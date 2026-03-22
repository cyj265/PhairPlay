package com.phairplay.airplay

import android.content.Context
import android.view.Surface
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver.
 *
 * WHY: This class is the central coordinator that ties together all the components:
 * mDNS advertising ([MdnsService]), RTSP session handling ([RtspHandler]),
 * video decoding ([VideoDecoder]), and audio playback ([AudioPlayer]).
 * Having one orchestrator keeps the complexity out of MainActivity and makes
 * the lifecycle and state transitions easy to reason about.
 *
 * HOW: Call [start] when the Activity is created and [stop] when it is destroyed.
 * The receiver manages its own coroutine scope and cleans up all resources on stop.
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = this,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> handleStateChange(state) }
 *   )
 *   receiver.start()   // in onCreate()
 *   receiver.stop()    // in onDestroy()
 */
class AirPlayReceiver(
    private val context: Context,
    // Lambda that returns the video Surface — called lazily when streaming starts,
    // by which time the SurfaceView has completed its layout pass and is ready.
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (AirPlayState) -> Unit
) {

    // SupervisorJob: if one child coroutine fails, the others keep running.
    // This prevents a network glitch from crashing the entire receiver.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Current state — starts in WAITING, switches to STREAMING when a client connects
    @Volatile
    private var currentState: AirPlayState = AirPlayState.WAITING

    // Child components — initialized in start(), cleaned up in stop()
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null

    /**
     * Starts the AirPlay receiver.
     *
     * This method:
     * 1. Creates and starts the mDNS service (makes the device discoverable)
     * 2. Creates and starts the RTSP handler (listens for incoming connections)
     * 3. Emits the initial [AirPlayState.WAITING] state to the UI
     *
     * This method is non-blocking — all network operations run in background coroutines.
     */
    fun start() {
        Logger.i("AirPlayReceiver starting")
        scope.launch {
            try {
                startMdnsService()
                startRtspHandler()
                emitState(AirPlayState.WAITING)
            } catch (e: Exception) {
                // Catch startup errors so the app doesn't crash — log and show waiting screen
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(AirPlayState.WAITING)
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * This method:
     * 1. Stops the RTSP handler (closes sockets, releases MediaCodec and AudioTrack)
     * 2. Stops mDNS advertising (device disappears from AirPlay picker)
     * 3. Cancels all background coroutines
     *
     * RULE 5: This MUST be called from Activity.onDestroy() to prevent memory leaks.
     * After stop(), this object should not be reused — create a new instance instead.
     */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        try {
            rtspHandler?.stop()
            mdnsService?.stop()
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            // Cancel all coroutines — this is safe even if they've already finished
            scope.cancel()
        }
    }

    /**
     * Called by [RtspHandler] when a macOS sender connects and starts streaming.
     * Switches the UI to the StreamingScreen.
     *
     * This method is called from the IO dispatcher (network thread), so it
     * dispatches to the Main thread before notifying the UI.
     */
    internal fun onStreamingStarted() {
        Logger.i("Streaming started")
        emitState(AirPlayState.STREAMING)
    }

    /**
     * Called by [RtspHandler] when a macOS sender disconnects (TEARDOWN or socket close).
     * Switches the UI back to the WaitingScreen and restarts mDNS advertising.
     *
     * This method is called from the IO dispatcher (network thread), so it
     * dispatches to the Main thread before notifying the UI.
     */
    internal fun onStreamingStopped() {
        Logger.i("Streaming stopped — returning to waiting state")
        emitState(AirPlayState.WAITING)
        // Re-advertise so the device is immediately visible in the macOS AirPlay picker again
        scope.launch {
            try {
                mdnsService?.restart()
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming stopped", e)
            }
        }
    }

    /**
     * Initializes and starts the mDNS service.
     * Must be called from a coroutine scope (runs on IO dispatcher).
     */
    private fun startMdnsService() {
        mdnsService = MdnsService(context).also { it.start() }
        Logger.d("mDNS service started")
    }

    /**
     * Initializes and starts the RTSP handler.
     * Must be called from a coroutine scope (runs on IO dispatcher).
     *
     * The videoSurfaceProvider lambda is passed through to RtspHandler, which
     * calls it when the RTSP RECORD command is received (at which point the
     * Surface is guaranteed to be ready after the Activity's initial layout pass).
     */
    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { onStreamingStarted() },
            onStreamingStopped = { onStreamingStopped() }
        ).also { it.start(scope) }
        Logger.d("RTSP handler started")
    }

    /**
     * Emits a state change to the UI callback.
     *
     * The callback is always dispatched on the Main thread because UI updates
     * must run on the Main thread (Android rule). This method handles the
     * thread switch from IO → Main transparently.
     *
     * @param state The new [AirPlayState] to emit.
     */
    private fun emitState(state: AirPlayState) {
        if (currentState == state) return  // Skip no-op transitions
        currentState = state
        scope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(state)
            }
        }
    }
}
