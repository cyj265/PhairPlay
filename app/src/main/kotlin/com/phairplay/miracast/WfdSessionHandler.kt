// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.content.Context
import com.phairplay.util.Logger
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Wi-Fi Display RTSP session handler (Sink side).
 *
 * WFD is unusual: the **Source listens** on 7236 and the Sink dials it. Once the TCP
 * connection exists both sides are peers on the same stream:
 *
 * ```
 * M1  Source → Sink   OPTIONS          answer 200 + Public
 * M2  Sink   → Source OPTIONS          sent by us
 * M3  Source → Sink   GET_PARAMETER    answer capabilities (needs wfd_client_rtp_ports)
 * M4  Source → Sink   SET_PARAMETER    selected format + presentation URL
 * M5  Source → Sink   SET_PARAMETER    wfd_trigger_method: SETUP
 * M6  Sink   → Source SETUP            sent by us, carries client_port
 * M7  Sink   → Source PLAY             sent by us, RTP starts right after
 * ```
 *
 * @param socket TCP connection already dialed to the Source
 * @param rtpPort local UDP port advertised to the Source in M3 and M6
 */
class WfdSessionHandler(
    private val context: Context,
    private val socket: Socket,
    private val rtpPort: Int,
    private val customModeProvider: (Boolean) -> WfdCustomVideoMode? = { wfdCustomVideoMode(context, it) }
) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    private var outCseq = 0
    private var sessionId = ""
    private var presentationUrl = ""
    private var playRequested = false
    private var advertisedCustomMode: WfdCustomVideoMode? = null

    var onSessionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStart: ((rtpPort: Int) -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null

    companion object {
        private const val AUDIO_CODECS = "AAC 00000001 00"
    }

    fun handleSession() {
        try {
            Logger.i("WFD session started with source ${socket.inetAddress.hostAddress}")
            while (!socket.isClosed) {
                val msg = readMessage() ?: break
                if (msg.startsWith("RTSP/1.0")) handleResponse(msg) else handleRequest(msg)
            }
        } catch (e: Exception) {
            Logger.e("Error in WFD session", e)
        } finally {
            Logger.i("WFD session ended")
            onStreamStop?.invoke()
            close()
        }
    }

    /** Read one complete RTSP message (headers + body sized by Content-Length). */
    private fun readMessage(): String? {
        // A Source whose handshake failed may never close the TCP connection. Don't let it
        // occupy the only session loop forever. Keep a longer timeout during playback so
        // protocol keep-alives are accepted.
        socket.soTimeout = if (sessionId.isEmpty()) 15_000 else 90_000
        val buf = StringBuilder()
        val one = ByteArray(1)

        while (!buf.endsWith("\r\n\r\n")) {
            val n = input.read(one)
            if (n <= 0) return null
            buf.append(one[0].toInt().toChar())
            if (buf.length > 64 * 1024) {
                Logger.w("WFD: header too large, dropping session")
                return null
            }
        }

        val contentLength = Regex("(?i)Content-Length:\\s*(\\d+)")
            .find(buf)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n <= 0) return null
                read += n
            }
            buf.append(String(body, StandardCharsets.UTF_8))
        }

        val msg = buf.toString()
        Logger.d("WFD >> ${msg.lineSequence().first()}")
        Logger.v("WFD >> full:\n$msg")
        return msg
    }

    @Synchronized
    private fun send(msg: String) {
        Logger.d("WFD << ${msg.lineSequence().first()}")
        Logger.v("WFD << full:\n$msg")
        output.write(msg.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun handleRequest(msg: String) {
        val method = msg.substringBefore(' ')
        val cseq = header(msg, "CSeq") ?: "0"

        when (method) {
            "OPTIONS" -> {
                sendOk(cseq, "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n")
                sendOptions()                                    // M2
            }
            "GET_PARAMETER" -> {
                val requested = msg.substringAfter("\r\n\r\n", "").lineSequence()
                    .map { it.substringBefore(':').trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }.toSet()
                if (requested.any { it.startsWith("wfd") || it == "microsoft_custom_video_formats" })
                    sendCapabilities(cseq, requested)            // M3
                else sendOk(cseq)                                // keep-alive
            }
            "SET_PARAMETER" -> {
                // M4 carries two presentation URLs ("<url> none"); only the first is valid.
                param(msg, "wfd_presentation_URL")
                    ?.substringBefore(' ')
                    ?.takeIf { it.startsWith("rtsp://") }
                    ?.let {
                        presentationUrl = it
                        Logger.i("WFD: presentation URL = $it")
                    }
                (param(msg, "wfdx_video_formats") ?: param(msg, "wfd_video_formats"))?.let { selected ->
                    Logger.i("WFD: source selected video format = $selected")
                    val ceaBit = selected.split(' ').getOrNull(4)?.toLongOrNull(16) ?: 0L
                    // WFD 1.0 table 5-1: one bit per CEA timing.
                    val mode = when (ceaBit) {
                        0x0001L -> "640x480p60"
                        0x0002L -> "720x480p60"
                        0x0004L -> "720x480i60"
                        0x0008L -> "720x576p50"
                        0x0010L -> "720x576i50"
                        0x0020L -> "1280x720p30"
                        0x0040L -> "1280x720p25"
                        0x0080L -> "1280x720p24"
                        0x0100L -> "1920x1080p30"
                        0x0200L -> "1920x1080p25"
                        0x0400L -> "1920x1080p24"
                        0x0800L -> "1920x1080i60"
                        0x1000L -> "1920x1080i50"
                        0x2000L -> "1280x720p60"
                        0x4000L -> "1280x720p50"
                        0x8000L -> "1920x1080p60"
                        else -> "CEA 0x%08X".format(ceaBit)
                    }
                    Logger.i("WFD: negotiated mode = $mode")
                }
                param(msg, "microsoft_custom_video_formats")?.takeIf { it != "none" }?.let { selected ->
                    val expected = advertisedCustomMode
                    if (expected == null || !matchesWfdCustomMode(selected, expected)) {
                        Logger.w("WFD: source selected an unadvertised or malformed custom mode")
                        send("RTSP/1.0 400 Bad Request\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n")
                        return
                    }
                    Logger.i("WFD: negotiated custom mode = ${expected.width}x${expected.height} p${expected.fps}")
                }
                param(msg, "wfd_audio_codecs")?.let {
                    Logger.i("WFD: source selected audio codec = $it")
                }
                sendOk(cseq)
                if (msg.contains("wfd_trigger_method: SETUP")) sendSetup()      // M5 → M6
                if (msg.contains("wfd_trigger_method: TEARDOWN")) close()
            }
            "TEARDOWN" -> {
                sendOk(cseq)
                onStreamStop?.invoke()
                close()
            }
            else -> sendOk(cseq)
        }
    }

    private fun handleResponse(msg: String) {
        val session = header(msg, "Session")?.substringBefore(';')
        if (!session.isNullOrBlank() && sessionId.isEmpty()) {
            sessionId = session
            Logger.i("WFD: session id = $sessionId")
            onSessionEstablished?.invoke(sessionId)
            sendPlay()                                           // M7
        } else if (playRequested) {
            Logger.i("WFD: PLAY acknowledged, RTP should start on $rtpPort")
            onStreamStart?.invoke(rtpPort)
            playRequested = false
        }
    }

    private fun sendOptions() = send(
        "OPTIONS * RTSP/1.0\r\n" +
            "CSeq: ${++outCseq}\r\n" +
            "Require: org.wfa.wfd1.0\r\n\r\n"
    )

    private fun sendSetup() {
        if (presentationUrl.isEmpty()) {
            presentationUrl = "rtsp://${socket.inetAddress.hostAddress}/wfd1.0/streamid=0"
        }
        send(
            "SETUP $presentationUrl RTSP/1.0\r\n" +
                "CSeq: ${++outCseq}\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort\r\n\r\n"
        )
    }

    private fun sendPlay() {
        playRequested = true
        send(
            "PLAY $presentationUrl RTSP/1.0\r\n" +
                "CSeq: ${++outCseq}\r\n" +
                "Session: $sessionId\r\n\r\n"
        )
    }

    private fun sendOk(cseq: String, extraHeaders: String = "") =
        send("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extraHeaders\r\n")

    /**
     * M3 capability response: answer only what the Source asked for.
     * Windows asks for ~27 parameters (including wfd2_* / intel_* / microsoft_* extensions);
     * the standard format is always kept, custom modes are declared only when queried.
     */
    private fun sendCapabilities(cseq: String, requested: Set<String>) {
        val mode = if ("microsoft_custom_video_formats" in requested || "wfdx_video_formats" in requested)
            customModeProvider("wfdx_video_formats" in requested) else null
        if ("microsoft_custom_video_formats" in requested) advertisedCustomMode = mode?.takeIf {
            "wfdx_video_formats" in requested || it.levelBit <= 0x10
        }
        if (mode != null) {
            Logger.i("WFD: custom receive declaration = ${mode.width}x${mode.height} p${mode.fps}")
        }
        val body = buildString {
            wfdVideoCapabilityLines(requested, mode).forEach { append(it).append("\r\n") }
            append("wfd_audio_codecs: $AUDIO_CODECS\r\n")
            // Critical: tells the Source which UDP port receives RTP. Without this no picture.
            append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $rtpPort 0 mode=play\r\n")
            append("wfd_content_protection: none\r\n")
            append("wfd_display_edid: none\r\n")
            append("wfd_uibc_capability: none\r\n")
            append("wfd_connector_type: 05\r\n")
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n" +
                body
        )
    }

    private fun header(msg: String, name: String): String? =
        Regex("(?i)^$name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun param(msg: String, name: String): String? =
        Regex("(?i)^$name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            Logger.e("Error closing WFD session socket", e)
        }
    }

    fun disconnectSender() {
        try {
            if (sessionId.isNotEmpty()) send(
                "TEARDOWN $presentationUrl RTSP/1.0\r\nCSeq: ${++outCseq}\r\nSession: $sessionId\r\n\r\n"
            )
        } finally {
            close()
        }
    }
}
