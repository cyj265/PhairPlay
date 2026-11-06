package com.phairplay.dlna.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jupnp.transport.Router;
import org.jupnp.transport.spi.InitializationException;
import org.jupnp.transport.spi.StreamServer;
import org.jupnp.transport.spi.StreamServerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plain-java {@link StreamServer} backed by a {@link ServerSocket},
 * replacing the Jetty/servlet transport used by jUPnP's default configuration.
 *
 * <p>Jetty 9.4 cannot be dexed for minSdk 25 (Fire TV / N1) and its
 * android compatibility surface is uncertain on modern phones; this
 * implementation only relies on {@code java.net}, which works on every
 * Android version from 7.1 (API 25) up.</p>
 */
public class AndroidStreamServer implements StreamServer<StreamServerConfiguration> {

    private static final Logger logger = LoggerFactory.getLogger(AndroidStreamServer.class.getName());

    private final StreamServerConfiguration configuration = new AndroidStreamServerConfiguration();

    private final int listenPort;
    private ServerSocket serverSocket;
    private Router router;
    private ExecutorService executor;
    private volatile boolean running;
    private int localPort = -1;

    /**
     * @param listenPort the preferred TCP port; pass the value from
     *                   {@link DlnaUpnpServiceConfiguration#STREAM_LISTEN_PORT}
     *                   so the renderer keeps the address people already know.
     *                   Taken? Scan a few neighbours, then bind ephemerally and
     *                   report the result through {@link #getPort()}.
     */
    public AndroidStreamServer(int listenPort) {
        this.listenPort = listenPort;
    }

    /**
     * How many ports above {@link #listenPort} are tried before falling back to
     * an arbitrary one.
     *
     * The ephemeral fallback already kept the renderer discoverable, but the
     * number changed on every restart (field finding: com.shafa.marke on this
     * box owns :8899, so the DLNA server never got its usual port and the
     * bookmark http://box:8899/debug never worked). 8899 is also the port the
     * debug page is documented under; staying one step above it keeps both the
     * LOCATION and the debug URL stable across restarts.
     */
    private static final int PORT_SCAN_SPAN = 8;

    @Override
    public void init(InetAddress bindAddress, Router router) throws InitializationException {
        try {
            this.router = router;
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            try {
                serverSocket.bind(new InetSocketAddress(bindAddress, listenPort));
            } catch (IOException taken) {
                // Something else already owns the port — on these boxes it is
                // usually another casting app. Refusing to start would take the
                // whole renderer down (the device would not appear in any
                // picker), whereas another port only costs a bookmark: SSDP
                // carries the LOCATION, and ManualSsdp reads it back from
                // getPort().
                closeQuietly(serverSocket);
                serverSocket = bindFirstFree(bindAddress);
                logger.warn("Port {} is busy, DLNA control server listening on {} instead",
                        listenPort, serverSocket.getLocalPort());
            }
            localPort = serverSocket.getLocalPort();
            // Bounded on purpose. A worker lives as long as the client takes to
            // send its request (30 s socket timeout), so a cached pool lets any
            // LAN device trade one TCP connection for one thread until the
            // process runs out of them. When the queue fills up, the accept
            // thread runs the request itself and stops accepting for a moment —
            // that is the intended backpressure, and a real control point never
            // gets near it.
            executor = new ThreadPoolExecutor(2, 16, 30L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(64),
                    new ThreadPoolExecutor.CallerRunsPolicy());
            logger.debug("AndroidStreamServer bound on {}:{}", bindAddress, localPort);
        } catch (IOException e) {
            throw new InitializationException("Could not initialize AndroidStreamServer: " + e, e);
        }
    }

    @Override
    public int getPort() {
        return localPort;
    }

    /** First free port in {@code listenPort+1 … listenPort+PORT_SCAN_SPAN}, else ephemeral. */
    private ServerSocket bindFirstFree(InetAddress bindAddress) throws IOException {
        for (int port = listenPort + 1; port <= listenPort + PORT_SCAN_SPAN; port++) {
            ServerSocket candidate = new ServerSocket();
            candidate.setReuseAddress(true);
            try {
                candidate.bind(new InetSocketAddress(bindAddress, port));
                return candidate;
            } catch (IOException alsoTaken) {
                closeQuietly(candidate);
            }
        }
        ServerSocket fallback = new ServerSocket();
        fallback.setReuseAddress(true);
        fallback.bind(new InetSocketAddress(bindAddress, 0));
        return fallback;
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public StreamServerConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void run() {
        running = true;
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(new AndroidUpnpStream(router.getProtocolFactory(), socket));
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                logger.warn("AndroidStreamServer accept failed: {}", e.getMessage());
            }
        }
    }
}
