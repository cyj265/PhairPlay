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
     *  by other apps, so use a less contested port. It is a preference, not a
     *  promise: when even this is taken the server binds ephemerally and
     *  {@link #getActualStreamPort()} reports what it got, because a guessed
     *  port only costs a bookmark, while failing to bind costs the whole
     *  renderer — it would not appear in any picker at all. */
    public static final int STREAM_LISTEN_PORT = 8899;

    /** The server this configuration actually handed to the router
     *  (jUPnP's RouterImpl calls {@code createStreamServer} exactly once). */
    private volatile AndroidStreamServer streamServer;

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
        AndroidStreamServer server = new AndroidStreamServer(STREAM_LISTEN_PORT);
        streamServer = server;
        return server;
    }

    /**
     * The TCP port the DLNA control server is really listening on. Same as
     * {@link #STREAM_LISTEN_PORT} unless that port was busy. Callers that need
     * to build a LOCATION (SSDP) must use this, not the constant.
     */
    public int getActualStreamPort() {
        AndroidStreamServer server = streamServer;
        int port = server == null ? -1 : server.getPort();
        return port > 0 ? port : STREAM_LISTEN_PORT;
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
