package com.phairplay.dlna.transport;

import com.phairplay.dlna.renderer.DlnaPlayerBridge;
import com.phairplay.util.DebugLog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled DLNA HTTP layer that serves the device descriptor, the three
 * service SCPDs and the SOAP control actions WITHOUT going through jUPnP's
 * registry/resource matching.
 *
 * <p>WHY: the in-app jUPnP stack advertises fine (registry shows 1 device /
 * 10 resources) but its {@code Registry#getResource} lookup returns null for
 * the very descriptor path that was registered, so every GET/SOAP request
 * ends in 404. Rather than debugging a library we cannot fully control on
 * Android, this class answers the requests that a real DLNA control point
 * (Bilibili/YouKu app, VLC, Windows Play To) makes:
 * <ol>
 *   <li>GET /upnp/dev/&lt;udn&gt;/desc          → device descriptor XML</li>
 *   <li>GET .../svc/upnp-org/AVTransport/desc  → SCPD XML</li>
 *   <li>POST .../svc/upnp-org/AVTransport/action → SOAP SetAVTransportURI/Play/…</li>
 * </ol>
 *
 * <p>State kept here is minimal: the current media URI and transport state.
 * Actual playback goes through {@link DlnaPlayerBridge}.
 */
public final class ManualDlnaHttp {

    /**
     * This device's UPnP identity: its UDN and the URL prefix built from it.
     *
     * v122 — two changes here.
     *
     * (1) One source of truth. The UDN and the "/upnp/dev/&lt;udn&gt;" prefix
     * were constants duplicated in {@link ManualSsdp} (which spelled the
     * descriptor path out a third time) and again in DlnaReceiver's jUPnP
     * {@code DeviceIdentity}. A change that missed one copy makes SSDP
     * advertise a LOCATION the HTTP layer answers 404 on: the box shows up in
     * the picker and every cast to it fails.
     *
     * (2) Per box, not per app. Every install shipped the same UDN, and the UDN
     * is the primary key a control point stores a device under — two PhairPlay
     * boxes on one LAN collapse into a single picker entry whose LOCATION is
     * whichever box answered last, so casts land on the wrong TV or flap
     * between the two. The value is now derived from the box's ANDROID_ID;
     * boxes where that id is absent or the well-known ROM default keep the old
     * constant, which is no worse than today.
     *
     * Held as one immutable pair so a descriptor can never be generated from a
     * UDN while the request matching uses a different prefix.
     */
    private static final class Identity {
        final String udn;
        final String base;

        Identity(String udn) {
            this.udn = udn;
            this.base = "/upnp/dev/" + udn;
        }
    }

    /**
     * The identity every PhairPlay build advertised until v122. Kept as the
     * fallback so a box with no usable ANDROID_ID still behaves.
     */
    public static final String DEFAULT_UDN = "6f61c845-1dd2-11b2-8f7b-001185123456";

    /** {@link #DEFAULT_UDN}'s descriptor prefix, for consumers that need a
     *  compile-time constant (see {@link ManualSsdp}). */
    public static final String DEFAULT_BASE = "/upnp/dev/" + DEFAULT_UDN;

    private static volatile Identity identity = new Identity(DEFAULT_UDN);

    /** &lt;friendlyName&gt; — the name a sender's picker shows for this box. */
    private static volatile String friendlyName = "PhairPlay";
    public static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";

    private static final Pattern P_ACTION = Pattern.compile("<([A-Za-z_][\\w.-]*):([A-Za-z_][\\w]*)");

    /**
     * Media types this renderer reports through ConnectionManager#GetProtocolInfo.
     *
     * Two things drove this list past plain mp4/mkv/avi: HLS is what phone
     * casting apps push most often, and senders that consult the list compare
     * against it literally - the video/* wildcard does not stand in for
     * application/vnd.apple.mpegurl. An absent HLS entry means the sender
     * completes SetAVTransportURI and then never sends Play, presenting as a
     * silent failure on our side. DASH and the common container/audio types are
     * here for the same reason.
     */
    private static final String[] SINK_TYPES = {
        "video/*", "audio/*", "application/octet-stream",
        "video/mp4", "video/x-matroska", "video/x-msvideo", "video/quicktime",
        "video/mpeg", "video/mp2t", "video/webm", "video/x-flv",
        "video/x-ms-asf", "video/x-ms-wmv", "video/3gpp",
        "video/vnd.dlna.mpeg-tts",
        "application/vnd.apple.mpegurl", "application/x-mpegURL",
        "application/vnd.apple.mpegurl.audio",
        "audio/mpegurl", "audio/x-mpegurl",
        "application/dash+xml",
        "audio/mpeg", "audio/x-wav", "audio/mp4", "audio/aac",
        "audio/flac", "audio/ogg", "audio/L16",
    };

    /** CSV form used in the GetProtocolInfo response. */
    private static final String SINK_PROTOCOL_INFO = buildSinkProtocolInfo();

    private static String buildSinkProtocolInfo() {
        StringBuilder sb = new StringBuilder();
        for (String t : SINK_TYPES) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append("http-get:*:").append(t).append(":*");
        }
        return sb.toString();
    }

    /** The same list as SCPD allowed values, so SCPD and GetProtocolInfo agree. */
    private static String sinkAllowedValueList() {
        StringBuilder sb = new StringBuilder("<allowedValueList>");
        for (String t : SINK_TYPES) {
            sb.append("<allowedValue>http-get:*:").append(t).append(":*</allowedValue>");
        }
        return sb.append("</allowedValueList>").toString();
    }
    private static volatile String currentUri = "";
    private static volatile String transportState = "STOPPED"; // NO_MEDIA_PRESENT/STOPPED/PLAYING/PAUSED_PLAYBACK — Windows PlayTo 只认 STOPPED 为就绪
    /**
     * v122 — AVTransport#TransportStatus, the third field of GetTransportInfo.
     *
     * It used to be a literal "OK" in both the polling response and the GENA
     * event, so a rejected Play was reported as a healthy stream and the
     * sender kept waiting. ERROR_OCCURRED is the value the SCPD at
     * {@link #avtScpd()} already advertises; nothing ever produced it.
     */
    private static volatile String transportStatus = "OK";
    private static volatile long positionSeconds = 0;
    private static volatile int volume = 50;
    /**
     * Whether the sender has muted us. SetMute used to answer 200 and do
     * nothing, so the phone showed a muted renderer that kept playing at full
     * volume — and a RenderingControl event could only have reported the lie.
     */
    private static volatile boolean muted = false;

    /**
     * v99-④ — bumped by every SetAVTransportURI so the auto-start timer below
     * can tell "still the item the sender just set" from "that item was
     * replaced ten minutes ago".
     */
    private static final java.util.concurrent.atomic.AtomicInteger setUriGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * v99-④ — how long a sender may leave an item set and never send Play.
     *
     * The field log at 13:10 shows four SetAVTransportURI with no Play after
     * them and nothing at all in response: we accepted the URI, the control
     * point waited, and the user saw a box that had been told what to play
     * and did nothing. Most senders follow up immediately, so a short grace
     * period costs nothing when Play does arrive.
     */
    private static final long SET_URI_AUTOSTART_DELAY_MS = 3000L;

    private ManualDlnaHttp() {
    }

    /** The UDN without its "uuid:" prefix, as advertised in the descriptor. */
    public static String getUdn() {
        return identity.udn;
    }

    /** This device's URL prefix, "/upnp/dev/&lt;udn&gt;". */
    public static String getBase() {
        return identity.base;
    }

    public static String getFriendlyName() {
        return friendlyName;
    }

    /**
     * Publish this box's identity and picker name. Called from
     * {@code DlnaReceiver#start}, i.e. once per session before the HTTP server
     * binds and before the first SSDP announcement — a blank or absent value
     * keeps the previous one rather than producing a device no sender can name.
     */
    public static void setIdentity(String udn, String name) {
        if (udn != null && !udn.trim().isEmpty()) {
            identity = new Identity(udn.trim());
        }
        if (name != null && !name.trim().isEmpty()) {
            friendlyName = name.trim();
        }
    }

    public static String getTransportState() {
        return transportState;
    }

    /** AVTransport#TransportStatus for GetTransportInfo and the GENA event. */
    public static String getTransportStatus() {
        return transportStatus;
    }

    /** RenderingControl#Volume, as the last SetVolume left it. */
    public static int getVolume() {
        return volume;
    }

    /** RenderingControl#Mute, as the last SetMute left it. */
    public static boolean isMuted() {
        return muted;
    }

    /**
     * Live playback position / duration for GENA progress events. Pulled off
     * the ExoPlayer bridge; returns 0 when there is no player or it is gone.
     */
    public static long getCurrentPositionSeconds() {
        com.phairplay.dlna.renderer.DlnaPlayerControl pc = DlnaPlayerBridge.get();
        return pc != null ? pc.getPositionSeconds() : 0L;
    }

    public static long getCurrentDurationSeconds() {
        com.phairplay.dlna.renderer.DlnaPlayerControl pc = DlnaPlayerBridge.get();
        return pc != null ? pc.getDurationSeconds() : 0L;
    }

    /**
     * v99-④ — the player really started, so say so before anyone asks.
     *
     * A sender polls TransportState and stops re-sending Set/Play once it
     * sees PLAYING. Telling it at the moment playback actually begins (rather
     * than only when the Play action arrives) is what closes that loop.
     */
    public static void markPlaying() {
        transportState = "PLAYING";
        transportStatus = "OK";
        GenaNotifier.pushAsync();
        GenaNotifier.startProgressTicker();
    }

    /**
     * v100-② — void every auto-start timer currently in flight.
     *
     * One generation bump makes all of them fail their first guard, so the
     * user closing an item cannot be followed by our own three-second timer
     * restarting it. Called on every exit path (see
     * {@code DlnaReceiver#stopPlaybackFromUi}).
     */
    public static void cancelPendingAutoStart() {
        setUriGeneration.incrementAndGet();
    }

    /**
     * v100-① — the auto-start declined to run because the user closed this
     * item, so the far end must be told playback is not happening.
     *
     * Without this the sender keeps polling a transport state that still says
     * PLAYING, and eventually re-sends Set/Play — which is the loop we are
     * trying to break.
     */
    public static void markStoppedByUser() {
        transportState = "STOPPED";
    }

    /**
     * v101-③ — the user pressed Back inside the picture: paused, not ended.
     *
     * PAUSED_PLAYBACK rather than STOPPED, and this is the whole point of the
     * change. STOPPED told the sender the cast was over (field log: 25 re-pushes
     * of the same URI while it tried to work out what happened), and PLAYING
     * would be a lie - nothing is decoding. PAUSED_PLAYBACK is the one answer
     * that is both true and actionable: the phone shows a pause button, so the
     * user can also resume from there.
     */
    public static void markPaused() {
        transportState = "PAUSED_PLAYBACK";
        GenaNotifier.pushAsync();
        // v101-③ verification: this used to leave no trace, so "no PAUSED_PLAYBACK
        // in the log" could not be told apart from "never called". The state
        // itself is observable from the outside - a GetTransportInfo during a
        // pause returns it - but the sender stops polling once it sees the
        // pause, so in practice it is the only place it shows up.
        DebugLog.INSTANCE.log("SOAP", "markPaused → transportState=PAUSED_PLAYBACK，GENA 推送已发");
    }

    /**
     * v99-④ — SetAVTransportURI with no Play behind it: start anyway.
     *
     * Guards, all of which the field log made necessary:
     * <ul>
     *   <li>generation — a newer SetAVTransportURI replaced this one;</li>
     *   <li>uri — the media changed under us;</li>
     *   <li>state — a Play did arrive inside the grace period, so this timer
     *       is redundant and starting a second time is exactly the duplicate
     *       MediaCodec the decoder-slot work spent a week trying to stop.</li>
     * </ul>
     */
    private static void scheduleAutoStart(final String uri, final int generation) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(SET_URI_AUTOSTART_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (generation != setUriGeneration.get()) return;
                    if (!uri.equals(currentUri)) return;
                    if ("PLAYING".equals(transportState)) return;
                    DebugLog.INSTANCE.log("SOAP", "SetAVTransportURI 后 "
                            + (SET_URI_AUTOSTART_DELAY_MS / 1000)
                            + "s 未收到 Play → 自动起播: " + uri);
                    com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                    if (c != null) {
                        // v100-①: the auto-start entry, not startPlayback. The
                        // receiver refuses it outright when the user has closed
                        // this item, and it never clears a dismissal.
                        c.startPlaybackAuto(uri);
                    }
                    transportState = "PLAYING";
                    transportStatus = "OK";
                    GenaNotifier.pushAsync();
                    GenaNotifier.startProgressTicker();
                } catch (Throwable ignored) {
                    // The player is the receiver's problem now; failing here
                    // must never take the HTTP server down.
                }
            }
        }, "dlna-seturi-autostart");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isDeviceDesc(String path) {
        return (identity.base + "/desc").equals(path);
    }

    public static boolean isScpd(String path) {
        return path.startsWith(identity.base + "/svc/") && path.endsWith("/desc");
    }

    public static boolean isAction(String path) {
        return path.startsWith(identity.base + "/svc/") && path.endsWith("/action");
    }

    /** Any service URL (/desc, /action, /event): used for GENA SUBSCRIBE. */
    public static boolean isService(String path) {
        return path.startsWith(identity.base + "/svc/");
    }

    /** The service id segment of a /svc/ URL, e.g. "AVTransport".
     *  Public because GENA subscriptions are keyed the same way
     *  ({@link GenaNotifier}). */
    public static String serviceOf(String path) {
        // /upnp/dev/<udn>/svc/<ns>/<svc>/desc|action
        String rest = path.substring(identity.base.length() + "/svc/".length());
        String[] parts = rest.split("/");
        return parts.length >= 2 ? parts[1] : "";
    }

    // ─────────────────────────────── device descriptor ───────────────────────────────

    public static String deviceDescriptorXml() {
        return deviceDescriptorXml(null);
    }

    /**
     * @param presentationUrl absolute URL of the info page, or null to omit the
     *     field. Ported from upstream: a Samsung TV expects a complete set of
     *     device fields (serialNumber, modelURL, presentationURL) and skips a
     *     device whose description is incomplete.
     */
    public static String deviceDescriptorXml(String presentationUrl) {
        // One snapshot: the UDN and every service URL must describe the same
        // device, and a sender that fetches a descriptor advertising URLs under
        // a different prefix than its own UDN fails on the first action.
        Identity id = identity;
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">\n"
            + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
            + "  <device>\n"
            + "    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\n"
            // v122: was a hard-coded "PhairPlay", so the displayName setting
            // (and the Android device name it falls back to) never reached a
            // DLNA picker even though DlnaReceiver was handed exactly the value
            // the AirPlay mDNS advertises, and logged "advertising as: <name>".
            + "    <friendlyName>" + xmlEscape(friendlyName) + "</friendlyName>\n"
            + "    <manufacturer>PhairPlay</manufacturer>\n"
            + "    <manufacturerURL>https://github.com/cyj265/PhairPlay</manufacturerURL>\n"
            + "    <modelDescription>PhairPlay DLNA Media Renderer</modelDescription>\n"
            + "    <modelName>PhairPlay</modelName>\n"
            + "    <modelNumber>1.0</modelNumber>\n"
            + "    <modelURL>https://github.com/cyj265/PhairPlay</modelURL>\n"
            + "    <serialNumber>" + xmlEscape(serialNumberFor(id.udn)) + "</serialNumber>\n"
            + (presentationUrl != null && !presentationUrl.isEmpty()
                    ? "    <presentationURL>" + xmlEscape(presentationUrl) + "</presentationURL>\n" : "")
            + "    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>\n"
            + "    <UDN>uuid:" + id.udn + "</UDN>\n"
            + "    <serviceList>\n"
            + serviceEntry(AVT, "AVTransport", id.base)
            + serviceEntry(RC, "RenderingControl", id.base)
            + serviceEntry(CM, "ConnectionManager", id.base)
            + "    </serviceList>\n"
            + "  </device>\n"
            + "</root>\n";
    }

    /**
     * Derives a stable serial number from the UDN (ported from upstream); every
     * character outside [A-Za-z0-9-] becomes a dash.
     */
    private static String serialNumberFor(String udn) {
        String uuidPart = udn == null ? "" : udn.trim();
        if (uuidPart.startsWith("uuid:")) {
            uuidPart = uuidPart.substring(5);
        } else if (uuidPart.startsWith("uuid-")) {
            uuidPart = uuidPart.substring(5);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uuidPart.length(); i++) {
            char c = uuidPart.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-';
            sb.append(keep ? c : '-');
        }
        int start = 0;
        int end = sb.length();
        while (start < end && sb.charAt(start) == '-') start++;
        while (end > start && sb.charAt(end - 1) == '-') end--;
        String serial = sb.substring(start, end);
        return serial.isEmpty() ? "PhairPlay" : serial;
    }

    // ─────────────────────────────── DIAL (Samsung Smart View) ───────────────────────────────

    /**
     * DIAL — "Discovery And Launch" — is the second half of Samsung Smart View
     * discovery (ported from upstream). A Samsung TV that finds us through the
     * MultiScreen SSDP search reads the {@code Application-Url} header of the
     * device description and asks for these documents; without them the app
     * picker opens empty.
     */
    public static final String DIAL_BASE = "/dial";

    /** The single app this renderer offers. Case sensitive, like DIAL ids. */
    public static final String DIAL_APP_ID = "PhairPlay";

    public static boolean isDialPath(String path) {
        return path != null && (DIAL_BASE.equals(path) || path.startsWith(DIAL_BASE + "/"));
    }

    /** True for "/dial" and "/dial/" — the app-list document. */
    public static boolean isDialAppListPath(String path) {
        return DIAL_BASE.equals(path) || (DIAL_BASE + "/").equals(path);
    }

    /** The app list. Static: the renderer is the only app. */
    public static String dialAppsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
                + "<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\" dialVer=\"2.2\">\n"
                + "  <apps>\n"
                + "    <app name=\"" + DIAL_APP_ID + "\" id=\"" + DIAL_APP_ID + "\" />\n"
                + "  </apps>\n"
                + "</service>\n";
    }

    /**
     * The DIAL app info document for [appId], or null when the id is unknown
     * (the caller answers 404, as DIAL requires). The state is always
     * "stopped": the media is pushed by a DLNA control point, not by a DIAL
     * session owned by the renderer.
     */
    public static String dialAppInfoXml(String appId) {
        if (appId == null || !isAllowedDialAppId(appId) || !DIAL_APP_ID.equals(appId)) {
            return null;
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
                + "<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\" dialVer=\"2.2\">\n"
                + "  <name>" + DIAL_APP_ID + "</name>\n"
                + "  <options allowStop=\"true\" allowPause=\"true\" allowResume=\"true\" allowSeek=\"true\"/>\n"
                + "  <state>stopped</state>\n"
                + "  <version>1.0.0</version>\n"
                + "</service>\n";
    }

    /**
     * The app id comes from the request path. Only letters, digits, dash,
     * underscore and dot are accepted, so "../.." or "a/b" never reaches the
     * comparison. The id is never used as a path or a file name.
     */
    public static boolean isAllowedDialAppId(String appId) {
        if (appId == null || appId.isEmpty() || appId.length() > 64) {
            return false;
        }
        for (int i = 0; i < appId.length(); i++) {
            char c = appId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String serviceEntry(String type, String id, String base) {
        return "      <service>\n"
            + "        <serviceType>" + type + "</serviceType>\n"
            + "        <serviceId>urn:upnp-org:serviceId:" + id + "</serviceId>\n"
            + "        <SCPDURL>" + base + "/svc/upnp-org/" + id + "/desc</SCPDURL>\n"
            + "        <controlURL>" + base + "/svc/upnp-org/" + id + "/action</controlURL>\n"
            + "        <eventSubURL>" + base + "/svc/upnp-org/" + id + "/event</eventSubURL>\n"
            + "      </service>\n";
    }

    // ─────────────────────────────── SCPD ───────────────────────────────

    public static String scpdXml(String path) {
        String svc = serviceOf(path);
        switch (svc) {
            case "AVTransport":
                return avtScpd();
            case "RenderingControl":
                return rcScpd();
            case "ConnectionManager":
                return cmScpd();
            default:
                return null;
        }
    }

    private static String avtScpd() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
            + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
            + "  <actionList>\n"
            + "    <action><name>SetAVTransportURI</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>SetNextAVTransportURI</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>NextURI</name><direction>in</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>NextURIMetaData</name><direction>in</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetMediaInfo</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>"
            + "<argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>"
            + "<argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>"
            + "<argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>"
            + "<argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlayMedium</relatedStateVariable></argument>"
            + "<argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordMedium</relatedStateVariable></argument>"
            + "<argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>WriteStatus</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetTransportInfo</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>"
            + "<argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>"
            + "<argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetPositionInfo</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>"
            + "<argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>"
            + "<argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>"
            + "<argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>"
            + "<argument><name>RelTime</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>"
            + "<argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>"
            + "<argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>"
            + "<argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetDeviceCapabilities</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>PlayMedia</name><direction>out</direction><relatedStateVariable>PossiblePlaybackStorageMedia</relatedStateVariable></argument>"
            + "<argument><name>RecMedia</name><direction>out</direction><relatedStateVariable>PossibleRecordStorageMedia</relatedStateVariable></argument>"
            + "<argument><name>RecQualityModes</name><direction>out</direction><relatedStateVariable>PossibleRecordQualityModes</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetTransportSettings</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>PlayMode</name><direction>out</direction><relatedStateVariable>CurrentPlayMode</relatedStateVariable></argument>"
            + "<argument><name>RecQualityMode</name><direction>out</direction><relatedStateVariable>CurrentRecordQualityMode</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Stop</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Play</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Pause</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Seek</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>"
            + "<argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Next</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Previous</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "  </actionList>\n"
            + "  <serviceStateTable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>TransportState</name><dataType>string</dataType>"
            + "<allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>TRANSITIONING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>PAUSED_RECORDING</allowedValue><allowedValue>RECORDING</allowedValue><allowedValue>NO_MEDIA_PRESENT</allowedValue></allowedValueList></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>TransportStatus</name><dataType>string</dataType>"
            + "<allowedValueList><allowedValue>OK</allowedValue><allowedValue>ERROR_OCCURRED</allowedValue></allowedValueList></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>TransportPlaySpeed</name><dataType>string</dataType>"
            + "<allowedValueList><allowedValue>1</allowedValue></allowedValueList></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>CurrentPlayMode</name><dataType>string</dataType>"
            + "<allowedValueList><allowedValue>NORMAL</allowedValue></allowedValueList></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>CurrentRecordQualityMode</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"yes\"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"yes\"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"yes\"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"yes\"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>\n"
            // Declared because the LastChange we push carries them (see
            // GenaNotifier#push): a control point that validates evented
            // variables against this table drops the whole event when one is
            // missing, and Windows "Play To" does exactly that.
            + "    <stateVariable sendEvents=\"no\"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>PlayMedium</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>RecordMedium</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>WriteStatus</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>PossiblePlaybackStorageMedia</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>PossibleRecordStorageMedia</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>PossibleRecordQualityModes</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"yes\"><name>LastChange</name><dataType>string</dataType></stateVariable>\n"
            + "  </serviceStateTable>\n"
            + "</scpd>\n";
    }

    private static String rcScpd() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
            + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
            + "  <actionList>\n"
            + "    <action><name>GetVolume</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>"
            + "<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>SetVolume</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>"
            + "<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetMute</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>"
            + "<argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>SetMute</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>"
            + "<argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "  </actionList>\n"
            + "  <serviceStateTable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType>"
            + "<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>Mute</name><dataType>boolean</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>Channel</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"yes\"><name>LastChange</name><dataType>string</dataType></stateVariable>\n"
            + "  </serviceStateTable>\n"
            + "</scpd>\n";
    }

    private static String cmScpd() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
            + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
            + "  <actionList>\n"
            + "    <action><name>GetProtocolInfo</name><argumentList>"
            + "<argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>"
            + "<argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetCurrentConnectionIDs</name><argumentList>"
            + "<argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetCurrentConnectionInfo</name><argumentList>"
            + "<argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>"
            + "<argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>"
            + "<argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>"
            + "<argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>"
            + "<argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>"
            + "<argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>"
            + "<argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>"
            + "<argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>PrepareForConnection</name><argumentList>"
            + "<argument><name>RemoteProtocolInfo</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>"
            + "<argument><name>PeerConnectionManager</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>"
            + "<argument><name>PeerConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>"
            + "<argument><name>Direction</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>"
            + "<argument><name>ConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>"
            + "<argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>"
            + "<argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>ConnectionComplete</name><argumentList>"
            + "<argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "  </actionList>\n"
            + "  <serviceStateTable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>SinkProtocolInfo</name><dataType>string</dataType>"
            + sinkAllowedValueList() + "</stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType>"
            + "<allowedValueList><allowedValue>OK</allowedValue><allowedValue>ContentFormatMismatch</allowedValue><allowedValue>IncompatibleParameters</allowedValue><allowedValue>UnsupportedMode</allowedValue><allowedValue>Busy</allowedValue></allowedValueList></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType>"
            + "<allowedValueList><allowedValue>Input</allowedValue><allowedValue>Output</allowedValue></allowedValueList></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>\n"
            + "  </serviceStateTable>\n"
            + "</scpd>\n";
    }

    // ─────────────────────────────── SOAP actions ───────────────────────────────

    /**
     * Handles a SOAP control request. Returns the response SOAP body (already
     * wrapped in the envelope) or {@code null} when the action is unknown /
     * malformed (the caller then returns a SOAP error or 500).
     */
    public static String handleAction(String path, String body) {
        String svc = serviceOf(path);
        String actionName = extractAction(body);
        if (actionName == null) {
            DebugLog.INSTANCE.log("SOAP", "无法解析动作 svc=" + svc + " body=" + safeBodyPreview(body));
            return null;
        }
        String result;
        switch (svc) {
            case "AVTransport":
                result = handleAvtAction(actionName, body);
                break;
            case "RenderingControl":
                result = handleRcAction(actionName, body);
                break;
            case "ConnectionManager":
                result = handleCmAction(actionName, body);
                break;
            default:
                result = null;
                break;
        }
        if (result == null) {
            DebugLog.INSTANCE.log("SOAP", "未知动作 " + actionName + " svc=" + svc);
        } else {
            // Log only state-changing actions; polling actions would flood the log.
            boolean keyAction = "SetAVTransportURI".equals(actionName)
                    || "Play".equals(actionName) || "Pause".equals(actionName)
                    || "Stop".equals(actionName) || "Seek".equals(actionName)
                    || "SetVolume".equals(actionName);
            if (keyAction) {
                DebugLog.INSTANCE.log("SOAP", "动作 " + actionName + " svc=" + svc + " -> OK");
            }
        }
        return result;
    }

    /**
     * File-name fallback for the media title: senders frequently omit
     * DIDL-Lite, and a blank music card looks like a failure.
     */
    private static String guessTitle(String uri) {
        try {
            String path = uri;
            int q = path.indexOf('?');
            if (q > 0) path = path.substring(0, q);
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            name = java.net.URLDecoder.decode(name, "UTF-8");
            // The card is read by a person, not a file manager: an audio cast
            // that arrives as M500003XFddH3QFNkO.mp3 should show the stem.
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 6) {
                boolean plainExt = true;
                for (int i = dot + 1; i < name.length(); i++) {
                    if (!Character.isLetterOrDigit(name.charAt(i))) {
                        plainExt = false;
                        break;
                    }
                }
                if (plainExt) {
                    name = name.substring(0, dot);
                }
            }
            return name;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeBodyPreview(String body) {
        if (body == null) {
            return "null";
        }
        String s = body.trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private static String handleAvtAction(String action, String body) {
        switch (action) {
            case "SetAVTransportURI": {
                String uri = tag(body, "CurrentURI");
                if (uri == null || uri.isEmpty()) {
                    DebugLog.INSTANCE.log("SOAP", "SetAVTransportURI 提取失败 body=" + safeBodyPreview(body));
                    return null;
                }
                // Ported from upstream (DlnaMediaUri): the URI is untrusted and
                // the box fetches it itself. Length, control characters, scheme
                // and the target address are checked before it reaches the
                // player or a log line.
                String refusal = com.phairplay.util.MediaUriPolicy.check(uri);
                if (refusal != null) {
                    DebugLog.INSTANCE.log("SOAP", "拒绝 SetAVTransportURI 原因=" + refusal
                            + " uri=" + safeBodyPreview(uri));
                    return null;
                }
                uri = uri.trim();
                DebugLog.INSTANCE.log("SOAP", "SetAVTransportURI uri=" + uri);
                // v101-⑦: while the user has this exact item paused, a re-send
                // of the same URI is the sender confirming state, not a new
                // item. Acting on it would (a) flip the transport to STOPPED,
                // which is what the sender then tries to "fix" by re-sending,
                // and (b) schedule the 3 s auto-start - so the picture the user
                // just paused would come back by itself. A Play for the same
                // URI is still honoured; only the Set is ignored. A different
                // URI is a genuine change of item and goes through.
                //
                // v122: this guard used to sit *after* the state assignments
                // below, so its early return protected only the auto-start.
                // The transport was already STOPPED and the position already
                // zeroed by the time we said "ignored" - which is the paused
                // picture being knocked back to stopped on every re-confirm.
                // Nothing may be mutated before this check.
                com.phairplay.dlna.renderer.DlnaPlayerControl pausedCheck = DlnaPlayerBridge.get();
                if (pausedCheck != null && pausedCheck.isUserPaused(uri)) {
                    DebugLog.INSTANCE.log("SOAP", "暂停中收到同 URI SetAVTransportURI → 忽略（不重置状态、不调度自动起播）");
                    return avtResponse("SetAVTransportURIResponse", "");
                }
                currentUri = uri;
                transportState = "STOPPED";
                transportStatus = "OK";
                positionSeconds = 0;
                // DIDL-Lite metadata (title/artist/cover) — what the sender says
                // this item IS. Without it an audio cast can only show a black
                // screen, and the UI has no way to offer a "back to playback"
                // label. The three sources are handed over separately: a phone
                // music app's cache file is named after a hash, so the file name
                // has to stay below the tags the media file carries itself
                // (see DlnaMediaMeta).
                String meta = tag(body, "CurrentURIMetaData");
                String title = null, artist = null, artUri = null;
                if (meta != null && !meta.isEmpty()) {
                    String m = xmlUnescape(meta);
                    title = tag(m, "title");
                    artist = tag(m, "artist");
                    artUri = tag(m, "albumArtURI");
                }
                com.phairplay.dlna.DlnaMediaMeta.setSenderMeta(
                    title, artist, artUri, guessTitle(uri));
                // v99-④: a sender that sets an item and never says Play should
                // still get playback, three seconds after it stopped talking.
                scheduleAutoStart(uri, setUriGeneration.incrementAndGet());
                return avtResponse("SetAVTransportURIResponse", "");
            }
            case "Play": {
                if (currentUri.isEmpty()) {
                    return avtResponse("PlayResponse", "");
                }
                com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                boolean refused = c == null;
                if (!refused) {
                    try {
                        c.startPlayback(currentUri);
                    } catch (Throwable t) {
                        refused = true;
                        DebugLog.INSTANCE.log("SOAP", "Play 起播调用异常: " + t);
                    }
                }
                if (refused) {
                    // v122: do not answer a Play we could not carry out with
                    // PLAYING. ERROR_OCCURRED is what the SCPD advertises for
                    // exactly this, and pushing it lets an event-driven sender
                    // stop polling a stream that never began. Scope is honest:
                    // this only covers a failure observable here — a player
                    // error after startPlayback returns still arrives through
                    // notifyPlaybackEnded().
                    transportState = "STOPPED";
                    transportStatus = "ERROR_OCCURRED";
                    GenaNotifier.pushAsync();
                    return avtResponse("PlayResponse", "");
                }
                transportState = "PLAYING";
                transportStatus = "OK";
                GenaNotifier.pushAsync();
                return avtResponse("PlayResponse", "");
            }
            case "Pause": {
                // v122: an explicit Pause must veto the auto-start timer that
                // SetAVTransportURI armed; otherwise it fires 3 s later and
                // restarts the item that was just paused. Stop/Pause used to
                // leave setUriGeneration untouched, so the only caller of
                // cancelPendingAutoStart() was the UI close path.
                cancelPendingAutoStart();
                com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                if (c != null) {
                    try {
                        c.pausePlayback();
                    } catch (Throwable ignored) {
                    }
                }
                transportState = "PAUSED_PLAYBACK";
                transportStatus = "OK";
                GenaNotifier.pushAsync();
                return avtResponse("PauseResponse", "");
            }
            case "Stop": {
                // v122: see Pause — the pending auto-start is the reason a
                // stopped item came back by itself.
                cancelPendingAutoStart();
                com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                if (c != null) {
                    try {
                        c.stopPlayback();
                    } catch (Throwable ignored) {
                    }
                }
                transportState = "STOPPED";
                transportStatus = "OK";
                positionSeconds = 0;
                GenaNotifier.pushAsync();
                return avtResponse("StopResponse", "");
            }
            case "Seek": {
                String target = tag(body, "Target");
                if (target != null && !target.isEmpty()) {
                    try {
                        long seconds = parseDuration(target);
                        com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                        long current = c != null ? c.getPositionSeconds() : -1L;
                        // Senders re-issue SetAVTransportURI + Play + Seek on
                        // every poll. A Seek landing on the position we are
                        // already at is a no-op semantically, but ExoPlayer does
                        // not treat it as one: it flushes the buffer and
                        // re-fetches the segment (and the AES key on HLS), which
                        // is another few seconds before a frame can show —
                        // restarting that cycle every 12-30 s is exactly what
                        // keeps the picture black.
                        if (current >= 0 && Math.abs(current - seconds) <= 3) {
                            DebugLog.INSTANCE.log(
                                "SOAP", "Seek 忽略(位置未变): target=" + target
                                    + " 当前=" + current + "s");
                        } else {
                            if (c != null) {
                                c.seekTo(seconds);
                            }
                            positionSeconds = seconds;
                            DebugLog.INSTANCE.log(
                                "SOAP", "Seek -> " + seconds + "s (原 " + current + "s)");
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return avtResponse("SeekResponse", "");
            }
            case "GetTransportInfo": {
                return avtResponse("GetTransportInfoResponse",
                    "<CurrentTransportState>" + transportState + "</CurrentTransportState>"
                        + "<CurrentTransportStatus>" + transportStatus + "</CurrentTransportStatus>"
                        + "<CurrentSpeed>1</CurrentSpeed>");
            }
            case "GetPositionInfo": {
                boolean hasMedia = !currentUri.isEmpty();
                // Match the response shape used by Macast (a Windows Play-To
                // compatible renderer): with no media, duration/position are
                // empty strings and the counters are INT32_MAX (invalid),
                // NOT "00:00:00"/0. Windows stalls the cast when it sees
                // a zeroed position on an idle renderer.
                String dur;
                String pos;
                if (hasMedia) {
                    // Live values from the player so the control point's
                    // progress bar actually advances; fall back to the last
                    // seek target while buffering.
                    com.phairplay.dlna.renderer.DlnaPlayerControl pc = DlnaPlayerBridge.get();
                    long p = pc != null ? pc.getPositionSeconds() : -1;
                    long d = pc != null ? pc.getDurationSeconds() : -1;
                    if (p >= 0) {
                        positionSeconds = p;
                        pos = formatDuration(p);
                    } else {
                        pos = formatDuration(positionSeconds);
                    }
                    dur = d > 0 ? formatDuration(d) : "00:00:00";
                } else {
                    dur = "";
                    pos = "";
                }
                String cnt = hasMedia ? "0" : "2147483647";
                return avtResponse("GetPositionInfoResponse",
                    "<Track>" + (hasMedia ? "1" : "0") + "</Track>"
                        + "<TrackDuration>" + dur + "</TrackDuration>"
                        + "<TrackMetaData></TrackMetaData>"
                        + "<TrackURI>" + xmlEscape(currentUri) + "</TrackURI>"
                        + "<RelTime>" + pos + "</RelTime>"
                        + "<AbsTime>" + pos + "</AbsTime>"
                        + "<RelCount>" + cnt + "</RelCount>"
                        + "<AbsCount>" + cnt + "</AbsCount>");
            }
            case "GetMediaInfo": {
                boolean hasMedia = !currentUri.isEmpty();
                String mediaDur = "00:00:00";
                if (hasMedia) {
                    com.phairplay.dlna.renderer.DlnaPlayerControl pc = DlnaPlayerBridge.get();
                    long d = pc != null ? pc.getDurationSeconds() : -1;
                    if (d > 0) {
                        mediaDur = formatDuration(d);
                    }
                } else {
                    mediaDur = "";
                }
                return avtResponse("GetMediaInfoResponse",
                    "<NrTracks>" + (hasMedia ? "1" : "0") + "</NrTracks>"
                        + "<MediaDuration>" + mediaDur + "</MediaDuration>"
                        + "<CurrentURI>" + xmlEscape(currentUri) + "</CurrentURI>"
                        + "<CurrentURIMetaData></CurrentURIMetaData>"
                        + "<NextURI></NextURI>"
                        + "<NextURIMetaData></NextURIMetaData>"
                        + "<PlayMedium>NONE</PlayMedium>"
                        + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                        + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>");
            }
            case "GetDeviceCapabilities": {
                // Macast leaves PlayMedia empty on a renderer (no source role);
                // a wildcard "video/*,audio/*" here is not a valid DLNA value
                // and can make Windows reject the device.
                return avtResponse("GetDeviceCapabilitiesResponse",
                    "<PlayMedia></PlayMedia>"
                        + "<RecMedia>NOT_IMPLEMENTED</RecMedia>"
                        + "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>");
            }
            case "GetTransportSettings": {
                return avtResponse("GetTransportSettingsResponse",
                    "<PlayMode>NORMAL</PlayMode>"
                        + "<RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>");
            }
            case "Next":
            case "Previous":
                return avtResponse(action + "Response", "");
            case "SetNextAVTransportURI": {
                // Not played by this renderer (there is no queue), but an
                // unchecked URI here would still be stored by nobody — the
                // check keeps the answer honest: a refused URI gets a fault
                // instead of a silent 200.
                String next = tag(body, "NextURI");
                if (next != null && !next.trim().isEmpty()) {
                    String nextRefusal = com.phairplay.util.MediaUriPolicy.check(next);
                    if (nextRefusal != null) {
                        DebugLog.INSTANCE.log("SOAP", "拒绝 SetNextAVTransportURI 原因="
                                + nextRefusal + " uri=" + safeBodyPreview(next));
                        return null;
                    }
                }
                return avtResponse("SetNextAVTransportURIResponse", "");
            }
            default:
                return null;
        }
    }

    private static String handleRcAction(String action, String body) {
        switch (action) {
            case "GetVolume": {
                return rcResponse("GetVolumeResponse", "<CurrentVolume>" + volume + "</CurrentVolume>");
            }
            case "SetVolume": {
                String v = tag(body, "DesiredVolume");
                if (v != null && !v.isEmpty()) {
                    try {
                        volume = Integer.parseInt(v.trim());
                        if (volume < 0) {
                            volume = 0;
                        }
                        if (volume > 100) {
                            volume = 100;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                applyVolume();
                // The sender that set it is not the only one watching: another
                // control point subscribed to RenderingControl has to hear about
                // this or its slider keeps showing the old number.
                GenaNotifier.pushVolumeAsync();
                return rcResponse("SetVolumeResponse", "");
            }
            case "GetMute": {
                return rcResponse("GetMuteResponse",
                        "<CurrentMute>" + (muted ? "1" : "0") + "</CurrentMute>");
            }
            case "SetMute": {
                String m = tag(body, "DesiredMute");
                muted = "1".equals(m) || "true".equalsIgnoreCase(m);
                applyVolume();
                GenaNotifier.pushVolumeAsync();
                return rcResponse("SetMuteResponse", "");
            }
            default:
                return null;
        }
    }

    /**
     * Hand the current volume/mute to the live player and to the jUPnP-side
     * state, which the player used to be the only reader of — a remote
     * SetVolume was silently ignored because these two never met.
     */
    private static void applyVolume() {
        com.phairplay.dlna.renderer.DlnaAudioRenderingControl.setVolumeValue(volume);
        com.phairplay.dlna.renderer.DlnaPlayerControl pc = DlnaPlayerBridge.get();
        if (pc != null) {
            try {
                pc.setVolumePercent(muted ? 0 : volume);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String handleCmAction(String action, String body) {
        switch (action) {
            case "GetProtocolInfo": {
                // The Sink list is a capability contract, not decoration.
                // Senders that check it match the payload MIME against these
                // entries EXACTLY - video/* does not cover them. With HLS absent
                // here, a caster would accept SetAVTransportURI and then never
                // issue Play, which looks like a dead receiver rather than a
                // negotiation failure. This is the difference against receivers
                // such as Dangbei that advertise HLS and take the same file.
                return cmResponse("GetProtocolInfoResponse",
                    "<Source></Source>"
                        + "<Sink>" + SINK_PROTOCOL_INFO + "</Sink>");
            }
            case "GetCurrentConnectionIDs": {
                return cmResponse("GetCurrentConnectionIDsResponse", "<ConnectionIDs>0</ConnectionIDs>");
            }
            case "GetCurrentConnectionInfo": {
                return cmResponse("GetCurrentConnectionInfoResponse",
                    "<RcsID>0</RcsID>"
                        + "<AVTransportID>0</AVTransportID>"
                        + "<ProtocolInfo>http-get:*:video/mp4:*</ProtocolInfo>"
                        + "<PeerConnectionManager></PeerConnectionManager>"
                        + "<PeerConnectionID>-1</PeerConnectionID>"
                        + "<Direction>Input</Direction>"
                        + "<Status>OK</Status>");
            }
            // Windows "Play To" / WMP reserves a virtual connection before
            // pushing media. Must succeed or the cast is aborted.
            case "PrepareForConnection": {
                return cmResponse("PrepareForConnectionResponse",
                    "<ConnectionID>0</ConnectionID>"
                        + "<RcsID>0</RcsID>"
                        + "<AVTransportID>0</AVTransportID>");
            }
            case "ConnectionComplete": {
                return cmResponse("ConnectionCompleteResponse", "");
            }
            default:
                return null;
        }
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    /** Current media URI (empty when idle). Exposed for the GENA notifier. */
    public static String getCurrentUri() {
        return currentUri;
    }

    private static String avtResponse(String action, String inner) {
        return soapBody(action, AVT, inner);
    }

    private static String rcResponse(String action, String inner) {
        return soapBody(action, RC, inner);
    }

    private static String cmResponse(String action, String inner) {
        return soapBody(action, CM, inner);
    }

    private static String soapBody(String action, String serviceType, String inner) {
        return "<u:" + action + " xmlns:u=\"" + serviceType + "\">" + inner + "</u:" + action + ">";
    }

    /** Full SOAP envelope for a successful response. */
    public static String wrapEnvelope(String soapBody) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
            + "<s:Body>" + soapBody + "</s:Body></s:Envelope>";
    }

    /** Standard UPnP SOAP fault body (action failed / unknown). */
    public static String faultBody() {
        return "<s:Fault>"
            + "<faultcode>s:Client</faultcode>"
            + "<faultstring>UPnPError</faultstring>"
            + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>501</errorCode>"
            + "<errorDescription>Action Failed</errorDescription></UPnPError></detail>"
            + "</s:Fault>";
    }

    /** Public action-name extractor for logging (null when unparseable). */
    public static String actionNameOf(String body) {
        return extractAction(body);
    }

    /** Called by the player bridge when playback ends or errors out, so
     *  control points see STOPPED instead of a stuck PLAYING state. */
    public static void notifyPlaybackEnded() {
        transportState = "STOPPED";
        // The device itself is healthy — only this item finished. Clearing the
        // flag here also stops a Play failure's ERROR_OCCURRED from outliving
        // the item it referred to.
        transportStatus = "OK";
        positionSeconds = 0;
        GenaNotifier.pushAsync();
        GenaNotifier.stopProgressTicker();
    }

    /**
     * The item stopped because the player refused it, not because it finished.
     *
     * v122 — {@link #notifyPlaybackEnded()} alone told the sender "STOPPED, all
     * fine", so a cast that died on a dead URL or a refusal looked exactly like
     * the user pressing stop, and nothing on the phone said "换源". UPnP has a
     * field for this and we now fill it: the state is still STOPPED, the status
     * says the device hit an error. A silent retry that succeeds calls
     * {@link #markPlaying()}, which puts the status back to OK, so a transient
     * blip never leaves this hanging.
     */
    public static void notifyPlaybackError() {
        transportState = "STOPPED";
        transportStatus = "ERROR_OCCURRED";
        positionSeconds = 0;
        GenaNotifier.pushAsync();
        GenaNotifier.stopProgressTicker();
    }

    /**
     * v122 — drop the control-plane state a previous receiver session left
     * behind, for the rebuild paths ({@code DlnaReceiver#start} after an IP
     * change, {@code stopPlaybackFromUi}).
     *
     * Everything above is process-static, so a rebuild used to inherit the old
     * session's transport state and timers: the SSDP LOCATION and descriptor
     * were freshly advertised while GetTransportInfo still answered PLAYING for
     * a URI that no player owns any more, and a timer scheduled before the
     * teardown could still fire after it.
     *
     * Kept on purpose: {@code currentUri} (a rebuilt receiver is expected to
     * carry on with the same item) and {@code volume} (whatever the sender last
     * set is still the box's volume).
     */
    public static void reset() {
        // Void every auto-start counting down for the old session.
        setUriGeneration.incrementAndGet();
        transportState = "STOPPED";
        transportStatus = "OK";
        positionSeconds = 0;
        // The old subscriber's socket died with the old address; pushing events
        // there would only burn the failure budget that releases the SID.
        GenaNotifier.unsubscribe();
    }

    private static String extractAction(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = P_ACTION.matcher(body);
        while (m.find()) {
            String action = m.group(2);
            // Skip SOAP envelope scaffolding (prefix may be s / soap / SOAP-ENV).
            if ("Envelope".equals(action) || "Body".equals(action)
                    || "Header".equals(action) || "Fault".equals(action)) {
                continue;
            }
            return action;
        }
        return null;
    }

    /**
     * v122 — one tag lookup for SOAP arguments and DIDL-Lite fields alike.
     *
     * Prefix-agnostic (asking for "title" matches {@code <dc:title>} and a bare
     * {@code <title>}) and attribute-tolerant (so
     * {@code <upnp:albumArtURI dlna:profileID="*">} parses). The previous two
     * patterns split on ':' and compared the queried string against the LOCAL
     * name, so a call site that passed "dc:title" could never match anything —
     * DIDL metadata was silently dropped for every sender that did send it and
     * the music card fell back to the file name.
     *
     * Patterns are cached because this runs on the socket thread once per
     * argument per action.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> TAG_PATTERNS =
            new java.util.concurrent.ConcurrentHashMap<String, Pattern>();

    private static String tag(String body, String name) {
        if (body == null || name == null || name.isEmpty()) {
            return null;
        }
        String q = Pattern.quote(name);
        Pattern p = TAG_PATTERNS.get(name);
        if (p == null) {
            p = Pattern.compile("<(?:[\\w.-]+:)?" + q + "(?:\\s[^>]*)?>([^<]*)"
                    + "</(?:[\\w.-]+:)?" + q + "\\s*>");
            TAG_PATTERNS.put(name, p);
        }
        Matcher m = p.matcher(body);
        if (m.find()) {
            String v = m.group(1);
            return v == null ? "" : xmlUnescape(v);
        }
        return null;
    }

    /** SOAP argument values arrive XML-escaped; without unescaping, any URL
     *  containing "&amp;" (query params!) is stored broken and never plays. */
    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&");
    }

    private static String xmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    /** Parses "HH:MM:SS", "HH:MM:SS.fractions" or plain seconds into seconds. */
    private static long parseDuration(String s) {
        String[] parts = s.split(":");
        if (parts.length == 3) {
            try {
                return Long.parseLong(parts[0].trim()) * 3600
                    + Long.parseLong(parts[1].trim()) * 60
                    + parseSecondsField(parts[2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Seconds field may carry a fractional part ("45.678") — UPnP targets do. */
    private static long parseSecondsField(String sec) {
        String t = sec.trim();
        int dot = t.indexOf('.');
        if (dot >= 0) {
            t = t.substring(0, dot);
        }
        return Long.parseLong(t);
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
