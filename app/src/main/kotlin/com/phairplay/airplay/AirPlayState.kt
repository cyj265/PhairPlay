package com.phairplay.airplay

/**
 * AirPlayState — Represents the two possible states of the AirPlay receiver.
 *
 * WHY: By modeling state as a sealed class (or enum), we make it impossible for the
 * UI to encounter an unexpected state. The compiler enforces handling all cases.
 *
 * HOW: [AirPlayReceiver] emits state changes via its onStateChanged callback.
 * [MainActivity] observes these and switches between screens.
 *
 * Example:
 *   when (state) {
 *       AirPlayState.WAITING   -> showWaitingScreen()
 *       AirPlayState.STREAMING -> showStreamingScreen()
 *   }
 */
enum class AirPlayState {
    /**
     * The receiver is idle: mDNS is advertising, RTSP port is open,
     * but no sender is currently connected.
     */
    WAITING,

    /**
     * A macOS sender is connected and actively streaming video/audio.
     */
    STREAMING
}
