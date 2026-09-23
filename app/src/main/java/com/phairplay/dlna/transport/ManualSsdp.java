package com.phairplay.dlna.transport;

import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.phairplay.util.DebugLog;

/**
 * Hand-rolled SSDP layer for the DLNA renderer: broadcasts NOTIFY ssdp:alive
 * announcements and answers M-SEARCH discovery requests, completely bypassing
 * jUPnP's multicast/datagram stack (which is disabled in
 * {@link DlnaUpnpServiceConfiguration}).
 *
 * <p>WHY: VLC / Windows Play-To / phone cast apps discover renderers via SSDP
 * multicast. The jUPnP registry lookup that broke for HTTP (404 on desc) could
 * equally break its SSDP responses; a manual layer makes discovery independent
 * of jUPnP's runtime state.
 */
public final class ManualSsdp {

    public static final String GROUP = "239.255.255.250";
    public static final int PORT = 1900;
    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String UDN_FULL = "uuid:uuid-phairplay-dlna-renderer";
    public static final String USN = UDN_FULL + "::" + DEVICE_TYPE;
    public static final String DESC_PATH =
            "/upnp/dev/uuid-phairplay-dlna-renderer/desc";

    private static final long ALIVE_INTERVAL_SECONDS = 30;

    private volatile boolean running;
    private MulticastSocket socket;
    private ScheduledExecutorService notifier;
    private Thread listener;
    private volatile String location = "";

    /** Binds UDP :1900, joins the multicast group and starts advertising. */
    public synchronized void start(String ip) {
        if (running) {
            return;
        }
        running = true;
        location = "http://" + ip + ":8899" + DESC_PATH;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            NetworkInterface ni = wifiInterface();
            if (ni != null) {
                // Specify the interface explicitly: with multiple network
                // interfaces (WiFi + cellular + hotspot + virtual) the
                // default joinGroup() binds to the system's default route,
                // which may not be the WiFi interface, so M-SEARCH never
                // reaches this socket. joinGroup() signatures changed across
                // API levels (the old InetAddress overload was removed in
                // API 35), so call it reflectively for compatibility.
                joinGroup(socket, GROUP, ni);
                DebugLog.INSTANCE.log("SSDP", "加入组播组 via 接口 " + ni.getName());
            } else {
                joinGroup(socket, GROUP, null);
                DebugLog.INSTANCE.log("SSDP", "加入组播组 (默认接口)");
            }
        } catch (Exception e) {
            running = false;
            socket = null;
            DebugLog.INSTANCE.setSsdpStatus("启动失败: " + e.getMessage());
            DebugLog.INSTANCE.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
            DebugLog.INSTANCE.log("SSDP", "bind :1900 失败: " + e);
            return;
        }
        DebugLog.INSTANCE.setSsdpStatus("运行中 (端口 " + PORT + ")");
        DebugLog.INSTANCE.setSsdpLocation(location);
        DebugLog.INSTANCE.log("SSDP", "启动成功, location=" + location);
        listener = new Thread(this::listenLoop, "phairplay-ssdp");
        listener.setDaemon(true);
        listener.start();
        // Announce immediately (and once more after 1s for devices that missed
        // the first packet), then periodically re-announce.
        broadcastAlive();
        notifier = Executors.newSingleThreadScheduledExecutor();
        notifier.scheduleAtFixedRate(
                this::broadcastAlive, 1, ALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        running = false;
        if (notifier != null) {
            notifier.shutdownNow();
            notifier = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }
    }

    /**
     * Best-effort pick of the WiFi interface (name contains "wlan"/"wifi").
     * Falls back to the first up, non-loopback interface.
     */
    private static NetworkInterface wifiInterface() {
        try {
            java.util.Enumeration<NetworkInterface> ifs =
                    NetworkInterface.getNetworkInterfaces();
            if (ifs == null) {
                return null;
            }
            NetworkInterface fallback = null;
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("wifi")) {
                    return ni;
                }
                if (fallback == null) {
                    fallback = ni;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Joins the multicast group, working across API levels: the old
     * joinGroup(InetAddress[, NetworkInterface]) overloads were removed in
     * API 35, while the SocketAddress overload only exists from API 31.
     */
    private static void joinGroup(MulticastSocket socket, String group, NetworkInterface ni)
            throws Exception {
        InetAddress groupAddr = InetAddress.getByName(group);
        if (ni == null) {
            try {
                java.lang.reflect.Method m = MulticastSocket.class.getMethod(
                        "joinGroup", InetAddress.class);
                m.invoke(socket, groupAddr);
                return;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                java.lang.reflect.Method m = MulticastSocket.class.getMethod(
                        "joinGroup", SocketAddress.class, NetworkInterface.class);
                m.invoke(socket, new InetSocketAddress(groupAddr, 0), null);
                return;
            } catch (NoSuchMethodException ignored) {
            }
            throw new RuntimeException("No usable MulticastSocket.joinGroup on this device");
        }
        try {
            java.lang.reflect.Method m = MulticastSocket.class.getMethod(
                    "joinGroup", InetAddress.class, NetworkInterface.class);
            m.invoke(socket, groupAddr, ni);
            return;
        } catch (NoSuchMethodException ignored) {
        }
        try {
            java.lang.reflect.Method m = MulticastSocket.class.getMethod(
                    "joinGroup", SocketAddress.class, NetworkInterface.class);
            m.invoke(socket, new InetSocketAddress(groupAddr, 0), ni);
            return;
        } catch (NoSuchMethodException ignored) {
        }
        throw new RuntimeException("No usable MulticastSocket.joinGroup on this device");
    }

    private void broadcastAlive() {
        DebugLog.INSTANCE.setLastNotifyAt(DebugLog.INSTANCE.now());
        DebugLog.INSTANCE.log("SSDP", "广播 NOTIFY alive -> 239.255.255.250:1900");
        String deviceNotify = "NOTIFY * HTTP/1.1\r\n"
                + "HOST: " + GROUP + ":" + PORT + "\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: PhairPlay/1.0 UPnP/1.0\r\n"
                + "NT: " + DEVICE_TYPE + "\r\n"
                + "NTS: ssdp:alive\r\n"
                + "USN: " + USN + "\r\n\r\n";
        send(deviceNotify, GROUP, PORT);

        String uuidNotify = "NOTIFY * HTTP/1.1\r\n"
                + "HOST: " + GROUP + ":" + PORT + "\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: PhairPlay/1.0 UPnP/1.0\r\n"
                + "NT: " + UDN_FULL + "\r\n"
                + "NTS: ssdp:alive\r\n"
                + "USN: " + UDN_FULL + "\r\n\r\n";
        send(uuidNotify, GROUP, PORT);
    }

    private void listenLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String data = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (data.startsWith("M-SEARCH")) {
                    respondToSearch(data, p.getAddress(), p.getPort());
                }
            } catch (SocketException e) {
                // Socket closed while stopping — exit quietly.
                if (running) {
                    Thread.yield();
                }
            } catch (Exception e) {
                // Keep the listener alive no matter what.
            }
        }
    }

    private void respondToSearch(String data, InetAddress target, int port) {
        String st = header(data, "ST");
        if (st == null) {
            return;
        }
        String stTrim = st.trim();
        boolean match = "ssdp:all".equals(stTrim)
                || DEVICE_TYPE.equals(stTrim)
                || UDN_FULL.equals(stTrim)
                || USN.equals(stTrim);
        DebugLog.INSTANCE.setLastSearchAt(DebugLog.INSTANCE.now());
        DebugLog.INSTANCE.setLastSearchFrom(target.getHostAddress() + ":" + port);
        DebugLog.INSTANCE.setLastSearchSt(stTrim);
        if (!match) {
            DebugLog.INSTANCE.log("SSDP", "收到 M-SEARCH ST=" + stTrim + " 来自 " + target.getHostAddress() + "（不匹配，忽略）");
            return;
        }
        DebugLog.INSTANCE.setLastResponseAt(DebugLog.INSTANCE.now());
        DebugLog.INSTANCE.log("SSDP", "响应 M-SEARCH ST=" + stTrim + " -> " + target.getHostAddress() + ":" + port);
        String resp = "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "EXT:\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: PhairPlay/1.0 UPnP/1.0\r\n"
                + "ST: " + DEVICE_TYPE + "\r\n"
                + "USN: " + USN + "\r\n\r\n";
        send(resp, target.getHostAddress(), port);
    }

    private static String header(String data, String name) {
        String[] lines = data.split("\r\n");
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase(name)) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private void send(String msg, String host, int port) {
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(b, b.length, InetAddress.getByName(host), port);
            socket.send(p);
        } catch (Exception ignored) {
        }
    }
}
