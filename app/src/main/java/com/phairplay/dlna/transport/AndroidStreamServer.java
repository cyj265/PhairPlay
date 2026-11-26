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

import com.phairplay.util.Logger;

/**
 * A plain-java HTTP server backed by {@link ServerSocket}. It serves the DLNA
 * descriptor / SCPD / SOAP / GENA / DIAL endpoints through
 * {@link AndroidUpnpStream} and keeps the historical port fallback: 8899, then
 * a few neighbours, then an ephemeral port reported through {@link #getPort()}.
 *
 * <p>Only {@code java.net} is used so it works on every supported Android
 * version and carries no CDDL-licensed dependency.</p>
 */
public class AndroidStreamServer implements Runnable {

    private final int listenPort;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    /** Set once at init; [run] must never set it again (see the stop/run race below). */
    private volatile boolean running;
    private int localPort = -1;

    /**
     * @param listenPort the preferred TCP port; pass
     *                   {@link ManualDlnaHttp#CONTROL_PORT} so the renderer
     *                   keeps the address people already know.
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

    public void start(InetAddress bindAddress) throws IOException {
        try {
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
                Logger.INSTANCE.w("Port " + listenPort + " is busy, DLNA control server listening on "
                        + serverSocket.getLocalPort() + " instead");
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
            Logger.INSTANCE.i("AndroidStreamServer bound on " + bindAddress + ":" + localPort);
            running = true;
        } catch (IOException e) {
            throw e;
        }
        Thread acceptThread = new Thread(this, "DLNA-HTTP");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

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
    public void run() {
        // Do NOT set running = true here. stop() flips it to false and closes the socket;
        // a run() thread that starts (or resumes) after stop() would otherwise resurrect the
        // flag, and accept() on the closed socket fails instantly forever. That was a busy
        // loop logging thousands of warnings per second, starving the decoder in the same
        // process during Miracast playback.
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(new AndroidUpnpStream(socket));
            } catch (IOException e) {
                // A closed socket is terminal regardless of the flag — it covers both a
                // normal stop() and the stale-run() race above.
                if (!running || serverSocket == null || serverSocket.isClosed()) {
                    break;
                }
                Logger.INSTANCE.w("AndroidStreamServer accept failed: " + e.getMessage());
                // Back off: a transient failure must never turn into a busy loop either.
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (RuntimeException e) {
                // stop() shuts the executor down; a rejected task is just shutdown noise.
                if (!running || executor.isShutdown()) {
                    break;
                }
                Logger.INSTANCE.w("AndroidStreamServer rejected a connection: " + e.getMessage());
            }
        }
    }
}
