package com.phairplay.dlna.renderer;

import org.jupnp.support.avtransport.impl.state.AbstractState;
import org.jupnp.support.avtransport.impl.state.Stopped;
import org.jupnp.support.avtransport.lastchange.AVTransportVariable;
import org.jupnp.support.model.AVTransport;
import org.jupnp.support.model.MediaInfo;
import org.jupnp.support.model.PositionInfo;
import org.jupnp.support.model.SeekMode;

import java.net.URI;

/**
 * Stopped state: a URI is set but playback has not started (or was stopped).
 *
 * The actual player work happens in {@link DlnaPlaying#onEntry()}; here we only
 * manage UPnP bookkeeping and transitions.
 */
public class DlnaStopped extends Stopped<AVTransport> {

    public DlnaStopped(AVTransport transport) {
        super(transport);
    }

    public void onEntry() {
        super.onEntry();
    }

    public void onExit() {
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
        return DlnaPlaying.class;
    }

    @Override
    public Class<? extends AbstractState<?>> next() {
        return DlnaStopped.class;
    }

    @Override
    public Class<? extends AbstractState<?>> previous() {
        return DlnaStopped.class;
    }

    @Override
    public Class<? extends AbstractState<?>> seek(SeekMode unit, String target) {
        // Parsing of relative/absolute seeks is done in the playing state.
        return DlnaStopped.class;
    }
}
