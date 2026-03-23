package com.phairplay.airplay

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * RtspHandler — Manages the RTSP session with the AirPlay sender (macOS).
 *
 * WHY: AirPlay uses RTSP (Real Time Streaming Protocol) to negotiate and control
 * a streaming session. Before any video or audio arrives, macOS and PhairPlay
 * must exchange a series of RTSP messages to agree on codecs, ports, and encryption keys.
 *
 * HOW: Listens on TCP port 7000. When a macOS device connects, it handles the
 * RTSP message exchange, extracts stream parameters from the SDP body, and then
 * creates [VideoDecoder] and [AudioPlayer] instances to handle the media.
 *
 * The RTSP state machine:
 *   IDLE → (client connects) → CONNECTED → (OPTIONS/SETUP/ANNOUNCE) → NEGOTIATING
 *   NEGOTIATING → (RECORD received) → STREAMING → (TEARDOWN or disconnect) → IDLE
 *
 * Example:
 *   val handler = RtspHandler(
 *       videoSurface = surface,
 *       onStreamingStarted = { showVideo() },
 *       onStreamingStopped = { showWaiting() }
 *   )
 *   handler.start(coroutineScope)
 *   handler.stop()
 */
class RtspHandler(
    // Called lazily when RECORD is received — Surface is ready by then
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit
) {

    // The server socket that accepts incoming AirPlay connections on port 7000
    private var serverSocket: ServerSocket? = null

    // The currently active client connection (only one at a time, per REQUIREMENTS.md FR-10)
    @Volatile
    private var activeClient: Socket? = null

    // Flag to signal that we should stop accepting new connections
    @Volatile
    private var running = false

    // RTSP CSeq counter — each RTSP response must echo back the request's CSeq number
    private var currentCSeq: Int = 0

    // Parsed SDP from the most recent ANNOUNCE — stored for use in SETUP and RECORD
    @Volatile
    private var currentSession: SessionDescription? = null

    // Counter for SETUP calls: first is typically video, second is audio
    private var setupCount = 0

    /**
     * Starts the RTSP server.
     *
     * Opens TCP port 7000 and begins accepting connections in a background coroutine.
     * This method returns immediately; the actual work runs asynchronously.
     *
     * @param scope The [CoroutineScope] to launch the server coroutine in.
     *              Should be the AirPlayReceiver's SupervisorJob scope so that
     *              a crash here doesn't kill the mDNS service.
     */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    /**
     * Stops the RTSP server.
     *
     * Closes the server socket (which causes the accept() call to throw, ending the loop),
     * disconnects any active client, and releases media resources.
     *
     * Safe to call from any thread.
     */
    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        Logger.i("RTSP handler stopped")
    }

    /**
     * The main server loop. Listens on port 7000 and handles one client at a time.
     *
     * SECURITY: Only one client is accepted at a time (FR-07). A second connection
     * attempt while a client is active receives a 503 response and is disconnected.
     *
     * @param scope Used to check if the coroutine is still active (for graceful shutdown).
     */
    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = ServerSocket(RTSP_PORT)
            Logger.i("RTSP server listening on port $RTSP_PORT")

            while (running && scope.isActive) {
                // accept() blocks until a client connects — this is intentional
                val clientSocket = serverSocket!!.accept()
                Logger.i("New client connected: ${clientSocket.inetAddress.hostAddress}")

                // SECURITY: Only one active client at a time (see FR-07)
                if (activeClient != null && !activeClient!!.isClosed) {
                    Logger.w("Rejecting second client — already streaming")
                    sendServiceUnavailable(clientSocket)
                    clientSocket.close()
                    continue
                }

                activeClient = clientSocket
                handleClient(clientSocket)
            }
        } catch (e: Exception) {
            // SocketException is thrown when serverSocket.close() is called from stop()
            // This is expected behavior during shutdown, not a real error
            if (running) {
                Logger.e("RTSP server error (unexpected)", e)
            } else {
                Logger.d("RTSP server socket closed (expected during shutdown)")
            }
        }
    }

    /**
     * Handles all RTSP communication with a single connected client.
     *
     * Reads RTSP requests line by line, routes each request to the appropriate
     * handler method, and sends responses. Loops until the client disconnects
     * or the handler is stopped.
     *
     * @param socket The connected client socket.
     */
    private fun handleClient(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val writer = PrintWriter(socket.outputStream, true)

        try {
            while (running && !socket.isClosed) {
                // Read and parse the next RTSP request
                val request = readRtspRequest(reader) ?: break

                // Route to the appropriate method handler
                val response = routeRequest(request, writer)

                // Send the response back to the client
                sendResponse(writer, response)
            }
        } catch (e: Exception) {
            if (running) {
                Logger.e("Error handling RTSP client", e)
            }
        } finally {
            Logger.i("Client disconnected")
            socket.close()
            activeClient = null
            onStreamingStopped()
        }
    }

    /**
     * Reads a complete RTSP request from the [reader].
     *
     * An RTSP request consists of:
     * 1. Request line:  "METHOD rtsp://... RTSP/1.0"
     * 2. Headers:       "Key: Value" pairs, one per line
     * 3. Blank line:    signals end of headers
     * 4. Optional body: present if Content-Length header > 0 (e.g., SDP in ANNOUNCE)
     *
     * SECURITY: Total message size is limited to [MAX_MESSAGE_BYTES] to prevent
     * buffer exhaustion attacks (DoS via huge messages).
     *
     * @return An [RtspRequest] object, or null if the connection was closed cleanly.
     */
    private fun readRtspRequest(reader: BufferedReader): RtspRequest? {
        // Read the request line (e.g., "OPTIONS rtsp://192.168.1.1/... RTSP/1.0")
        val requestLine = reader.readLine() ?: return null  // null = connection closed
        if (requestLine.isBlank()) return null

        // SECURITY: Validate that the request line has the expected format
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            Logger.w("Malformed RTSP request line: '$requestLine'")
            return null
        }

        val method = parts[0]
        val uri = parts[1]

        // Read headers until a blank line
        val headers = mutableMapOf<String, String>()
        var totalBytes = requestLine.length
        var line = reader.readLine()

        while (line != null && line.isNotBlank()) {
            totalBytes += line.length

            // SECURITY: Reject messages that exceed the maximum allowed size
            if (totalBytes > MAX_MESSAGE_BYTES) {
                Logger.w("RTSP message too large ($totalBytes bytes) — rejecting")
                return null
            }

            // Parse "Key: Value" header format
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
            line = reader.readLine()
        }

        // Remember the CSeq for the response (RTSP requires echoing the request's CSeq)
        currentCSeq = headers["CSeq"]?.toIntOrNull() ?: 0

        // Read the body if Content-Length is present
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            // SECURITY: Validate body length before reading
            if (contentLength > MAX_MESSAGE_BYTES) {
                Logger.w("RTSP body too large ($contentLength bytes) — rejecting")
                return null
            }
            val bodyChars = CharArray(contentLength)
            reader.read(bodyChars, 0, contentLength)
            String(bodyChars)
        } else {
            ""
        }

        return RtspRequest(method = method, uri = uri, headers = headers, body = body)
    }

    /**
     * Routes an [RtspRequest] to the appropriate handler method.
     *
     * Each RTSP method has a specific role in the AirPlay protocol:
     * - OPTIONS:  macOS asks "what can you do?" — we reply with our supported methods
     * - ANNOUNCE: macOS sends the SDP describing codecs and encryption keys
     * - SETUP:    macOS requests that we set up a media channel (video or audio)
     * - RECORD:   macOS says "start sending media now"
     * - TEARDOWN: macOS says "stop and clean up"
     * - GET/SET_PARAMETER: used for keep-alive and metadata updates
     *
     * @return An [RtspResponse] to send back to the client.
     */
    private fun routeRequest(request: RtspRequest, writer: PrintWriter): RtspResponse {
        Logger.d("RTSP ${request.method} ${request.uri}")
        return when (request.method) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            "SETUP"         -> handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request)
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> handleGetParameter(request)
            "SET_PARAMETER" -> handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            else            -> handleUnknownInternal(request)
        }
    }

    /**
     * Handles OPTIONS — macOS asks "what RTSP methods do you support?"
     *
     * We respond with the list of methods PhairPlay supports. This is the
     * first message in every AirPlay session.
     *
     * Exposed as `internal open` so unit tests can call it via [TestableRtspHandler]
     * without requiring a real network socket.
     */
    internal open fun handleOptionsInternal(request: RtspRequest): RtspResponse {
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf(
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        )
    }

    /**
     * Handles ANNOUNCE — macOS/iOS sends the SDP body describing codecs, ports, and encryption.
     *
     * We parse the SDP with [SdpParser] and store the result in [currentSession].
     * The session is used later in SETUP and RECORD to configure the media pipeline.
     *
     * Security: if SDP parsing fails completely, we return 400 Bad Request.
     */
    internal open fun handleAnnounceInternal(request: RtspRequest): RtspResponse {
        Logger.d("ANNOUNCE body (${request.body.length} bytes)")
        currentSession = SdpParser.parse(request.body)

        if (currentSession == null) {
            Logger.e("ANNOUNCE: SDP parsing returned no usable session — rejecting")
            return RtspResponse(statusCode = 400, statusMessage = "Bad Request")
        }

        val s = currentSession!!
        Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
                 "audioCodec=${s.audioCodec} encrypted=${s.isAudioEncrypted}")

        setupCount = 0
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles SETUP — macOS/iOS requests allocation of a media channel.
     *
     * AirPlay sends two SETUP requests: one for video, one for audio (in that order).
     * We respond with the transport params and a session ID.
     *
     * Transport negotiation:
     * - Video: TCP interleaved (piggy-backed on the RTSP TCP connection, `$` framing)
     * - Audio: UDP (separate socket; port negotiated here)
     *
     * For audio-only streams there is only one SETUP (for audio).
     */
    internal open fun handleSetupInternal(request: RtspRequest): RtspResponse {
        setupCount++
        val session = currentSession

        // Determine if this SETUP is for video or audio based on order and session info
        val isVideoSetup = setupCount == 1 && session?.hasVideo == true

        val transport = if (isVideoSetup) {
            // Video: interleaved over the existing RTSP TCP connection
            "RTP/AVP/TCP;unicast;interleaved=0-1"
        } else {
            // Audio: UDP to a fixed port we allocate
            "RTP/AVP/UDP;unicast;client_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "server_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1}"
        }

        Logger.d("SETUP #$setupCount — transport: $transport")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Session" to SESSION_ID, "Transport" to transport)
        )
    }

    /**
     * Handles RECORD — macOS/iOS says "start sending media now".
     *
     * Invokes [onStreamingStarted] with the parsed [SessionDescription] so the caller
     * can wire up [VideoDecoder] and/or [AudioPlayer] as appropriate.
     * For audio-only streams, only [AudioPlayer] is started.
     */
    internal open fun handleRecordInternal(request: RtspRequest): RtspResponse {
        val session = currentSession
        if (session == null) {
            Logger.e("RECORD received but no session from ANNOUNCE — rejecting")
            return RtspResponse(statusCode = 455, statusMessage = "Method Not Valid in This State")
        }
        Logger.i("RECORD — streaming starting (audioOnly=${session.isAudioOnly})")
        onStreamingStarted(session)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles TEARDOWN — macOS says "stop and clean up".
     *
     * The streaming session is over. We clean up resources and return to WAITING state.
     */
    internal open fun handleTeardownInternal(request: RtspRequest): RtspResponse {
        Logger.i("TEARDOWN received — streaming stopping")
        onStreamingStopped()
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles GET_PARAMETER — used by macOS as a keep-alive ping.
     * We respond with 200 OK and an empty body.
     */
    private fun handleGetParameter(request: RtspRequest): RtspResponse {
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles SET_PARAMETER — macOS may send metadata (volume, track info, etc.).
     * We acknowledge receipt but currently ignore the content.
     */
    private fun handleSetParameter(request: RtspRequest): RtspResponse {
        Logger.d("SET_PARAMETER: ${request.body}")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles any unrecognized RTSP method.
     * Returns 501 Not Implemented, which is the correct RTSP response for unknown methods.
     */
    internal open fun handleUnknownInternal(request: RtspRequest): RtspResponse {
        Logger.w("Unknown RTSP method: ${request.method}")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented")
    }

    /**
     * Handles FLUSH — macOS requests we discard buffered media data.
     * Used during seek or pause operations.
     */
    private fun handleFlush(request: RtspRequest): RtspResponse {
        // TODO: Flush MediaCodec input buffers when implemented
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Sends an RTSP response to the client.
     *
     * RTSP response format:
     *   RTSP/1.0 <statusCode> <statusMessage>\r\n
     *   CSeq: <n>\r\n
     *   <header>: <value>\r\n
     *   \r\n
     *   [optional body]
     *
     * @param writer The output writer to the client socket.
     * @param response The [RtspResponse] to serialize and send.
     */
    private fun sendResponse(writer: PrintWriter, response: RtspResponse) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 ${response.statusCode} ${response.statusMessage}\r\n")
        sb.append("CSeq: $currentCSeq\r\n")
        sb.append("Server: PhairPlay/1.0\r\n")
        response.headers.forEach { (key, value) ->
            sb.append("$key: $value\r\n")
        }
        if (response.body.isNotEmpty()) {
            sb.append("Content-Length: ${response.body.length}\r\n")
        }
        sb.append("\r\n")
        if (response.body.isNotEmpty()) {
            sb.append(response.body)
        }
        writer.print(sb.toString())
        writer.flush()
    }

    /**
     * Sends a 503 Service Unavailable response to a client that connected while
     * another session is already active (enforcing FR-07: one sender at a time).
     *
     * @param socket The socket of the rejected client.
     */
    private fun sendServiceUnavailable(socket: Socket) {
        try {
            val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
            socket.outputStream.write(response.toByteArray())
            socket.outputStream.flush()
        } catch (e: Exception) {
            Logger.e("Error sending 503 response", e)
        }
    }

    companion object {
        /** Standard AirPlay RTSP port. */
        private const val RTSP_PORT = 7000

        /**
         * Maximum allowed RTSP message size (security: prevents DoS via huge messages).
         * 64 KB is well above any legitimate RTSP/SDP message size.
         */
        private const val MAX_MESSAGE_BYTES = 65536

        /** Fixed session ID — one session at a time. */
        private const val SESSION_ID = "PhairPlaySession"

        /**
         * UDP port for receiving audio RTP packets.
         * We use a fixed port for simplicity (one session at a time).
         * Must not conflict with the RTSP port (7000).
         */
        private const val AUDIO_RTP_PORT = 6001
    }
}

/**
 * RtspRequest — Represents a parsed RTSP request from the client.
 *
 * @param method  The RTSP method (e.g., "OPTIONS", "ANNOUNCE", "RECORD")
 * @param uri     The request URI (e.g., "rtsp://192.168.1.1/phairplay")
 * @param headers All headers as a key→value map (case-sensitive keys)
 * @param body    The request body (empty string if no body)
 */
data class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val body: String
)

/**
 * RtspResponse — Represents an RTSP response to send to the client.
 *
 * @param statusCode    HTTP-like status code (200 = OK, 503 = unavailable, etc.)
 * @param statusMessage Human-readable status (e.g., "OK", "Not Found")
 * @param headers       Optional extra headers to include in the response
 * @param body          Optional response body (e.g., SDP content)
 */
data class RtspResponse(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = ""
)
