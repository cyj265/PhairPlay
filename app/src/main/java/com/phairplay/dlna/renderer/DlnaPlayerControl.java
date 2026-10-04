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

    /**
     * v100-① — start playback on our own initiative, not on a sender's Play.
     *
     * Called by the three-second auto-start that follows a
     * SetAVTransportURI which was never followed by a Play. The receiver
     * treats this differently on purpose: it does not clear a dismissal, and
     * it declines to run at all when the user has just closed this item or the
     * source is already proven to be non-media. Treating a guess like a real
     * instruction is what made a channel the user had just closed come back
     * on its own one second later (field log 14:49:38-39).
     */
    void startPlaybackAuto(String uri);

    /**
     * v101-⑦ — has the user paused exactly this item?
     *
     * The SOAP layer asks before it touches its own state on
     * SetAVTransportURI. While a cast is paused, a re-send of the same URI is
     * the sender confirming state, not a new item, and acting on it would
     * reset the transport to STOPPED and schedule an auto-start — which is how
     * "the picture came back on its own" used to happen. A Play for the same
     * URI is still honoured; only the Set is ignored.
     */
    boolean isUserPaused(String uri);

    /** Pause the current playback. */
    void pausePlayback();

    /** Resume a paused playback. */
    void resumePlayback();

    /** Stop playback and return the renderer to idle. */
    void stopPlayback();

    /** Seek to a position in seconds. */
    void seekTo(long positionSeconds);

    /** Current playback position in seconds (0 when unknown/idle). */
    long getPositionSeconds();

    /** Total media duration in seconds (0 when unknown). */
    long getDurationSeconds();

    /** Apply volume as a percentage 0-100 to the live player. */
    void setVolumePercent(int percent);
}
