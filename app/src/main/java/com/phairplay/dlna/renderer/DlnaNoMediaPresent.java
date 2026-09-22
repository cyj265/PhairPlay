package com.phairplay.dlna.renderer;

import org.jupnp.support.avtransport.impl.state.AbstractState;
import org.jupnp.support.avtransport.impl.state.NoMediaPresent;
import org.jupnp.support.avtransport.lastchange.AVTransportVariable;
import org.jupnp.support.model.AVTransport;
import org.jupnp.support.model.MediaInfo;
import org.jupnp.support.model.PositionInfo;

import java.net.URI;

/**
 * Initial state: no media has been set yet.
 *
 * When a control point (phone video app) calls SetAVTransportURI we record the
 * URI in the AVTransport and transition to Stopped, exactly like the Cling
 * reference renderer.
 */
public class DlnaNoMediaPresent extends NoMediaPresent<AVTransport> {

    public DlnaNoMediaPresent(AVTransport transport) {
        super(transport);
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
}
