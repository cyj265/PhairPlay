package com.phairplay.dlna

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import com.phairplay.util.DebugLog
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/** Media3 1.4.1 has no LENGTH_UNSET constant; an unknown length is simply -1. */
private const val LENGTH_UNKNOWN = -1L

/**
 * v107–v110 — an HTTP data source that does not pay the IPv6 wait, and that
 * sends the correct `Host` header.
 *
 * ## Why OkHttp instead of `HttpURLConnection`
 *
 * Two constraints have to hold at once on this box:
 *
 * 1. **No IPv6 data plane.** The router advertises an IPv6 default route but
 *    does not forward it, so any connection that tries a host's AAAA record
 *    first goes to a black hole and only recovers after the connect timeout —
 *    measured 6.1 s / 12.6 s / 66.2 s of first byte against 144 ms for a
 *    source with no AAAA. The fix is to resolve only IPv4.
 * 2. **Correct `Host`.** Android's `HttpURLConnection` lists `Host` as a
 *    *restricted* header, so `setRequestProperty("Host", …)` is silently
 *    ignored (v107 proved this: rewriting the URL to an IP literal made the
 *    request carry `Host: <ip>`, and hosts that validate Host — douyincdn,
 *    TVbox anti-leech — answered 403, see doc §89/§90). The hostname therefore
 *    has to live in the URL itself, and the platform must be allowed to derive
 *    `Host` from it.
 *
 * `HttpURLConnection` cannot satisfy both: it exposes no DNS seam, and the one
 * trick that would have pinned only the socket (`setSocketFactory`) does not
 * exist at runtime on Android 7.1 (reflection confirms it is absent, so the
 * v110 attempt was a no-op and silently reintroduced the IPv6 black hole).
 * OkHttp gives us a first-class `Dns` interface, so we resolve only IPv4 while
 * the URL keeps the real hostname — `Host`, SNI and relative-HLS resolution
 * are all automatically correct, and redirects are followed safely per hop.
 */
internal class Ipv4HttpDataSource : BaseDataSource(false), HttpDataSource {

    private var call: okhttp3.Call? = null
    private var response: Response? = null
    private var bodyStream: InputStream? = null
    private var currentUri: Uri? = null
    private var currentSpec: DataSpec = DataSpec(Uri.EMPTY)
    private var responseCodeInternal = 0
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
        // Covers HTTPS and any other plain-Java connection in the process;
        // cheap, idempotent, and harmless once IPv6 forwarding is fixed.
        Ipv4OnlyDns.preferIpv4()
    }

    override fun getUri(): Uri? = currentUri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        currentSpec = dataSpec
        try {
            val resp = openOverIpv4(dataSpec)
            transferStarted(dataSpec)
            response = resp
            responseCodeInternal = resp.code
            responseHeadersInternal = resp.headers.toMultimap()
            logResponseHeaders(resp, dataSpec.uri.toString())
            val body: ResponseBody =
                resp.body ?: throw IOException("HTTP ${resp.code} with no body")
            bodyStream = body.byteStream()
            // Media3 mirrors DefaultHttpDataSource here: what open() returns is
            // the number of bytes left in this resource, not the offset it
            // started at. Returning the position instead leaves `remaining`
            // wrong in [read], and every read hands back the wrong slice — which
            // is what made Mp4Extractor sniff three bytes, declare the container
            // unrecognised and fail the whole cast.
            val declared = dataSpec.length
            bytesRemaining = when {
                declared != LENGTH_UNKNOWN -> declared
                else -> {
                    val cl = body.contentLength()
                    if (cl > 0L) cl else 0L
                }
            }
            if (dataSpec.position != 0L && responseCodeInternal in 200..299) {
                // A 200 answer to a request that asked for an offset has to be
                // skipped forward, the same way the platform class does it.
                var skipped = 0L
                val stream = bodyStream!!
                while (skipped < dataSpec.position) {
                    val n = stream.skip(dataSpec.position - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                if (bytesRemaining > 0L) bytesRemaining -= skipped
            }
            return bytesRemaining
        } catch (e: IOException) {
            throw HttpDataSourceException.createForIOException(
                e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            )
        }
    }

    /**
     * Opens [dataSpec.uri] through OkHttp with an IPv4-only [Dns].
     *
     * The hostname stays in the URL, so OkHttp emits the correct `Host` header
     * (and the correct SNI for HTTPS), and relative HLS segment URLs resolve
     * against the item's own URI. Redirects are followed automatically by the
     * client — each hop is re-resolved through the same IPv4-only DNS, so no AAAA
     * address is ever dialled.
     */
    private fun openOverIpv4(dataSpec: DataSpec): Response {
        val client = newIpv4OkHttpClient(connectTimeout, readTimeout)
        val builder = Request.Builder().url(dataSpec.uri.toString())
        // Range header, exactly as media3's DefaultHttpDataSource does it.
        if (dataSpec.position != 0L || dataSpec.length != LENGTH_UNKNOWN) {
            val range = if (dataSpec.length != LENGTH_UNKNOWN) {
                "bytes=${dataSpec.position}-${dataSpec.position + dataSpec.length - 1}"
            } else {
                "bytes=${dataSpec.position}-"
            }
            builder.header("Range", range)
        }
        userAgent?.let { builder.header("User-Agent", it) }
        // Identity encoding keeps byte offsets aligned with the server's stream,
        // which matters because media3 seeks by absolute position; it also stops
        // OkHttp from transparently gzip-decoding a body we want raw.
        builder.header("Accept-Encoding", "identity")
        for ((key, value) in requestProperties) {
            builder.header(key, value)
        }
        val c = client.newCall(builder.build())
        call = c
        return try {
            c.execute()
        } catch (e: IOException) {
            throw e
        }
        // OkHttp follows redirects itself (followRedirects/followSslRedirects on
        // the client), so the returned response is the terminal one. A 3xx that
        // reached us would mean redirection was disabled; we never disable it.
    }

    /**
     * v107 — reports what the far end actually agreed to, into the in-app
     * diagnostic log (visible at `http://<box>:8099/log`) rather than only
     * logcat. A cast that reads three bytes and stops is indistinguishable,
     * from the outside, between a short file and a connection that closed
     * mid-stream; these headers separate those cases. The request line also
     * records the hostname we sent `Host` for, which is the exact thing the
     * §89/§90 regression turned on.
     */
    private fun logResponseHeaders(resp: Response, url: String) {
        val h = resp.headers
        fun get(name: String): String =
            h[name] ?: "-"

        DebugLog.log(
            "DECODER",
            "Ipv4DS open url=$url code=${resp.code} " +
                "CL=${get("Content-Length")} TE=${get("Transfer-Encoding")} " +
                "CR=${get("Content-Range")} Conn=${get("Connection")} " +
                "Type=${get("Content-Type")} CE=${get("Content-Encoding")}"
        )
        DebugLog.log(
            "DECODER",
            "Ipv4DS req host=${resp.request.url.host} UA=${userAgent ?: "-"} " +
                "extra=${requestProperties.keys} range=${requestProperties["Range"] ?: "-"}"
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = bodyStream ?: return -1
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
        // read that the extractors read as "end of stream". When the length is
        // unknown (-1) we must never narrow with it.
        val want = if (bytesRemaining in 1L until length.toLong()) {
            bytesRemaining.toInt()
        } else {
            length
        }
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
        if (bytesRemaining > 0L) bytesRemaining -= bytes
        // v107: the sniffers only get one chance, and "the stream ended after
        // three bytes" has to be distinguishable from "the stream ended after
        // three bytes because the far end closed". The second read's result is
        // the difference, so it is logged next to the first one.
        if (!firstReadLogged) {
            firstReadLogged = true
            val sample = buffer.copyOfRange(offset, minOf(offset + 16, offset + bytes)).toHex()
            DebugLog.log(
                "DECODER",
                "Ipv4DS read@${currentSpec.position}: got=$bytes want=$want remaining=$bytesRemaining " +
                    "code=$responseCodeInternal first=$sample url=${currentUri}"
            )
        } else if (bytes < want) {
            DebugLog.log(
                "DECODER",
                "Ipv4DS read@${currentSpec.position}: got=$bytes want=$want remaining=$bytesRemaining (short)"
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
            bodyStream?.close()
            response?.close()
            call?.cancel()
        } catch (e: Exception) {
            // The stream is being dropped either way; a failure here cannot be
            // acted on and must not mask the caller's own error.
        } finally {
            bodyStream = null
            response = null
            call = null
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
     * Mirrors `DefaultHttpDataSource.Factory`'s shape so the call site stays as
     * it was, and keeps [Ipv4OnlyDns] the single definition of how a host
     * resolves for both playback and the source probe.
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

/**
 * IPv4-only DNS shared by playback and the source probe. It delegates to
 * [Ipv4OnlyDns.lookup], which drops AAAA records (the router advertises IPv6
 * but the data plane is a black hole) and throws [java.net.UnknownHostException]
 * when only AAAA exists — the same verdict the player would have reached anyway.
 */
internal val IPV4_OKDNS: Dns = object : Dns {
    override fun lookup(hostname: String) = Ipv4OnlyDns.lookup(hostname)
}

/**
 * Build an OkHttp client that resolves only IPv4. Used by both
 * [Ipv4HttpDataSource] (playback) and [com.phairplay.dlna.DlnaReceiver]'s
 * source probe, so the two paths can never disagree about which address family
 * they dial or what `Host` they send.
 */
internal fun newIpv4OkHttpClient(connectTimeoutMs: Int, readTimeoutMs: Int): OkHttpClient =
    OkHttpClient.Builder()
        .dns(IPV4_OKDNS)
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        // Redirects are followed per hop, each re-resolved through IPV4_OKDNS;
        // Host/SNI stay correct because the URL keeps the real hostname.
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
