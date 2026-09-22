package com.phairplay.dlna.renderer;

/**
 * Bridge between the UPnP AVTransport state machine and the real player.
 *
 * The Cling state-machine classes are instantiated reflectively with a single
 * (AVTransport) constructor, so they cannot receive the player directly.
 * Instead DlnaReceiver registers itself here at start-up and the state classes
 * call through this bridge. All methods must be safe to call from any thread
 * (implementations dispatch internally).
 */
public interface DlnaPlayerControl {

    /** Start (or restart) playback of the given media URI. */
    void startPlayback(String uri);

    /** Pause the current playback. */
    void pausePlayback();

    /** Resume a paused playback. */
    void resumePlayback();

    /** Stop playback and return the renderer to idle. */
    void stopPlayback();

    /** Seek to a position in seconds. */
    void seekTo(long positionSeconds);
}
