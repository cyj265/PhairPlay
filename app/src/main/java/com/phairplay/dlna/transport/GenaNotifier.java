package com.phairplay.dlna.transport;

import com.phairplay.util.DebugLog;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal GENA event pusher: sends an initial LastChange NOTIFY right after a
 * SUBSCRIBE succeeds, on transport-state changes, and periodically while
 * PLAYING so that control points which track the event stream (rather than
 * polling GetPositionInfo) also see the progress bar advance with a real
 * RelTime.
 *
 * This is the missing half of "the progress bar doesn't move": the other half
 * (GetPositionInfo returning a real RelTime) already exists in ManualDlnaHttp.
 *
 * Windows Play To waits for the initial event before it considers the renderer
 * ready and pushes media, so without it the cast silently stalls.
 */
public final class GenaNotifier {

    /** The services we event, keyed exactly as {@code ManualDlnaHttp#serviceOf}. */
    private static final String AVT = "AVTransport";
    private static final String RC = "RenderingControl";

    /**
     * v122 — one record per subscribed service.
     *
     * There used to be a single callback/SID/SEQ triple shared by AVTransport,
     * RenderingControl and ConnectionManager. A control point subscribes to all
     * three, usually in one burst, so the LAST subscribe won: the AVTransport
     * LastChange that Windows Play To waits for was sent with the
     * RenderingControl SID (which it discards as foreign), its SEQ was reset by
     * every later subscriber, and any one UNSUBSCRIBE or dead callback released
     * the subscription the cast depended on. Keying by service is also what lets
     * a gone subscriber be dropped without taking the other two with it.
     */
    private static final class Sub {
        volatile String callback;
        volatile String sid;
        final AtomicInteger seq = new AtomicInteger(0);
        /** Consecutive failed pushes - after enough, this subscriber is gone. */
        final AtomicInteger failures = new AtomicInteger(0);
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Sub> subs =
            new java.util.concurrent.ConcurrentHashMap<String, Sub>();

    /**
     * Progress ticker: while a control point is subscribed and the renderer is
     * PLAYING, re-push LastChange every {@link #TICK_MS} so event-driven
     * senders (BubbleUPnP, some phone gallery casts) advance their progress
     * bar. The single daemon task lives for the process lifetime and simply
     * early-returns when not PLAYING / not subscribed.
     */
    private static final long TICK_MS = 2000L;
    private static final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gena-progress-ticker");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicBoolean ticking = new AtomicBoolean(false);
    private static final int MAX_FAILURES = 5;

    /**
     * One-shot pusher: call sites in ManualDlnaHttp (markPlaying, markPaused,
     * notifyPlaybackEnded, …) run on the ExoPlayer / main thread, where a
     * blocking socket connect throws android.os.NetworkOnMainThreadException.
     * This executor moves the actual send off the calling thread. The ticker
     * and register() still call {@link #push()} directly because they already
     * run on background threads.
     */
    private static final ExecutorService pusher =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "gena-pusher");
                t.setDaemon(true);
                return t;
            });

    private GenaNotifier() {
    }

    /** SID handed out for one service (null when nothing subscribes to it). */
    public static String currentSid(String service) {
        Sub sub = subs.get(service);
        return sub == null ? null : sub.sid;
    }

    /**
     * Remember one service's subscriber. An AVTransport subscription also gets
     * the initial LastChange, which is what gates a Windows cast; RenderingControl
     * gets its own, so the slider on the phone starts at the box's real volume
     * instead of whatever the sender assumed.
     *
     * ConnectionManager gets no event: jUPnP ships no CM LastChange implementation
     * either (its support jar has no {@code connectionmanager/lastchange} package),
     * and inventing the CMS variable list is how a sender ends up discarding the
     * NOTIFY outright — the exact class of bug the AVTransport payload had.
     */
    public static void register(String service, String callback, String newSid) {
        Sub sub = new Sub();
        sub.callback = callback;
        sub.sid = newSid;
        subs.put(service, sub);
        final boolean avt = AVT.equals(service);
        if (!avt && !RC.equals(service)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            if (avt) {
                push();
            } else {
                pushVolume();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** One service cancelled (a SID-matched UNSUBSCRIBE). */
    public static void unsubscribe(String service) {
        if (subs.remove(service) != null && AVT.equals(service)) {
            stopProgressTicker();
        }
    }

    /** Every service forgotten — receiver teardown, see ManualDlnaHttp#reset. */
    public static void unsubscribe() {
        subs.clear();
        stopProgressTicker();
    }

    /** Begin (idempotent) periodic progress pushes while PLAYING. */
    public static void startProgressTicker() {
        if (ticking.compareAndSet(false, true)) {
            ticker.scheduleWithFixedDelay(() -> {
                if (!ticking.get()) {
                    return;
                }
                Sub sub = subs.get(AVT);
                if (sub == null || sub.sid == null || sub.callback == null) {
                    return;
                }
                if (!"PLAYING".equals(ManualDlnaHttp.getTransportState())) {
                    return;
                }
                push();
            }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Stop periodic pushes (playback ended / unsubscribed). Idempotent. */
    public static void stopProgressTicker() {
        ticking.set(false);
    }

    /** Push on a background thread (for call sites on the main/player thread). */
    public static void pushAsync() {
        pusher.submit(GenaNotifier::push);
    }

    /** Same, for the RenderingControl subscriber (SetVolume / SetMute). */
    public static void pushVolumeAsync() {
        pusher.submit(GenaNotifier::pushVolume);
    }

    /**
     * Push the AVTransport LastChange to its own subscriber. RenderingControl has
     * {@link #pushVolume()}; each service gets a document of its own type, never
     * another service's — which is what the single-slot version did whenever
     * AVTransport happened to be the one holding the callback, and strict control
     * points read an unknown property as broken eventing.
     */
    public static void push() {
        Sub sub = subs.get(AVT);
        if (sub == null) {
            return;
        }
        String state = ManualDlnaHttp.getTransportState();
        String uri = ManualDlnaHttp.getCurrentUri();
        String status = ManualDlnaHttp.getTransportStatus();
        long posSec = ManualDlnaHttp.getCurrentPositionSeconds();
        long durSec = ManualDlnaHttp.getCurrentDurationSeconds();
        String pos = formatHMS(posSec);
        String dur = durSec > 0 ? formatHMS(durSec) : "00:00:00";
        String track = (uri != null && !uri.isEmpty()) ? "1" : "0";
        // v122 — the LastChange payload is an XML document *inside* the text
        // of <LastChange>, so it is parsed twice by the control point. The
        // old code hand-escaped the inner tags while inserting
        // <AVTransportURI> raw, which made the outer parse take the URI
        // element as a child of <LastChange> instead of part of its text:
        // the AVTransportURI property never reached anyone. Worse, the URI
        // itself was escaped only once, so a media URL carrying a query
        // string put a bare "&" into character data and the whole NOTIFY
        // failed to parse (measured: "The reference to entity must end with
        // the ';' delimiter") — i.e. every signed/expiring URL from a phone
        // caster silently killed the event Windows waits for.
        // Building the inner document normally and escaping it once as a
        // whole gets both levels right; verified against a two-level parse.
        String inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">"
                + "<InstanceID val=\"0\">"
                + "<TransportState val=\"" + state + "\"/>"
                + "<TransportStatus val=\"" + status + "\"/>"
                + "<RelativeTimePosition val=\"" + pos + "\"/>"
                + "<AbsoluteTimePosition val=\"" + pos + "\"/>"
                + "<CurrentMediaDuration val=\"" + dur + "\"/>"
                + "<CurrentTrackDuration val=\"" + dur + "\"/>"
                + "<CurrentTrack val=\"" + track + "\"/>"
                + "<NumberOfTracks val=\"" + track + "\"/>"
                + (uri != null && !uri.isEmpty()
                        ? "<AVTransportURI>" + escapeXml(uri) + "</AVTransportURI>" : "")
                + "</InstanceID></Event>";
        deliver(AVT, sub, inner, "state=" + state + " pos=" + pos + " dur=" + dur);
    }

    /**
     * Push the RenderingControl LastChange. Per
     * {@code urn:schemas-upnp-org:metadata-1-0/RCS/}, {@code Volume} and
     * {@code Mute} both require the {@code channel} and {@code val} attributes,
     * and {@code Mute}'s value is a boolean, so 0/1. A sender that has subscribed
     * to RenderingControl but never gets this shows its own guess for the box's
     * volume, and the next SetVolume from that slider overwrites the real one.
     */
    public static void pushVolume() {
        Sub sub = subs.get(RC);
        if (sub == null) {
            return;
        }
        int vol = ManualDlnaHttp.getVolume();
        boolean muted = ManualDlnaHttp.isMuted();
        String inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\">"
                + "<InstanceID val=\"0\">"
                + "<Volume channel=\"Master\" val=\"" + vol + "\"/>"
                + "<Mute channel=\"Master\" val=\"" + (muted ? "1" : "0") + "\"/>"
                + "</InstanceID></Event>";
        deliver(RC, sub, inner, "volume=" + vol + " mute=" + (muted ? "1" : "0"));
    }

    /**
     * One NOTIFY with one service's document, to that service's subscriber.
     *
     * A subscriber that stops accepting events is gone — stop spamming a dead
     * callback and release that subscription so a fresh one can take over. Only
     * that service: the old global release also dropped two services that were
     * still perfectly alive.
     */
    private static void deliver(String service, Sub sub, String inner, String detail) {
        String cb = sub.callback;
        String s = sub.sid;
        if (cb == null || s == null) {
            return;
        }
        String body = "<?xml version=\"1.0\"?>\n"
                + "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">\n"
                + "<e:property>\n"
                + "<LastChange>" + escapeXml(inner) + "</LastChange>\n"
                + "</e:property>\n"
                + "</e:propertyset>\n";
        String host = null;
        int port = 80;
        try {
            URL u = new URL(cb);
            host = u.getHost();
            port = u.getPort() > 0 ? u.getPort() : 80;
            // The subscriber told us a URL, not just a host: its callback path
            // (and query, which several control points use to key the session)
            // is part of the request target. "NOTIFY *" is only legal for
            // OPTIONS — an HTTP server that follows RFC 7230 answers 400 and
            // the control point writes that off as broken eventing.
            String target = u.getFile();
            if (target == null || target.isEmpty()) {
                target = "/";
            }
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 3000);
            sock.setSoTimeout(3000);
            OutputStream os = sock.getOutputStream();
            String head = "NOTIFY " + target + " HTTP/1.1\r\n"
                    + "HOST: " + host + ":" + port + "\r\n"
                    + "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"
                    + "NT: upnp:event\r\n"
                    + "NTS: upnp:propchange\r\n"
                    + "SID: " + s + "\r\n"
                    + "SEQ: " + sub.seq.getAndIncrement() + "\r\n"
                    + "CONTENT-LENGTH: " + body.getBytes("UTF-8").length + "\r\n\r\n";
            os.write((head + body).getBytes("UTF-8"));
            os.flush();
            sock.close();
            sub.failures.set(0);
            DebugLog.INSTANCE.log("SOAP", "事件推送 " + service + " LastChange -> "
                    + host + ":" + port + " " + detail);
        } catch (Throwable t) {
            int fails = sub.failures.incrementAndGet();
            StringBuilder sb = new StringBuilder("事件推送失败(" + service + "," + fails + ") " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                sb.append("\n  at ").append(e);
            }
            DebugLog.INSTANCE.log("SOAP", sb.toString());
            if (fails >= MAX_FAILURES) {
                unsubscribe(service);
            }
        }
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String formatHMS(long seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long sec = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, sec);
    }
}
