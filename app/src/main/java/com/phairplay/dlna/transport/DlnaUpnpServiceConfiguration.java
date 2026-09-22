package com.phairplay.dlna.transport;

import org.jupnp.android.AndroidUpnpServiceConfiguration;
import org.jupnp.transport.impl.jetty.StreamClientConfigurationImpl;
import org.jupnp.transport.spi.NetworkAddressFactory;
import org.jupnp.transport.spi.StreamClient;
import org.jupnp.transport.spi.StreamServer;

/**
 * jUPnP configuration for the DLNA renderer that does not require the Jetty
 * libraries. jUPnP's default (and Android) configurations build their HTTP
 * transport on Jetty, which is neither dexable for minSdk 25 nor reliably
 * available on modern Android without extra libraries. This configuration
 * plugs in {@link AndroidStreamClient} (java.net HttpURLConnection) and
 * {@link AndroidStreamServer} (plain ServerSocket) instead.
 */
public class DlnaUpnpServiceConfiguration extends AndroidUpnpServiceConfiguration {

    @Override
    @SuppressWarnings("rawtypes")
    public StreamClient createStreamClient() {
        return new AndroidStreamClient(
                new StreamClientConfigurationImpl(getSyncProtocolExecutorService()));
    }

    @Override
    @SuppressWarnings("rawtypes")
    public StreamServer createStreamServer(NetworkAddressFactory networkAddressFactory) {
        return new AndroidStreamServer();
    }
}
