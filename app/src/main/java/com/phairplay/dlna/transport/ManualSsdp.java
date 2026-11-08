package com.phairplay.dlna.transport;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import java.io.FileDescriptor;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
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
 *
 * <p>UNICAST PROBES (Douyin and other LeLink-style cast panels): after the
 * first multicast discovery, a control point usually stops broadcasting and
 * re-probes each cached device with a <em>unicast</em> M-SEARCH sent straight
 * to {@code <box-ip>:1900}. Some ROMs ship their own DMR ("光元素投屏" on the
 * Amlogic N1) which, like us, binds the wildcard 0.0.0.0:1900; the kernel hands
 * a unicast datagram to exactly one of those sockets, and it is not ours. The
 * device then silently ages out of the sender's list while the multicast state
 * still looks healthy. A socket bound to the face's own address wins unicast
 * delivery by longest-prefix match, so each face carries one: it joins no
 * group (multicast stays on the wildcard socket) and only answers probes.
 */
public final class ManualSsdp {

    public static final String GROUP = "239.255.255.250";
    public static final int PORT = 1900;
    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";

    /**
     * The search target a Samsung TV and the Samsung Smart View app use to find
     * a receiver (ported from upstream). They do not search for a UPnP
     * MediaRenderer; the DIAL documents behind the advertised LOCATION
     * ({@link ManualDlnaHttp#DIAL_BASE}) are what completes the discovery.
     */
    public static final String SAMSUNG_MULTI_SCREEN =
            "urn:samsung.com:service:MultiScreenService:1";
    /** Service-level STs. Video/screencast apps typically probe AVTransport directly. */
    static final String[] SERVICE_TYPES = {
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1",
    };
    // Standard UUID format — Windows/VLC drop non-UUID UDNs.
    //
    // v122: this used to be three constants that copy-pasted the same UDN a
    // second and third time (USN concatenated from it, DESC_PATH spelled the
    // whole descriptor path out by hand). Now the identity is read from
    // ManualDlnaHttp in start(), so SSDP can never advertise a LOCATION the
    // HTTP layer 404s on — which is what a half-finished edit here used to
    // produce: the box shows up in the picker and every cast to it fails.
    // The per-instance defaults keep a ManualSsdp that was never started from
    // announcing anything odd.
    private String udnFull = "uuid:" + ManualDlnaHttp.DEFAULT_UDN;
    private String deviceUsn = udnFull + "::" + DEVICE_TYPE;
    private String descriptorPath = ManualDlnaHttp.DEFAULT_BASE + "/desc";

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
        /**
         * AF_INET socket bound to this face's address on :1900, or null when it
         * could not be opened. Receives the unicast M-SEARCH probes that a
         * wildcard socket cannot win while a ROM-installed sink also holds
         * :1900 — see the class comment.
         */
        final FileDescriptor unicastFd;
        /**
         * Port this face actually bound. Anything other than {@link #PORT} means
         * the face is <em>deaf</em>: M-SEARCH probes are addressed to :1900, so
         * a socket parked on an ephemeral port never sees one. Reported on the
         * status card rather than papered over.
         */
        final int boundPort;
        /** M-SEARCH probes this face has received — see {@link #faceDistribution()}. */
        final java.util.concurrent.atomic.AtomicInteger searches =
                new java.util.concurrent.atomic.AtomicInteger();

        Face(InetAddress ip, NetworkInterface ni, MulticastSocket sock, int prefixLen,
             int boundPort, FileDescriptor unicastFd) {
            this.ip = ip;
            this.ni = ni;
            this.sock = sock;
            this.prefixLen = prefixLen;
            this.boundPort = boundPort;
            this.unicastFd = unicastFd;
        }

        String label() {
            return (ni == null ? "default" : ni.getName()) + "/" + ip.getHostAddress()
                    + (prefixLen > 0 ? "/" + prefixLen : "")
                    + ":" + boundPort;
        }
    }

    private volatile boolean running;
    /** Every LAN face we advertise on; mutated while starting and stopping only. */
    private final List<Face> faces = new CopyOnWriteArrayList<Face>();
    /** M-SEARCH probes seen across all faces — surfaced as DebugLog.searchTotal. */
    private final java.util.concurrent.atomic.AtomicInteger totalSearches =
            new java.util.concurrent.atomic.AtomicInteger();
    private ScheduledExecutorService notifier;
    private volatile String location = "";
    /** Port the DLNA HTTP control server listens on — what it really bound,
     *  passed in by {@link #start(String, int)} and corrected by
     *  {@link #setHttpPort(int)}; the constant in
     *  {@link DlnaUpnpServiceConfiguration} is only what it asked for. */
    private volatile int httpPort = DlnaUpnpServiceConfiguration.STREAM_LISTEN_PORT;

    /**
     * The LOCATION announced for one face: that face's address plus this
     * session's descriptor path. Built here rather than on {@link Face} because
     * the path belongs to the identity, which lives on this instance.
     *
     * <p>The address is read from the interface on every call instead of the
     * copy taken at start(). A box that briefly brought up Wi-Fi (wlan0 held
     * 192.168.10.13 for a few minutes) kept advertising that address after the
     * interface lost it — Douyin then fetched the descriptor from a dead IP,
     * failed, and silently dropped the device from its list.
     */
    private String locationOf(Face f) {
        InetAddress ip = f.ip;
        if (f.ni != null) {
            Inet4Address live = firstIPv4(f.ni);
            if (live != null) {
                ip = live;
            }
        }
        return "http://" + ip.getHostAddress() + ":" + httpPort + descriptorPath;
    }

    /**
     * Correct the announced port once the control server has actually bound, and
     * re-announce at once. Discovery starts before the server does — on purpose,
     * it must not depend on jUPnP coming up — so a control point that already
     * cached a LOCATION on the preferred port would keep 404ing until the next
     * 30s alive cycle.
     */
    public synchronized void setHttpPort(int port) {
        if (port <= 0 || port == httpPort || !running || faces.isEmpty()) {
            return;
        }
        httpPort = port;
        location = locationOf(faces.get(0));
        DebugLog.INSTANCE.setSsdpLocation(location);
        DebugLog.INSTANCE.log("SSDP", "控制端口改为 " + port + "，重新宣告 LOCATION=" + location);
        broadcastAlive();
    }

    /**
     * Joins the multicast group on every LAN face and starts advertising.
     *
     * @param httpPort the port the DLNA control server actually bound (it asks
     *                 for {@code STREAM_LISTEN_PORT} but may have been pushed to
     *                 an ephemeral one). Non-positive keeps the current value.
     */
    public synchronized void start(String ip, int httpPort) {
        if (running) {
            return;
        }
        running = true;
        if (httpPort > 0) {
            this.httpPort = httpPort;
        }
        // v122: take the identity from the HTTP layer before announcing anything,
        // so a NOTIFY can never advertise a LOCATION the control server does not
        // serve (the two used to be independent copies of the same UDN).
        udnFull = "uuid:" + ManualDlnaHttp.getUdn();
        deviceUsn = udnFull + "::" + DEVICE_TYPE;
        descriptorPath = ManualDlnaHttp.getBase() + "/desc";
        location = "http://" + ip + ":" + httpPort + descriptorPath;
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
                        // An SSDP listener normally owns :1900. If another process
                        // already holds the port (some TV-box ROMs ship their own
                        // cast/mirror service) fall back to an ephemeral port.
                        //
                        // NOTE: such a face is then DEAF, not merely "unaffected".
                        // M-SEARCH is addressed to :1900, so a socket parked on an
                        // arbitrary port never sees a probe and the box stays
                        // invisible in the sender's list. The old comment claimed
                        // the opposite, which is why this case used to look
                        // healthy; it is now surfaced on the status card.
                        sock.bind(new InetSocketAddress(PORT));
                    } catch (SocketException busy) {
                        sock = new MulticastSocket(null);
                        sock.setReuseAddress(true);
                        sock.bind(new InetSocketAddress(0));
                        DebugLog.INSTANCE.log("SSDP", "接口 " + ni.getName()
                                + " 的 :1900 已被占用，降级到临时端口 "
                                + sock.getLocalPort() + " → 该面收不到 M-SEARCH");
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
                    Face f = new Face(addr, ni, sock, prefixOf(ni, addr), sock.getLocalPort(),
                            openUnicastSocket(addr, ni.getName()));
                    built.add(f);
                    DebugLog.INSTANCE.log("SSDP", "监听面 " + f.label()
                            + (f.unicastFd != null ? " + 单播探活" : ""));
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
                built.add(new Face(InetAddress.getByName(ip), null, sock, 24, sock.getLocalPort(), null));
            }
            faces.clear();
            faces.addAll(oneFacePerSubnet(built));
            // Fresh start => fresh probe counters, so the status card always
            // describes the current run rather than accumulating across restarts.
            totalSearches.set(0);
            DebugLog.INSTANCE.setSearchTotal(0);
            DebugLog.INSTANCE.setSearchByFace("—");
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
        int deafFaces = 0;
        for (Face f : faces) {
            if (f.boundPort != PORT) {
                deafFaces++;
            }
        }
        if (deafFaces > 0) {
            // Never claim a healthy :1900 while some face is parked elsewhere:
            // that face cannot see M-SEARCH at all, which is exactly the
            // "service running but the phone finds nothing" symptom.
            DebugLog.INSTANCE.setSsdpStatus("异常: " + deafFaces + "/" + faces.size()
                    + " 个面未绑 1900 → 收不到手机探测");
        } else {
            DebugLog.INSTANCE.setSsdpStatus("运行中 (端口 " + PORT + ", 面数=" + faces.size() + ")");
        }
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
            if (f.unicastFd != null) {
                Thread u = new Thread(new Runnable() {
                    public void run() {
                        unicastLoop(f);
                    }
                }, "phairplay-ssdp-unicast");
                u.setDaemon(true);
                u.start();
            }
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
            if (f.unicastFd != null) {
                try {
                    Os.close(f.unicastFd);
                } catch (Exception ignored) {
                }
            }
        }
        faces.clear();
    }

    private static void closeFace(Face f) {
        try {
            f.sock.close();
        } catch (Exception ignored) {
        }
        if (f.unicastFd != null) {
            try {
                Os.close(f.unicastFd);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Reduces the announced faces to one per subnet, preferring the wired one.
     * These boxes are routinely dual-homed on the same /24 (eth0 plus a wlan0
     * whose SDIO link keeps timing out); both faces would otherwise answer the
     * same probe with different LOCATIONs, and a control point that cached the
     * Wi-Fi address is left pointing at an interface that silently stops
     * answering. One face per subnet makes the announced LOCATION stable.
     */
    private static List<Face> oneFacePerSubnet(List<Face> built) {
        List<Face> kept = new ArrayList<Face>();
        for (Face f : built) {
            Face same = null;
            for (Face k : kept) {
                if (sameSubnet(k, f.ip)) {
                    same = k;
                    break;
                }
            }
            if (same == null) {
                kept.add(f);
                continue;
            }
            if (isWireless(same) && !isWireless(f)) {
                kept.remove(same);
                kept.add(f);
                closeFace(same);
                DebugLog.INSTANCE.log("SSDP", "同一网段只保留有线面，丢弃 "
                        + same.label() + "（保留 " + f.label() + "）");
            } else {
                closeFace(f);
                DebugLog.INSTANCE.log("SSDP", "同一网段已有面，丢弃 " + f.label()
                        + "（保留 " + same.label() + "）");
            }
        }
        return kept;
    }

    private static boolean isWireless(Face f) {
        if (f.ni == null) {
            return false;
        }
        String n = f.ni.getName();
        if (n == null) {
            return false;
        }
        n = n.toLowerCase(java.util.Locale.US);
        return n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("p2p")
                || n.startsWith("wifi");
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
            final String loc = locationOf(f);
            // rootdevice announcement: some control points build their device tree
            // exclusively from NT: upnp:rootdevice and ignore service-level NTs.
            send(f, notify(loc, "upnp:rootdevice", udnFull + "::upnp:rootdevice"));
            send(f, notify(loc, DEVICE_TYPE, deviceUsn));
            send(f, notify(loc, udnFull, udnFull));
            // A Samsung TV also listens for the passive announcement of a
            // receiver, not only for the answer to its M-SEARCH.
            send(f, notify(loc, SAMSUNG_MULTI_SCREEN, udnFull + "::" + SAMSUNG_MULTI_SCREEN));
        }
    }

    private void broadcastByebye() {
        for (Face f : faces) {
            final String loc = locationOf(f);
            String bye = "NTS: ssdp:byebye";
            send(f, notify(loc, "upnp:rootdevice", udnFull + "::upnp:rootdevice", bye));
            send(f, notify(loc, DEVICE_TYPE, deviceUsn, bye));
            send(f, notify(loc, udnFull, udnFull, bye));
            send(f, notify(loc, SAMSUNG_MULTI_SCREEN, udnFull + "::" + SAMSUNG_MULTI_SCREEN, bye));
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
                + "SERVER: PhairPlay/1.0 UPnP/1.1\r\n"
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
                    face.searches.incrementAndGet();
                    DebugLog.INSTANCE.setSearchTotal(totalSearches.incrementAndGet());
                    DebugLog.INSTANCE.setSearchByFace(faceDistribution());
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

    /**
     * Probe count per face, e.g. "wlan0=12 eth0=0".
     *
     * The split is the point: on a dual-homed box one NIC showing 0 while the
     * phone keeps searching means that face is deaf, or the phone's traffic
     * never reaches that interface. Neither is visible from a plain "running"
     * status, and together with {@link com.phairplay.util.DebugLog#searchTotal}
     * it separates "probes never reach the box" from "we answered but the
     * sender ignored us".
     */
    private String faceDistribution() {
        StringBuilder sb = new StringBuilder();
        for (Face f : faces) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(f.ni == null ? "default" : f.ni.getName())
                    .append('=').append(f.searches.get());
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    /**
     * Opens a plain AF_INET socket bound to one face's address on :1900.
     *
     * <p>This must be a real AF_INET socket, not a {@link java.net.DatagramSocket}:
     * Android's libcore creates those as dual-stack AF_INET6 sockets, and the
     * kernel's IPv4 unicast lookup prefers the ROM sink's AF_INET wildcard over
     * them — which is the whole reason this socket exists.
     *
     * <p>SO_REUSEADDR lets it coexist with the wildcard :1900 holders; binding a
     * specific address is what makes unicast probes land here.
     */
    private static FileDescriptor openUnicastSocket(InetAddress addr, String ifName) {
        FileDescriptor fd = null;
        try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP);
            // Both sockopt wrappers are newer than this flavor's minSdk 25:
            // setsockoptInt landed in 26, setsockoptTimeval / fromMillis in 29.
            // The socket is still usable without them (no address reuse, and
            // recv is unbounded), which is why this path is best-effort.
            if (Build.VERSION.SDK_INT >= 26) {
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                // Bounded receive so stop() does not need to interrupt a blocked
                // syscall on an fd that close() alone may not wake.
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                        StructTimeval.fromMillis(1000));
            }
            Os.bind(fd, addr, PORT);
            return fd;
        } catch (Exception | LinkageError e) {
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (Exception ignored) {
                }
            }
            DebugLog.INSTANCE.log("SSDP", "接口 " + ifName + " 单播 :1900 未绑定: "
                    + e.getClass().getSimpleName() + "（多播发现不受影响）");
            return null;
        }
    }

    private void unicastLoop(final Face face) {
        final FileDescriptor fd = face.unicastFd;
        if (fd == null) {
            return;
        }
        ByteBuffer bb = ByteBuffer.allocate(4096);
        byte[] buf = new byte[4096];
        while (running) {
            try {
                bb.clear();
                InetSocketAddress from = new InetSocketAddress(0);
                int n = Os.recvfrom(fd, bb, 0, from);
                if (n <= 0) {
                    continue;
                }
                bb.clear();
                bb.get(buf, 0, n);
                String data = new String(buf, 0, n, StandardCharsets.UTF_8);
                InetAddress src = from.getAddress();
                if (!data.startsWith("M-SEARCH") || src == null) {
                    continue;
                }
                face.searches.incrementAndGet();
                DebugLog.INSTANCE.setSearchTotal(totalSearches.incrementAndGet());
                DebugLog.INSTANCE.setSearchByFace(faceDistribution());
                respondToSearch(data, src, from.getPort(), face, fd);
            } catch (android.system.ErrnoException e) {
                // SO_RCVTIMEO fires as EAGAIN; it only exists to re-check `running`.
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                    continue;
                }
                if (!running) {
                    break;
                }
                DebugLog.INSTANCE.log("SSDP", "单播监听异常: " + e.getClass().getSimpleName());
                pauseBeforeRetry();
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                DebugLog.INSTANCE.log("SSDP", "单播监听异常: " + e.getClass().getSimpleName());
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
        respondToSearch(data, target, port, from, null);
    }

    /**
     * @param unicastFd the socket the probe arrived on when it was a unicast
     *                  M-SEARCH, so the answer goes back from the address the
     *                  sender actually probed (and :1900), not from the
     *                  multicast socket; null for multicast probes.
     */
    private void respondToSearch(String data, InetAddress target, int port, Face from,
                                 FileDescriptor unicastFd) {
        String st = header(data, "ST");
        if (st == null) {
            return;
        }
        String stTrim = st.trim();
        boolean match = "ssdp:all".equals(stTrim)
                || DEVICE_TYPE.equals(stTrim)
                || udnFull.equals(stTrim)
                || deviceUsn.equals(stTrim)
                || "upnp:rootdevice".equals(stTrim)
                // Service-level probes. Most phone casting libraries search the
                // AVTransport ST directly because that is the service they will
                // drive; without this the probe fell into the "not matched"
                // branch and the sender's list stayed empty forever.
                || serviceTypeOf(stTrim) != null
                // Samsung Smart View probes this MultiScreen ST instead of a
                // UPnP device type; without it the Samsung app list is empty.
                || SAMSUNG_MULTI_SCREEN.equals(stTrim)
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
        // Answer through the face the probe actually arrived on: that is the
        // network the sender can reach. Re-picking "the last face in the same
        // subnet" used to override it, and when a Wi-Fi face outlived its
        // address the response carried a dead LOCATION (see locationOf) that
        // the sender could not fetch — it then dropped the device.
        // Only the fallback face (no interface) needs a subnet-based guess.
        if (from.ni == null) {
            Face best = bestFaceFor(target);
            if (best != null) {
                from = best;
            }
        }
        DebugLog.INSTANCE.setLastResponseAt(DebugLog.INSTANCE.now());
        DebugLog.INSTANCE.log("SSDP", "响应 M-SEARCH ST=" + stTrim + " -> " + target.getHostAddress() + ":" + port
                + " (面=" + from.label() + (unicastFd != null ? " 单播" : "")
                + ", LOCATION=" + locationOf(from) + ")");
        // The response ST/USN must echo what the requester asked for
        // (Windows "Play To" searches upnp:rootdevice and validates the
        // response ST against its request).
        if ("ssdp:all".equals(stTrim)) {
            // Per spec, ssdp:all must be answered once per matching resource:
            // rootdevice + UUID + MediaRenderer + every service. Control points
            // that filter responses by looking for a service token in ST/USN
            // (common shortcut in casting libraries) find nothing when only the
            // device-level triple is answered.
            sendSearchResponse(from, "upnp:rootdevice", udnFull + "::upnp:rootdevice", target, port, unicastFd);
            sendSearchResponse(from, udnFull, udnFull, target, port, unicastFd);
            sendSearchResponse(from, DEVICE_TYPE, deviceUsn, target, port, unicastFd);
            for (String svc : SERVICE_TYPES) {
                sendSearchResponse(from, svc, udnFull + "::" + svc, target, port, unicastFd);
            }
            return;
        }
        String svc = serviceTypeOf(stTrim);
        String respSt;
        String respUsn;
        if (svc != null) {
            // Echo the requested service ST verbatim (spec requirement) and
            // pair it with our UDN in the USN.
            respSt = stTrim;
            respUsn = udnFull + "::" + stTrim;
        } else if ("upnp:rootdevice".equals(stTrim)) {
            respSt = "upnp:rootdevice";
            respUsn = udnFull + "::upnp:rootdevice";
        } else if (SAMSUNG_MULTI_SCREEN.equals(stTrim)) {
            // Echo the Samsung target verbatim and pair our UDN with it.
            respSt = stTrim;
            respUsn = udnFull + "::" + stTrim;
        } else if (udnFull.equals(stTrim)) {
            respSt = udnFull;
            respUsn = udnFull;
        } else if ("uuid:020000000000-dmr".equals(stTrim)) {
            // Echo the Windows DMR probe type; pair our real UDN with it.
            respSt = "uuid:020000000000-dmr";
            respUsn = udnFull + "::uuid:020000000000-dmr";
        } else if (deviceUsn.equals(stTrim)) {
            respSt = DEVICE_TYPE;
            respUsn = deviceUsn;
        } else {
            respSt = DEVICE_TYPE;
            respUsn = deviceUsn;
        }
        sendSearchResponse(from, respSt, respUsn, target, port, unicastFd);
    }

    /**
     * Returns the canonical service type for a requested ST, accepting any
     * version the sender may ask for (e.g. AVTransport:3 maps to our :1), or
     * null when the ST is not one of our services.
     */
    private static String serviceTypeOf(String st) {
        for (String svc : SERVICE_TYPES) {
            String base = svc.substring(0, svc.lastIndexOf(':'));
            if (st.equals(svc) || st.startsWith(base + ":")) {
                return svc;
            }
        }
        return null;
    }

    /** The live face whose subnet contains `target`, or null when nothing matches. */
    private Face bestFaceFor(InetAddress target) {
        Face hit = null;
        for (Face f : faces) {
            if (!faceLive(f)) {
                continue;
            }
            if (sameSubnet(f, target)) {
                hit = f;
            }
        }
        return hit;
    }

    /** Interface still up and still holding an IPv4 address (DHCP leases move). */
    private static boolean faceLive(Face f) {
        if (f.ni == null) {
            return true;
        }
        try {
            return f.ni.isUp() && firstIPv4(f.ni) != null;
        } catch (Exception e) {
            return false;
        }
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

    private void sendSearchResponse(Face f, String st, String usn, InetAddress target, int port,
                                    FileDescriptor unicastFd) {
        String resp = "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "DATE: " + httpDate() + "\r\n"
                + "EXT:\r\n"
                // DLNA CORE profile header — required by several phone cast stacks.
                + "X-User-Agent: redsonic\r\n"
                + "LOCATION: " + locationOf(f) + "\r\n"
                + "SERVER: PhairPlay/1.0 UPnP/1.1\r\n"
                + "ST: " + st + "\r\n"
                + "USN: " + usn + "\r\n\r\n";
        if (unicastFd != null) {
            sendUnicast(unicastFd, resp, target, port);
        } else {
            send(f, resp, target.getHostAddress(), port);
        }
    }

    private static void sendUnicast(FileDescriptor fd, String msg, InetAddress target, int port) {
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            Os.sendto(fd, ByteBuffer.wrap(b), 0, target, port);
        } catch (Exception ignored) {
        }
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
