package com.phairplay.dlna

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** Media3 1.4.1 has no LENGTH_UNSET constant; an unknown length is simply -1. */
private const val LENGTH_UNKNOWN = -1L

/** Redirect hops before giving up, matching media3's own data source. */
private const val MAX_REDIRECTS = 5

/**
 * v107 — an HTTP data source that does not pay the IPv6 wait.
 *
 * ## Why media3's own factory cannot do this
 *
 * Media3 1.4.1's `DefaultHttpDataSource` opens connections through the JVM's
 * `HttpURLConnection` and its `Factory` offers no DNS seam — resolution happens
 * inside the platform, which sorts addresses per RFC 6724 and therefore tries a
 * host's AAAA record first. On this network the router advertises IPv6 and hands
 * out a default route but the IPv6 data plane is a black hole, so those
 * connections go nowhere and a request proceeds only after the connect timeout
 * expires. Measured on real casts: 6.1 s, 12.6 s and 66.2 s of first byte — one
 * per retry step — against 144 ms for the one source publishing no AAAA record.
 * The same video plays quickly on a phone on the same Wi-Fi, which is what
 * proved the delay was ours rather than the origin's.
 *
 * ## Why the URI is not rewritten
 *
 * The hostname is kept for the `Host` header and for the URL media3 reports; for
 * plain http only the socket points at an IPv4 address. Rewriting the item URI to
 * an IP literal would break every HLS playlist with relative segment URLs, since
 * media3 resolves segments against the item's own URI. Hence a DataSource rather
 * than a URI rewrite.
 *
 * ## Why HTTPS keeps the hostname
 *
 * Dialling an IP literal breaks HTTPS outright: the handshake verifies the
 * certificate against the address and every source rejects it
 * (`SSLPeerUnverifiedException: Hostname 117.157.224.253 not verified`, measured
 * on the first HTTPS cast through this class). A custom `SSLSocketFactory` that
 * keeps SNI on the real name while connecting to the address is the textbook
 * answer, but this Android 7.1 platform client drives the handshake itself and
 * ignores the SNI set on the socket, so that path could not be trusted here.
 * HTTPS therefore keeps the name end to end and is de-prioritised at the resolver
 * instead — the same effect without touching TLS.
 */
internal class Ipv4HttpDataSource : BaseDataSource(false), HttpDataSource {

    private var connection: HttpURLConnection? = null
    private var inputStream: InputStream? = null
    private var currentUri: Uri? = null
    private var currentSpec: DataSpec = DataSpec(Uri.EMPTY)
    private var responseCodeInternal = HttpURLConnection.HTTP_OK
    private var responseHeadersInternal: Map<String, List<String>> = emptyMap()
    private var userAgent: String? = null
    private var allowCrossProtocolRedirects = true
    private var connectTimeout = 10_000
    private var readTimeout = 20_000

    private val requestProperties = LinkedHashMap<String, String>()

    private var firstReadLogged = false
    private var streamEnded = false
    private var bytesRemaining = 0L

    init {
        // Covers the HTTPS half; cheap, idempotent, and it only changes how
        // names resolve.
        Ipv4OnlyDns.preferIpv4()
    }

    override fun getUri(): Uri? = currentUri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        currentSpec = dataSpec
        try {
            val conn = openOverIpv4(dataSpec.uri)
            transferStarted(dataSpec)
            connection = conn
            responseCodeInternal = conn.responseCode
            responseHeadersInternal = conn.headerFields ?: emptyMap()
            logResponseHeaders(conn)
            inputStream = try {
                conn.inputStream
            } catch (e: IOException) {
                // An error status still has a body worth draining: the sender's
                // relay answers a challenge page here, and its text is the only
                // thing that says why.
                conn.errorStream
            } ?: throw IOException("HTTP ${conn.responseCode} with no body")
            // Media3 mirrors DefaultHttpDataSource here: what open() returns is
            // the number of bytes left in this resource, not the offset it
            // started at. Returning the position instead leaves `remaining`
            // wrong in [read], and every read hands back the wrong slice — which
            // is what made Mp4Extractor sniff three bytes, declare the container
            // unrecognised and fail the whole cast.
            val declared = dataSpec.length
            bytesRemaining = when {
                declared != LENGTH_UNKNOWN -> declared
                conn.contentLengthLong > 0 -> conn.contentLengthLong
                else -> 0L
            }
            if (dataSpec.position != 0L && responseCodeInternal == HttpURLConnection.HTTP_OK) {
                // A 200 answer to a request that asked for an offset has to be
                // skipped forward, the same way the platform class does it.
                var skipped = 0L
                while (skipped < dataSpec.position) {
                    val n = inputStream!!.skip(dataSpec.position - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                bytesRemaining -= skipped
            }
            return bytesRemaining
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(
                e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        }
    }

    /**
     * Opens [uri] against one of its IPv4 addresses, keeping the hostname in the
     * `Host` header so a plain-http request is indistinguishable from a
     * name-based one.
     *
     * The URL is rebuilt from the `Uri` components rather than through `URL`'s
     * string parsing: an HLS segment can arrive with a path that does not start
     * with a slash, and the three-argument `URL(scheme, host, file)` constructor
     * then duplicates the path — which is exactly what broke the first version
     * of this class (`.../270000001128/270000001128/...`).
     *
     * Every candidate failing means the source is genuinely unreachable, which is
     * a different verdict from "slow" and the probe reports them separately.
     */
    private fun openOverIpv4(uri: Uri): HttpURLConnection {
        val host = uri.host
        if (host.isNullOrBlank()) {
            // "Invalid host: http://[...]" points at the wrong place; say what
            // actually arrived instead.
            throw IOException("no host in URI: ${uri.toString().take(120)}")
        }
        val scheme = uri.scheme?.lowercase() ?: "http"
        val port = if (uri.port > 0) uri.port else defaultPort(scheme)
        val path = uri.encodedPath.orEmpty()
        val pathAndQuery = buildString {
            append(if (path.startsWith("/")) path else "/$path")
            uri.encodedQuery?.let { append('?').append(it) }
        }
        val hostHeader = if (port == defaultPort(scheme)) host else "$host:$port"

        val candidates = LinkedHashSet<String>()
        for (address in Ipv4OnlyDns.lookupOrEmpty(host)) {
            val ip = address.hostAddress ?: continue
            if (address is Inet4Address) candidates.add(ip)
        }
        if (candidates.isEmpty()) {
            throw IOException("no IPv4 address for $host (IPv6 does not leave this network)")
        }

        var lastFailure: IOException? = null
        for (ip in candidates) {
            // Plain http is dialled on the IPv4 address, which is where the wait
            // actually happened for every source measured; the `Host` header
            // keeps the request identical to a name-based one.
            //
            // https keeps the hostname so TLS verifies the real name — and it
            // uses the original URI verbatim rather than a rebuilt string,
            // because hand-assembling it produced a stream that began with
            // 0x00 instead of the `ftyp` box and every source failed container
            // sniffing. There is no error to work backwards from in that case,
            // so this path stays as untouched as possible.
            val target = if (scheme == "https") uri.toString() else "$scheme://$ip:$port$pathAndQuery"
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.instanceFollowRedirects = allowCrossProtocolRedirects
            if (scheme != "https") {
                // A restricted header on the platform client; harmless to set,
                // and it documents the intent for the http path where it matters.
                runCatching { conn.setRequestProperty("Host", hostHeader) }
            }
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            // Identity encoding keeps byte offsets aligned with the server's
            // stream, which matters because media3 seeks by absolute position.
            conn.setRequestProperty("Accept-Encoding", "identity")
            for ((key, value) in requestProperties) {
                if (key.equals("Host", ignoreCase = true)) continue
                conn.setRequestProperty(key, value)
            }
            try {
                conn.connect()
                if (conn.responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!allowCrossProtocolRedirects || location.isNullOrBlank()) {
                        throw IOException(
                            "redirect (HTTP ${conn.responseCode}) not followed for $host"
                        )
                    }
                    // Redirects are followed here rather than by the platform,
                    // because the platform would follow them against the IP
                    // literal and leak that address into the next request's Host
                    // header. media3's own data source does the same thing: read
                    // Location, cap the hops, re-resolve each target.
                    return followRedirects(uri, location)
                }
                return conn
            } catch (e: IOException) {
                conn.disconnect()
                lastFailure = e
            }
        }
        throw lastFailure ?: IOException("could not connect to $host over IPv4")
    }

    /**
     * Follows an HTTP redirect chain the way media3's own data source does:
     * read `Location`, cap the hops, and resolve every target again so the next
     * hop also gets the IPv4 treatment. Absolute and relative targets are both
     * accepted; a cross-protocol hop is passed to the platform, which is safe
     * because the URL then carries the real hostname.
     */
    private fun followRedirects(fromUri: Uri, location: String): HttpURLConnection {
        var target = java.net.URI(location).let {
            if (it.isAbsolute) it else java.net.URI(fromUri.toString()).resolve(it)
        }
        var hops = 0
        while (hops < MAX_REDIRECTS) {
            if (++hops > MAX_REDIRECTS) {
                throw IOException("Too many redirects: $location")
            }
            val nextUri = Uri.parse(target.toString())
            val nextScheme = nextUri.scheme?.lowercase() ?: "http"
            // An https hop goes by name: the certificate has to verify against
            // it, and the platform resolver is the only thing that can do that.
            if (nextScheme == "https") {
                return URL(target.toString()).openConnection() as HttpURLConnection
            }
            val host = nextUri.host
                ?: throw IOException("redirect without a host: $location")
            val ip = Ipv4OnlyDns.lookupOrEmpty(host).firstNotNullOfOrNull { it.hostAddress }
                ?: throw IOException("no IPv4 address for $host (IPv6 does not leave this network)")
            val port = if (nextUri.port > 0) nextUri.port else defaultPort(nextScheme)
            val path = nextUri.encodedPath.orEmpty()
            val conn = URL(
                "$nextScheme://$ip:$port" + (if (path.startsWith("/")) path else "/$path") +
                    (nextUri.encodedQuery?.let { "?$it" } ?: "")
            ).openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Host", host)
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            conn.setRequestProperty("Accept-Encoding", "identity")
            for ((key, value) in requestProperties) {
                if (key.equals("Host", ignoreCase = true)) continue
                conn.setRequestProperty(key, value)
            }
            conn.connect()
            if (conn.responseCode !in 300..399) return conn
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            if (next.isNullOrBlank()) {
                throw IOException("redirect without a Location header")
            }
            target = java.net.URI(next).let {
                if (it.isAbsolute) it else target.resolve(it)
            }
        }
        throw IOException("Too many redirects: $location")
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    /**
     * v107 — reports what the far end actually agreed to.
     *
     * A cast that reads three bytes and stops is indistinguishable, from the
     * outside, between a body that ended, a connection closed mid-stream and a
     * `Content-Length` that never matched. A CDN that dislikes the request
     * headers answers `200` with a tiny length and closes, which looks exactly
     * like a short file. These four headers separate those cases, and the
     * request headers go out with them so the two can be compared against a
     * curl of the same URL.
     */
    private fun logResponseHeaders(conn: HttpURLConnection) {
        val h = conn.headerFields.orEmpty()
        fun get(name: String): String =
            h.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.joinToString(",") ?: "-"

        android.util.Log.d(
            "Ipv4HttpDataSource",
            "open ${dataSpecUri()} code=${conn.responseCode} " +
                "CL=${get("Content-Length")} TE=${get("Transfer-Encoding")} " +
                "CR=${get("Content-Range")} Conn=${get("Connection")} " +
                "Type=${get("Content-Type")} CE=${get("Content-Encoding")}"
        )
        android.util.Log.d(
            "Ipv4HttpDataSource",
            "req ${dataSpecUri()} UA=${conn.getRequestProperty("User-Agent")} " +
                "AE=${conn.getRequestProperty("Accept-Encoding")} " +
                "Host=${conn.getRequestProperty("Host")} " +
                "extra=${requestProperties.keys}"
        )
    }

    private fun dataSpecUri(): String = currentSpec.uri.toString().take(100)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = inputStream ?: return -1
        if (length == 0) return 0
        if (bytesRemaining == 0L) {
            if (!streamEnded) {
                streamEnded = true
                transferEnded()
            }
            return -1
        }
        // DefaultHttpDataSource narrows the caller's length by what is left in
        // this resource (`min(remaining, length)`) before every read. Without
        // that, a caller asking for more than the resource holds gets a short
        // read that the extractors read as "end of stream".
        val want = if (bytesRemaining < length) bytesRemaining.toInt() else length
        // One underlying read is the platform's contract here: the caller asks
        // for as much as it can take and we give it, filling the buffer only as
        // far as the stream actually yields.
        val bytes = try {
            stream.read(buffer, offset, want)
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(
                e, currentSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        }
        if (bytes == -1) {
            if (!streamEnded) {
                streamEnded = true
                transferEnded()
            }
            return -1
        }
        bytesRemaining -= bytes
        // v107: the sniffers only get one chance, and "the stream ended after
        // three bytes" has to be distinguishable from "the stream ended after
        // three bytes because the far end closed". The second read's result is
        // the difference, so it is logged next to the first one.
        if (!firstReadLogged) {
            firstReadLogged = true
            val sample = buffer.copyOfRange(offset, minOf(offset + 16, offset + bytes)).toHex()
            android.util.Log.d(
                "Ipv4HttpDataSource",
                "read@${currentSpec.position}: got=$bytes want=$want remaining=$bytesRemaining " +
                    "code=$responseCodeInternal first=$sample url=${currentUri}"
            )
        } else if (bytes < want) {
            android.util.Log.d(
                "Ipv4HttpDataSource",
                "read@${currentSpec.position}: got=$bytes want=$want remaining=$bytesRemaining (short)"
            )
        }
        bytesTransferred(bytes)
        return bytes
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder()
        for (b in this) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    override fun close() {
        try {
            inputStream?.close()
            connection?.disconnect()
        } catch (e: Exception) {
            // The stream is being dropped either way; a failure here cannot be
            // acted on and must not mask the caller's own error.
        } finally {
            inputStream = null
            connection = null
            streamEnded = false
            bytesRemaining = 0L
            transferEnded()
        }
    }

    override fun setRequestProperty(key: String, value: String) {
        requestProperties[key] = value
    }

    override fun clearRequestProperty(key: String) {
        requestProperties.remove(key)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun getResponseCode(): Int = responseCodeInternal

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeadersInternal

    /**
     * Mirrors `DefaultHttpDataSource.Factory`'s shape so the call site stays as it
     * was, and keeps [Ipv4OnlyDns] the single definition of how a host resolves
     * for both playback and the source probe.
     */
    internal class Factory : HttpDataSource.Factory {
        private var userAgent: String? = null
        private var allowCrossProtocolRedirects = true
        private var connectTimeoutMs = 10_000
        private var readTimeoutMs = 20_000
        private var defaultRequestProperties: Map<String, String> = emptyMap()

        fun setUserAgent(value: String?): Factory = apply { userAgent = value }

        fun setAllowCrossProtocolRedirects(value: Boolean): Factory = apply {
            allowCrossProtocolRedirects = value
        }

        fun setConnectTimeoutMs(value: Int): Factory = apply { connectTimeoutMs = value }

        fun setReadTimeoutMs(value: Int): Factory = apply { readTimeoutMs = value }

        override fun setDefaultRequestProperties(value: Map<String, String>): HttpDataSource.Factory {
            defaultRequestProperties = value
            return this
        }

        override fun createDataSource(): HttpDataSource = Ipv4HttpDataSource().apply {
            userAgent = this@Factory.userAgent
            allowCrossProtocolRedirects = this@Factory.allowCrossProtocolRedirects
            connectTimeout = this@Factory.connectTimeoutMs
            readTimeout = this@Factory.readTimeoutMs
            for ((key, value) in defaultRequestProperties) setRequestProperty(key, value)
        }
    }
}