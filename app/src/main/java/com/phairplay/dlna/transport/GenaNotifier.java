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

    private static volatile String callbackUrl;
    private static volatile String sid;
    /** GENA event SEQ: starts at 0 after each (re-)subscription, then increments. */
    private static final AtomicInteger seq =
            new AtomicInteger(0);

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
    /** Consecutive pushes that failed — after enough, the subscriber is gone. */
    private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
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

    /** SID handed out for the current subscription (null when none). */
    public static String currentSid() {
        return sid;
    }

    /** Remember the subscriber and push the initial LastChange shortly after. */
    public static synchronized void register(String callback, String newSid) {
        if (callback != null) {
            callbackUrl = callback;
        }
        sid = newSid;
        seq.set(0);
        consecutiveFailures.set(0);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            push();
        });
        t.setDaemon(true);
        t.start();
    }

    /** A subscriber cancelled: drop the SID and stop ticking. */
    public static synchronized void unsubscribe() {
        sid = null;
        callbackUrl = null;
        stopProgressTicker();
    }

    /** Begin (idempotent) periodic progress pushes while PLAYING. */
    public static void startProgressTicker() {
        if (ticking.compareAndSet(false, true)) {
            ticker.scheduleWithFixedDelay(() -> {
                if (!ticking.get()) {
                    return;
                }
                String s = sid;
                String cb = callbackUrl;
                if (s == null || cb == null) {
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

    /** Push current LastChange: transport state + real RelTime + duration. */
    public static void push() {
        String cb = callbackUrl;
        String s = sid;
        if (cb == null || s == null) {
            return;
        }
        try {
            URL u = new URL(cb);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String state = ManualDlnaHttp.getTransportState();
            String uri = ManualDlnaHttp.getCurrentUri();
            String avt = (uri != null && !uri.isEmpty())
                    ? "<AVTransportURI>" + escapeXml(uri) + "</AVTransportURI>" : "";
            String status = "OK";
            long posSec = ManualDlnaHttp.getCurrentPositionSeconds();
            long durSec = ManualDlnaHttp.getCurrentDurationSeconds();
            String pos = formatHMS(posSec);
            String dur = durSec > 0 ? formatHMS(durSec) : "00:00:00";
            String track = (uri != null && !uri.isEmpty()) ? "1" : "0";
            String body = "<?xml version=\"1.0\"?>\n"
                    + "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">\n"
                    + "<e:property>\n"
                    + "<LastChange>&lt;Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"&gt;&lt;InstanceID val=\"0\"&gt;"
                    + "&lt;TransportState val=\"" + state + "\"/&gt;"
                    + "&lt;TransportStatus val=\"" + status + "\"/&gt;"
                    + "&lt;RelativeTimePosition val=\"" + pos + "\"/&gt;"
                    + "&lt;AbsoluteTimePosition val=\"" + pos + "\"/&gt;"
                    + "&lt;CurrentMediaDuration val=\"" + dur + "\"/&gt;"
                    + "&lt;CurrentTrackDuration val=\"" + dur + "\"/&gt;"
                    + "&lt;CurrentTrack val=\"" + track + "\"/&gt;"
                    + "&lt;NumberOfTracks val=\"" + track + "\"/&gt;"
                    + avt + "&lt;/InstanceID&gt;&lt;/Event&gt;</LastChange>\n"
                    + "</e:property>\n"
                    + "</e:propertyset>\n";
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 3000);
            sock.setSoTimeout(3000);
            OutputStream os = sock.getOutputStream();
            String head = "NOTIFY * HTTP/1.1\r\n"
                    + "HOST: " + host + ":" + port + "\r\n"
                    + "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"
                    + "NT: upnp:event\r\n"
                    + "NTS: upnp:propchange\r\n"
                    + "SID: " + s + "\r\n"
                    + "SEQ: " + seq.getAndIncrement() + "\r\n"
                    + "CONTENT-LENGTH: " + body.getBytes("UTF-8").length + "\r\n\r\n";
            os.write((head + body).getBytes("UTF-8"));
            os.flush();
            sock.close();
            consecutiveFailures.set(0);
            DebugLog.INSTANCE.log("SOAP", "事件推送 LastChange -> " + host + ":" + port
                    + " state=" + state + " pos=" + pos + " dur=" + dur);
        } catch (Throwable t) {
            int fails = consecutiveFailures.incrementAndGet();
            StringBuilder sb = new StringBuilder("事件推送失败(" + fails + ") " + t);
            for (StackTraceElement e : t.getStackTrace()) {
                sb.append("\n  at ").append(e);
            }
            DebugLog.INSTANCE.log("SOAP", sb.toString());
            // A subscriber that stops accepting events is gone — stop spamming a
            // dead callback and release the subscription so a fresh one can take
            // over. (UNSUBSCRIBE already calls unsubscribe(); this is the safety
            // net for crash / network-loss / no-UNSUBSCRIBE teardown.)
            if (fails >= MAX_FAILURES) {
                unsubscribe();
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
