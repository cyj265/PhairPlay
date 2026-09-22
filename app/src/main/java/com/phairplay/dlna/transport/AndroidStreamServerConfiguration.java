package com.phairplay.dlna.transport;

import org.jupnp.transport.spi.StreamServerConfiguration;

/** Empty configuration for {@link AndroidStreamServer}. */
public class AndroidStreamServerConfiguration implements StreamServerConfiguration {

    @Override
    public int getListenPort() {
        return 0; // ephemeral
    }
}
