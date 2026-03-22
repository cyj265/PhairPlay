package com.phairplay

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.phairplay.airplay.AirPlayReceiver
import com.phairplay.airplay.AirPlayState
import com.phairplay.ui.StreamingScreen
import com.phairplay.ui.WaitingScreen
import timber.log.Timber

/**
 * MainActivity — The single Activity that hosts the entire PhairPlay UI.
 *
 * WHY: PhairPlay has exactly two visual states (waiting / streaming), so a
 * single Activity with two swappable screens is the simplest architecture.
 * Using multiple Activities would require passing the MediaCodec Surface across
 * Activity transitions, which is complex and error-prone.
 *
 * HOW: Create an instance of [AirPlayReceiver] and observe its state.
 * Show [WaitingScreen] when idle, show [StreamingScreen] when streaming.
 *
 * Example lifecycle:
 *   App starts → onCreate() → AirPlayReceiver starts → WaitingScreen shown
 *   macOS connects → AirPlayReceiver.state = STREAMING → StreamingScreen shown
 *   macOS disconnects → AirPlayReceiver.state = WAITING → WaitingScreen shown
 *   User presses Back → onDestroy() → AirPlayReceiver stops
 */
class MainActivity : AppCompatActivity() {

    // The top-level orchestrator that manages mDNS, RTSP, video, and audio
    private lateinit var airPlayReceiver: AirPlayReceiver

    // UI containers from activity_main.xml
    private lateinit var waitingContainer: FrameLayout
    private lateinit var streamingContainer: FrameLayout

    // Screen fragments/views — created once, swapped in/out of containers
    private lateinit var waitingScreen: WaitingScreen
    private lateinit var streamingScreen: StreamingScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Timber.d("MainActivity created")

        bindViews()
        createScreens()
        createAirPlayReceiver()
        startReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        // RULE 5: All resources must be released in onDestroy() to prevent memory leaks.
        // Stopping the receiver closes all sockets, releases MediaCodec, releases AudioTrack.
        Timber.d("MainActivity destroyed — stopping AirPlayReceiver")
        airPlayReceiver.stop()
    }

    /**
     * Finds and stores references to the View containers defined in activity_main.xml.
     * Called once from onCreate().
     */
    private fun bindViews() {
        waitingContainer = findViewById(R.id.waiting_container)
        streamingContainer = findViewById(R.id.streaming_container)
    }

    /**
     * Creates the WaitingScreen and StreamingScreen instances and adds them
     * to their respective containers. Both are created eagerly so that the
     * StreamingScreen's SurfaceView can begin its lifecycle before streaming starts.
     *
     * WHY eager creation: MediaCodec needs an active Surface *before* the first
     * video frame arrives. Pre-creating the StreamingScreen ensures the Surface is
     * ready when the RTSP RECORD command is received.
     */
    private fun createScreens() {
        waitingScreen = WaitingScreen(this)
        waitingContainer.addView(waitingScreen)

        streamingScreen = StreamingScreen(this)
        streamingContainer.addView(streamingScreen)
    }

    /**
     * Initializes the AirPlayReceiver and sets up the state change callback.
     *
     * The Surface from StreamingScreen may not be ready immediately (it is created
     * asynchronously by SurfaceView). We pass a lazy Surface provider lambda so
     * AirPlayReceiver can retrieve it when the RTSP RECORD is received (by which
     * time the Surface is guaranteed to be ready after the initial layout pass).
     *
     * The callback runs on the Main thread (guaranteed by AirPlayReceiver's
     * internal coroutine dispatcher switch) so it is safe to update the UI directly.
     */
    private fun createAirPlayReceiver() {
        airPlayReceiver = AirPlayReceiver(
            context = this,
            videoSurfaceProvider = { streamingScreen.getSurface() },
            onStateChanged = { state -> handleStateChange(state) }
        )
    }

    /**
     * Starts the AirPlayReceiver — begins mDNS advertising and opens the RTSP port.
     * Called from onCreate(). The receiver continues running until onDestroy().
     */
    private fun startReceiver() {
        airPlayReceiver.start()
    }

    /**
     * Handles state transitions from the AirPlayReceiver.
     *
     * [AirPlayState.WAITING] → Show the WaitingScreen, hide StreamingScreen.
     * [AirPlayState.STREAMING] → Show the StreamingScreen, hide WaitingScreen.
     *
     * This method is always called on the Main thread (safe to update UI).
     *
     * @param state The new state from AirPlayReceiver.
     */
    private fun handleStateChange(state: AirPlayState) {
        Timber.d("State changed to: $state")
        when (state) {
            AirPlayState.WAITING -> showWaitingScreen()
            AirPlayState.STREAMING -> showStreamingScreen()
        }
    }

    /**
     * Makes the WaitingScreen visible and hides the StreamingScreen.
     * Uses GONE (not INVISIBLE) so the hidden view takes no layout space.
     */
    private fun showWaitingScreen() {
        waitingContainer.visibility = View.VISIBLE
        streamingContainer.visibility = View.GONE
    }

    /**
     * Makes the StreamingScreen visible and hides the WaitingScreen.
     * Uses GONE (not INVISIBLE) so the hidden view takes no layout space.
     */
    private fun showStreamingScreen() {
        streamingContainer.visibility = View.VISIBLE
        waitingContainer.visibility = View.GONE
    }
}
