package com.phairplay.dlna.renderer;

import org.jupnp.support.avtransport.impl.state.AbstractState;
import org.jupnp.support.avtransport.impl.state.Playing;
import org.jupnp.support.avtransport.lastchange.AVTransportVariable;
import org.jupnp.support.model.AVTransport;
import org.jupnp.support.model.MediaInfo;
import org.jupnp.support.model.PositionInfo;
import org.jupnp.support.model.SeekMode;

import java.net.URI;

/**
 * Playing state: playback is active.
 *
 * The real playback starts in {@link #onEntry()} using the current URI stored
 * in the AVTransport by the SetAVTransportURI call.
 */
public class DlnaPlaying extends Playing<AVTransport> {

    public DlnaPlaying(AVTransport transport) {
        super(transport);
    }

    @Override
    public void onEntry() {
        super.onEntry();
        DlnaPlayerControl control = DlnaPlayerBridge.get();
        if (control != null) {
            control.startPlayback(getTransport().getMediaInfo().getCurrentURI());
        }
    }

    @Override
    public Class<? extends AbstractState<?>> setTransportURI(URI uri, String metaData) {
        getTransport().setMediaInfo(new MediaInfo(uri.toString(), metaData));
        getTransport().setPositionInfo(new PositionInfo(1, metaData, uri.toString()));
        getTransport().getLastChange().setEventedValue(
                getTransport().getInstanceId(),
                new AVTransportVariable.AVTransportURI(uri),
                new AVTransportVariable.CurrentTrackURI(uri)
        );
        return DlnaStopped.class;
    }

    @Override
    public Class<? extends AbstractState<?>> stop() {
        DlnaPlayerControl control = DlnaPlayerBridge.get();
        if (control != null) {
            control.stopPlayback();
        }
        return DlnaStopped.class;
    }

    @Override
    public Class<? extends AbstractState<?>> play(String speed) {
        DlnaPlayerControl control = DlnaPlayerBridge.get();
        if (control != null) {
            control.resumePlayback();
        }
        return null; // stay in Playing
    }

    @Override
    public Class<? extends AbstractState<?>> pause() {
        DlnaPlayerControl control = DlnaPlayerBridge.get();
        if (control != null) {
            control.pausePlayback();
        }
        return null; // stay in Playing (simplified UPnP bookkeeping)
    }

    @Override
    public Class<? extends AbstractState<?>> next() {
        return null;
    }

    @Override
    public Class<? extends AbstractState<?>> previous() {
        return null;
    }

    @Override
    public Class<? extends AbstractState<?>> seek(SeekMode unit, String target) {
        DlnaPlayerControl control = DlnaPlayerBridge.get();
        if (control == null || target == null) {
            return null;
        }
        try {
            // UPnP seek targets are usually HH:MM:SS.
            String[] parts = target.trim().split(":");
            long seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Long.parseLong(part.trim());
            }
            control.seekTo(seconds);
        } catch (NumberFormatException ignored) {
            // Non-timecode targets (e.g. "TRACK_NR=1") are not supported.
        }
        return null;
    }
}
