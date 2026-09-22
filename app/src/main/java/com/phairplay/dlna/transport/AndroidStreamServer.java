package com.phairplay.dlna.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private ServerSocket serverSocket;
    private Router router;
    private ExecutorService executor;
    private volatile boolean running;
    private int localPort = -1;

    @Override
    public void init(InetAddress bindAddress, Router router) throws InitializationException {
        try {
            this.router = router;
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // Port 0 = ephemeral; the jUPnP Router reads getPort() right after init.
            serverSocket.bind(new InetSocketAddress(bindAddress, 0));
            localPort = serverSocket.getLocalPort();
            executor = Executors.newCachedThreadPool();
            logger.debug("AndroidStreamServer bound on {}:{}", bindAddress, localPort);
        } catch (IOException e) {
            throw new InitializationException("Could not initialize AndroidStreamServer: " + e, e);
        }
    }

    @Override
    public int getPort() {
        return localPort;
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
