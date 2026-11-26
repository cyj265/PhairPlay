package com.phairplay.dlna.renderer;

/**
 * RenderingControl volume state for the DLNA renderer.
 *
 * The SOAP layer in {@code ManualDlnaHttp} owns GetVolume/SetVolume and the
 * player bridge applies the value to ExoPlayer, so all this class needs is a
 * single in-process value shared by those two call sites. It used to extend
 * jUPnP's AbstractAudioRenderingControl solely to feed the registry-based
 * service that has since been removed.
 */
public final class DlnaAudioRenderingControl {

    private static volatile long volume = 50;

    private DlnaAudioRenderingControl() {
    }

    /** Current volume as a 0–100 integer (used by the player bridge). */
    public static int getVolumeValue() {
        return (int) volume;
    }

    /** Update volume from the manual SOAP layer so both states stay in sync. */
    public static void setVolumeValue(int v) {
        volume = v;
    }
}
