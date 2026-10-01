package com.phairplay.dlna.transport;

import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *
 * <p>MULTI-HOME (Phicomm N1 / TV boxes): a box such as an N1 can be wired
 * (eth0) while the phone sits on the Wi-Fi subnet, or sit behind two routers.
 * One multicast socket pinned to a single interface answers M-SEARCH only for
 * senders on that interface's subnet — the box shows a healthy status card yet
 * stays invisible in the phone's cast list. This implementation therefore runs
 * one listening socket per IPv4 interface and answers with the LOCATION that
 * belongs to the subnet the requesting control point actually sits on.
 */
public final class ManualSsdp {

    public static final String GROUP = "239.255.255.250";
    public static final int PORT = 1900;
    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    // Standard UUID format — Windows/VLC drop non-UUID UDNs.
    public static final String UDN_FULL = "uuid:6f61c845-1dd2-11b2-8f7b-001185123456";
    public static final String USN = UDN_FULL + "::" + DEVICE_TYPE;
    public static final String DESC_PATH =
            "/upnp/dev/6f61c845-1dd2-11b2-8f7b-001185123456/desc";

    private static final long ALIVE_INTERVAL_SECONDS = 30;

    /**
     * One advertised LAN face: a local IPv4, the interface it lives on, and the
     * multicast socket listening on that interface.
     */
    private static final class Face {
        final InetAddress ip;
        final NetworkInterface ni;
        final MulticastSocket sock;
        final int prefixLen;

        Face(InetAddress ip, NetworkInterface ni, MulticastSocket sock, int prefixLen) {
            this.ip = ip;
            this.ni = ni;
            this.sock = sock;
            this.prefixLen = prefixLen;
        }

        /** LOCATION advertised on this face — the renderer's HTTP control URL. */
        String location(int httpPort) {
            return "http://" + ip.getHostAddress() + ":" + httpPort + DESC_PATH;
        }

        String label() {
            return (ni == null ? "default" : ni.getName()) + "/" + ip.getHostAddress()
                    + (prefixLen > 0 ? "/" + prefixLen : "");
        }
    }

    private volatile boolean running;
    /** Every LAN face we advertise on; mutated while starting and stopping only. */
    private final List<Face> faces = new CopyOnWriteArrayList<Face>();
    private ScheduledExecutorService notifier;
    private volatile String location = "";
    /** Port the DLNA HTTP control server listens on (must match AndroidStreamServer). */
    private volatile int httpPort = 8899;

    /** Joins the multicast group on every LAN face and starts advertising. */
    public synchronized void start(String ip) {
        if (running) {
            return;
        }
        running = true;
        location = "http://" + ip + ":" + httpPort + DESC_PATH;
        try {
            List<Face> built = new ArrayList<Face>();
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs != null) {
                while (ifs.hasMoreElements()) {
                    NetworkInterface ni = ifs.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    Inet4Address addr = firstIPv4(ni);
                    if (addr == null) {
                        continue;
                    }
                    MulticastSocket sock = new MulticastSocket(null);
                    sock.setReuseAddress(true);
                    try {
                        // A spec-ish SSDP originator binds :1900. When the port is
                        // already taken (e.g. another SSDP client on the box) fall
                        // back to an ephemeral port — a socket that joined the group
                        // still receives every packet addressed to the multicast
                        // group, so discovery keeps working.
                        sock.bind(new InetSocketAddress(PORT));
                    } catch (SocketException busy) {
                        sock = new MulticastSocket(null);
                        sock.setReuseAddress(true);
                        sock.bind(new InetSocketAddress(0));
                        DebugLog.INSTANCE.log("SSDP",
                                ":1900 已被占用，改用临时端口（组播监听不受影响）");
                    }
                    try {
                        sock.setTimeToLive(4);
                    } catch (Exception ignored) {
                    }
                    try {
                        joinGroup(sock, GROUP, ni);
                        sock.setNetworkInterface(ni);
                    } catch (Exception e) {
                        DebugLog.INSTANCE.log("SSDP", "接口 " + ni.getName()
                                + " 加入组播组失败: " + e.getClass().getSimpleName() + "（跳过该面）");
                        try {
                            sock.close();
                        } catch (Exception ignored) {
                        }
                        continue;
                    }
                    Face f = new Face(addr, ni, sock, prefixOf(ni, addr));
                    built.add(f);
                    DebugLog.INSTANCE.log("SSDP", "监听面 " + f.label());
                }
            }
            if (built.isEmpty()) {
                // Last resort: a single default-interface socket (best effort).
                MulticastSocket sock = new MulticastSocket(null);
                sock.setReuseAddress(true);
                try {
                    sock.bind(new InetSocketAddress(PORT));
                } catch (SocketException busy) {
                    sock.bind(new InetSocketAddress(0));
                }
                joinGroup(sock, GROUP, null);
                built.add(new Face(InetAddress.getByName(ip), null, sock, 24));
            }
            faces.clear();
            faces.addAll(built);
        } catch (Exception e) {
            running = false;
            closeAll();
            DebugLog.INSTANCE.setSsdpStatus("启动失败: " + e.getMessage());
            DebugLog.INSTANCE.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
            DebugLog.INSTANCE.log("SSDP", "bind :1900 失败: " + e);
            return;
        }
        StringBuilder ifaceDump = new StringBuilder("LAN面: ");
        for (Face f : faces) {
            ifaceDump.append(f.label()).append(" ");
        }
        DebugLog.INSTANCE.setSsdpStatus("运行中 (端口 " + PORT + ", 面数=" + faces.size() + ")");
        DebugLog.INSTANCE.log("SSDP", ifaceDump.toString());
        DebugLog.INSTANCE.setSsdpLocation(location);
        DebugLog.INSTANCE.log("SSDP", "启动成功, location=" + location);

        for (final Face f : faces) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    listenLoop(f);
                }
            }, "phairplay-ssdp");
            t.setDaemon(true);
            t.start();
        }
        // Announce on every face (each with its own reachable LOCATION) so a
        // sender on any subnet sees a valid control URL, then re-announce.
        broadcastAlive();
        notifier = Executors.newSingleThreadScheduledExecutor();
        notifier.scheduleAtFixedRate(new Runnable() {
            public void run() {
                broadcastAlive();
            }
        }, 1, ALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        running = false;
        // Tell control points we're gone; without byebye they keep the device
        // cached for max-age (30 min) as a ghost entry that fails to cast.
        try {
            broadcastByebye();
        } catch (Exception ignored) {
        }
        if (notifier != null) {
            notifier.shutdownNow();
            notifier = null;
        }
        closeAll();
    }

    private void closeAll() {
        for (Face f : faces) {
            try {
                f.sock.close();
            } catch (Exception ignored) {
            }
        }
        faces.clear();
    }

    /** First usable LAN IPv4 on an interface (link-local and loopback skipped). */
    private static Inet4Address firstIPv4(NetworkInterface ni) {
        try {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address
                        && !ia.getAddress().isLoopbackAddress()
                        && !((Inet4Address) ia.getAddress()).isLinkLocalAddress()) {
                    return (Inet4Address) ia.getAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int prefixOf(NetworkInterface ni, InetAddress addr) {
        try {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress().equals(addr)) {
                    int len = ia.getNetworkPrefixLength();
                    return (len >= 0 && len <= 32) ? len : 24;
                }
            }
        } catch (Exception ignored) {
        }
        return 24;
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
        for (Face f : faces) {
            final String loc = f.location(httpPort);
            // rootdevice announcement: some control points build their device tree
            // exclusively from NT: upnp:rootdevice and ignore service-level NTs.
            send(f, notify(loc, "upnp:rootdevice", UDN_FULL + "::upnp:rootdevice"));
            send(f, notify(loc, DEVICE_TYPE, USN));
            send(f, notify(loc, UDN_FULL, UDN_FULL));
        }
    }

    private void broadcastByebye() {
        for (Face f : faces) {
            final String loc = f.location(httpPort);
            String bye = "NTS: ssdp:byebye";
            send(f, notify(loc, "upnp:rootdevice", UDN_FULL + "::upnp:rootdevice", bye));
            send(f, notify(loc, DEVICE_TYPE, USN, bye));
            send(f, notify(loc, UDN_FULL, UDN_FULL, bye));
        }
    }

    /** Builds an ssdp NOTIFY for the given NT/USN pair. */
    private String notify(String loc, String nt, String usn) {
        return notify(loc, nt, usn, "NTS: ssdp:alive");
    }

    private String notify(String loc, String nt, String usn, String ntsHeader) {
        return "NOTIFY * HTTP/1.1\r\n"
                + "HOST: " + GROUP + ":" + PORT + "\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "DATE: " + httpDate() + "\r\n"
                // DLNA CORE profile: control points identify renderers by this header.
                // Several phone cast stacks (Xiaomi / Huawei / CMCC loaders) drop a
                // device whose SSDP packet lacks it, i.e. the TV is simply not listed.
                + "X-User-Agent: redsonic\r\n"
                + "LOCATION: " + loc + "\r\n"
                + "SERVER: PhairPlay/1.0 UPnP/1.0 UPnP/1.1\r\n"
                + "NT: " + nt + "\r\n"
                + ntsHeader + "\r\n"
                + "USN: " + usn + "\r\n\r\n";
    }

    /** HTTP date header value in the format required by UPnP 1.1 (GMT, RFC 1123). */
    private static String httpDate() {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            return sdf.format(new java.util.Date());
        } catch (Exception e) {
            return "";
        }
    }

    private void listenLoop(final Face face) {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                face.sock.receive(p);
                String data = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (data.startsWith("M-SEARCH")) {
                    respondToSearch(data, p.getAddress(), p.getPort(), face);
                }
            } catch (SocketException e) {
                // Socket closed while stopping — exit quietly.
                if (running) {
                    // The socket can reject receive() repeatedly; spinning here pinned a
                    // CPU core and starved the mDNS/HTTP threads, which made the box
                    // look "found but dead" (or invisible again) on the phone.
                    pauseBeforeRetry();
                }
            } catch (Exception e) {
                // Keep the listener alive no matter what, but never busy-spin.
                DebugLog.INSTANCE.log("SSDP", "监听异常: " + e.getClass().getSimpleName());
                pauseBeforeRetry();
            }
        }
    }

    /** Backs off the receive loop so a failing socket can never burn a CPU core. */
    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void respondToSearch(String data, InetAddress target, int port, Face from) {
        String st = header(data, "ST");
        if (st == null) {
            return;
        }
        String stTrim = st.trim();
        boolean match = "ssdp:all".equals(stTrim)
                || DEVICE_TYPE.equals(stTrim)
                || UDN_FULL.equals(stTrim)
                || USN.equals(stTrim)
                || "upnp:rootdevice".equals(stTrim)
                // Windows Play-To also probes this well-known DLNA DMR UUID;
                // answering it makes the renderer show up as a DMR in
                // Windows' dedicated search pass.
                || "uuid:020000000000-dmr".equals(stTrim);
        DebugLog.INSTANCE.setLastSearchAt(DebugLog.INSTANCE.now());
        DebugLog.INSTANCE.setLastSearchFrom(target.getHostAddress() + ":" + port);
        DebugLog.INSTANCE.setLastSearchSt(stTrim);
        if (!match) {
            DebugLog.INSTANCE.log("SSDP", "收到 M-SEARCH ST=" + stTrim + " 来自 " + target.getHostAddress() + "（不匹配，忽略）");
            return;
        }
        // Answer through the face the requester can actually reach: on a
        // dual-homed box a LOCATION pointing into another subnet is
        // unreachable, and the sender silently drops the device.
        Face best = bestFaceFor(target);
        if (best != null) {
            from = best;
        }
        DebugLog.INSTANCE.setLastResponseAt(DebugLog.INSTANCE.now());
        DebugLog.INSTANCE.log("SSDP", "响应 M-SEARCH ST=" + stTrim + " -> " + target.getHostAddress() + ":" + port
                + " (面=" + from.label() + ", LOCATION=" + from.location(httpPort) + ")");
        // The response ST/USN must echo what the requester asked for
        // (Windows "Play To" searches upnp:rootdevice and validates the
        // response ST against its request).
        if ("ssdp:all".equals(stTrim)) {
            // Per spec, ssdp:all must be answered once per matching resource:
            // rootdevice + UUID + MediaRenderer. Control points that build
            // their tree strictly from rootdevice responses find nothing
            // when only the service type is answered.
            sendSearchResponse(from, "upnp:rootdevice", UDN_FULL + "::upnp:rootdevice", target, port);
            sendSearchResponse(from, UDN_FULL, UDN_FULL, target, port);
            sendSearchResponse(from, DEVICE_TYPE, USN, target, port);
            return;
        }
        String respSt;
        String respUsn;
        if ("upnp:rootdevice".equals(stTrim)) {
            respSt = "upnp:rootdevice";
            respUsn = UDN_FULL + "::upnp:rootdevice";
        } else if (UDN_FULL.equals(stTrim)) {
            respSt = UDN_FULL;
            respUsn = UDN_FULL;
        } else if ("uuid:020000000000-dmr".equals(stTrim)) {
            // Echo the Windows DMR probe type; pair our real UDN with it.
            respSt = "uuid:020000000000-dmr";
            respUsn = UDN_FULL + "::uuid:020000000000-dmr";
        } else if (USN.equals(stTrim)) {
            respSt = DEVICE_TYPE;
            respUsn = USN;
        } else {
            respSt = DEVICE_TYPE;
            respUsn = USN;
        }
        sendSearchResponse(from, respSt, respUsn, target, port);
    }

    /** The face whose subnet contains `target`, or null when nothing matches. */
    private Face bestFaceFor(InetAddress target) {
        Face hit = null;
        for (Face f : faces) {
            if (sameSubnet(f, target)) {
                hit = f;
            }
        }
        return hit;
    }

    private static boolean sameSubnet(Face f, InetAddress other) {
        try {
            if (!(other instanceof Inet4Address) || !(f.ip instanceof Inet4Address)) {
                return false;
            }
            int bits = f.prefixLen;
            if (bits <= 0 || bits > 30) {
                bits = 24;
            }
            if (bits >= 32) {
                return f.ip.equals(other);
            }
            int mask = (0xFFFFFFFF << (32 - bits)) & 0xFFFFFFFF;
            return ((ipv4ToInt((Inet4Address) f.ip) ^ ipv4ToInt((Inet4Address) other)) & mask) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int ipv4ToInt(Inet4Address a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private void sendSearchResponse(Face f, String st, String usn, InetAddress target, int port) {
        String resp = "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "DATE: " + httpDate() + "\r\n"
                + "EXT:\r\n"
                // DLNA CORE profile header — required by several phone cast stacks.
                + "X-User-Agent: redsonic\r\n"
                + "LOCATION: " + f.location(httpPort) + "\r\n"
                + "SERVER: PhairPlay/1.0 UPnP/1.0 UPnP/1.1\r\n"
                + "ST: " + st + "\r\n"
                + "USN: " + usn + "\r\n\r\n";
        send(f, resp, target.getHostAddress(), port);
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

    private void send(Face f, String msg) {
        send(f, msg, GROUP, PORT);
    }

    private void send(Face f, String msg, String host, int port) {
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(
                    b, b.length, InetAddress.getByName(host), port);
            f.sock.send(p);
        } catch (Exception ignored) {
        }
    }
}
