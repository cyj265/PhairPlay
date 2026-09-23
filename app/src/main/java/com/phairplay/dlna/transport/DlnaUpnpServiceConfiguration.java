package com.phairplay.dlna.transport;

import org.jupnp.android.AndroidUpnpServiceConfiguration;
import org.jupnp.transport.impl.jetty.StreamClientConfigurationImpl;
import org.jupnp.transport.spi.DatagramIO;
import org.jupnp.transport.spi.MulticastReceiver;
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

    /** Fixed HTTP port so the renderer is reachable without SSDP discovery
     *  (e.g. http://&lt;box-ip&gt;:8899 from a browser). 8080 is commonly taken
     *  by other apps, so use a less contested port. Port 0 would pick an
     *  ephemeral port that nobody can guess. */
    public static final int STREAM_LISTEN_PORT = 8899;

    public DlnaUpnpServiceConfiguration() {
        super(STREAM_LISTEN_PORT, 0);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public StreamClient createStreamClient() {
        return new AndroidStreamClient(
                new StreamClientConfigurationImpl(getSyncProtocolExecutorService()));
    }

    @Override
    @SuppressWarnings("rawtypes")
    public StreamServer createStreamServer(NetworkAddressFactory networkAddressFactory) {
        return new AndroidStreamServer(STREAM_LISTEN_PORT);
    }

    /**
     * SSDP discovery is handled manually by {@link ManualSsdp} (NOTIFY alive
     * + M-SEARCH responses). jUPnP's multicast/ datagram layers are disabled
     * so that only our listener owns UDP :1900 and the device is discoverable
     * even if the jUPnP registry lookup misbehaves at runtime.
     */
    @Override
    @SuppressWarnings("rawtypes")
    public MulticastReceiver createMulticastReceiver(NetworkAddressFactory networkAddressFactory) {
        return null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public DatagramIO createDatagramIO(NetworkAddressFactory networkAddressFactory) {
        return null;
    }
}
