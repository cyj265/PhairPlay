package com.phairplay.dlna.renderer;

import org.jupnp.support.model.Channel;
import org.jupnp.support.renderingcontrol.AbstractAudioRenderingControl;
import org.jupnp.support.renderingcontrol.RenderingControlException;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.model.types.UnsignedIntegerTwoBytes;

/**
 * RenderingControl service implementation for the DLNA renderer.
 *
 * Volume state is kept in-process so GetVolume/SetVolume round-trip correctly;
 * actual audio-level control of ExoPlayer could be wired later.
 */
public class DlnaAudioRenderingControl extends AbstractAudioRenderingControl {

    private static final UnsignedIntegerFourBytes[] INSTANCES =
            new UnsignedIntegerFourBytes[]{new UnsignedIntegerFourBytes(0)};

    private static volatile long volume = 50;

    @Override
    public boolean getMute(UnsignedIntegerFourBytes instanceId, String channelName)
            throws RenderingControlException {
        return false;
    }

    @Override
    public void setMute(UnsignedIntegerFourBytes instanceId, String channelName, boolean desiredMute)
            throws RenderingControlException {
    }

    @Override
    public UnsignedIntegerTwoBytes getVolume(UnsignedIntegerFourBytes instanceId, String channelName)
            throws RenderingControlException {
        return new UnsignedIntegerTwoBytes(volume);
    }

    @Override
    public void setVolume(UnsignedIntegerFourBytes instanceId, String channelName,
                          UnsignedIntegerTwoBytes desiredVolume) throws RenderingControlException {
        volume = desiredVolume.getValue();
    }

    /** Current volume as a 0–100 integer (used by the player bridge). */
    public static int getVolumeValue() {
        return (int) volume;
    }

    @Override
    protected Channel[] getCurrentChannels() {
        return new Channel[]{Channel.Master};
    }

    @Override
    public UnsignedIntegerFourBytes[] getCurrentInstanceIds() {
        return INSTANCES;
    }
}
