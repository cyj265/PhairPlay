package com.phairplay.dlna.renderer;

/**
 * Static holder for the active {@link DlnaPlayerControl}.
 *
 * The Cling state machine instantiates state classes reflectively with only an
 * (AVTransport) constructor, so the player bridge is injected through a static
 * holder instead. Only one DlnaReceiver runs at a time, so a static is safe.
 */
public final class DlnaPlayerBridge {

    private static volatile DlnaPlayerControl control;

    private DlnaPlayerBridge() {
    }

    public static void setControl(DlnaPlayerControl c) {
        control = c;
    }

    /** May be null if the renderer has been stopped. */
    public static DlnaPlayerControl get() {
        return control;
    }
}
