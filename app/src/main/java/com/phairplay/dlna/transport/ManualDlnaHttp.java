package com.phairplay.dlna.transport;

import com.phairplay.dlna.renderer.DlnaPlayerBridge;

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

    public static final String UDN = "uuid-phairplay-dlna-renderer";
    public static final String BASE = "/upnp/dev/" + UDN;
    public static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";

    private static final Pattern P_ACTION = Pattern.compile("<(?:u|ns\\d+):(\\w+)");
    private static final Pattern P_TAG = Pattern.compile("<([A-Za-z0-9_]+)>([^<]*)</\\1>");

    private static volatile String currentUri = "";
    private static volatile String transportState = "NO_MEDIA_PRESENT"; // NO_MEDIA_PRESENT/STOPPED/PLAYING/PAUSED_PLAYBACK
    private static volatile long positionSeconds = 0;
    private static volatile int volume = 50;

    private ManualDlnaHttp() {
    }

    public static boolean isDeviceDesc(String path) {
        return (BASE + "/desc").equals(path);
    }

    public static boolean isScpd(String path) {
        return path.startsWith(BASE + "/svc/") && path.endsWith("/desc");
    }

    public static boolean isAction(String path) {
        return path.startsWith(BASE + "/svc/") && path.endsWith("/action");
    }

    /** The service id segment of a /svc/ URL, e.g. "AVTransport". */
    private static String serviceOf(String path) {
        // /upnp/dev/<udn>/svc/<ns>/<svc>/desc|action
        String rest = path.substring(BASE.length() + "/svc/".length());
        String[] parts = rest.split("/");
        return parts.length >= 2 ? parts[1] : "";
    }

    // ─────────────────────────────── device descriptor ───────────────────────────────

    public static String deviceDescriptorXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n"
            + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
            + "  <device>\n"
            + "    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>\n"
            + "    <friendlyName>PhairPlay</friendlyName>\n"
            + "    <manufacturer>PhairPlay</manufacturer>\n"
            + "    <manufacturerURL>https://github.com/cyj265/PhairPlay</manufacturerURL>\n"
            + "    <modelDescription>PhairPlay DLNA Media Renderer</modelDescription>\n"
            + "    <modelName>PhairPlay</modelName>\n"
            + "    <modelNumber>1.0</modelNumber>\n"
            + "    <UDN>uuid:" + UDN + "</UDN>\n"
            + "    <serviceList>\n"
            + serviceEntry(AVT, "AVTransport")
            + serviceEntry(RC, "RenderingControl")
            + serviceEntry(CM, "ConnectionManager")
            + "    </serviceList>\n"
            + "  </device>\n"
            + "</root>\n";
    }

    private static String serviceEntry(String type, String id) {
        return "      <service>\n"
            + "        <serviceType>" + type + "</serviceType>\n"
            + "        <serviceId>urn:upnp-org:serviceId:" + id + "</serviceId>\n"
            + "        <SCPDURL>" + BASE + "/svc/upnp-org/" + id + "/desc</SCPDURL>\n"
            + "        <controlURL>" + BASE + "/svc/upnp-org/" + id + "/action</controlURL>\n"
            + "        <eventSubURL>" + BASE + "/svc/upnp-org/" + id + "/event</eventSubURL>\n"
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
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>SetNextAVTransportURI</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>NextURI</name><direction>in</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>NextURIMetaData</name><direction>in</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetMediaInfo</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
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
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>"
            + "<argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>"
            + "<argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetPositionInfo</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
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
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>PlayMedia</name><direction>out</direction><relatedStateVariable>PossiblePlaybackStorageMedia</relatedStateVariable></argument>"
            + "<argument><name>RecMedia</name><direction>out</direction><relatedStateVariable>PossibleRecordStorageMedia</relatedStateVariable></argument>"
            + "<argument><name>RecQualityModes</name><direction>out</direction><relatedStateVariable>PossibleRecordQualityModes</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetTransportSettings</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>PlayMode</name><direction>out</direction><relatedStateVariable>CurrentPlayMode</relatedStateVariable></argument>"
            + "<argument><name>RecQualityMode</name><direction>out</direction><relatedStateVariable>CurrentRecordQualityMode</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Stop</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Play</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Pause</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Seek</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "<argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>"
            + "<argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Next</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>Previous</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "  </actionList>\n"
            + "  <serviceStateTable>\n"
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
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>"
            + "<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>SetVolume</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>"
            + "<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>GetMute</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>"
            + "<argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>SetMute</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>Channel</relatedStateVariable></argument>"
            + "<argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "  </actionList>\n"
            + "  <serviceStateTable>\n"
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
            + "  </actionList>\n"
            + "  <serviceStateTable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>\n"
            + "    <stateVariable sendEvents=\"no\"><name>SinkProtocolInfo</name><dataType>string</dataType>"
            + "<allowedValueList><allowedValue>http-get:*:video/mp4:*</allowedValue><allowedValue>http-get:*:video/x-matroska:*</allowedValue><allowedValue>http-get:*:video/x-msvideo:*</allowedValue><allowedValue>http-get:*:video/quicktime:*</allowedValue><allowedValue>http-get:*:audio/mpeg:*</allowedValue><allowedValue>http-get:*:audio/x-wav:*</allowedValue></allowedValueList></stateVariable>\n"
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
            return null;
        }
        switch (svc) {
            case "AVTransport":
                return handleAvtAction(actionName, body);
            case "RenderingControl":
                return handleRcAction(actionName, body);
            case "ConnectionManager":
                return handleCmAction(actionName, body);
            default:
                return null;
        }
    }

    private static String handleAvtAction(String action, String body) {
        switch (action) {
            case "SetAVTransportURI": {
                String uri = tag(body, "CurrentURI");
                if (uri == null || uri.isEmpty()) {
                    return null;
                }
                currentUri = uri;
                transportState = "STOPPED";
                positionSeconds = 0;
                return avtResponse("SetAVTransportURIResponse", "");
            }
            case "Play": {
                if (currentUri.isEmpty()) {
                    return avtResponse("PlayResponse", "");
                }
                com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                if (c != null) {
                    try {
                        c.startPlayback(currentUri);
                    } catch (Throwable t) {
                        // surface errors as STOPPED, keep server alive
                    }
                }
                transportState = "PLAYING";
                return avtResponse("PlayResponse", "");
            }
            case "Pause": {
                com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                if (c != null) {
                    try {
                        c.pausePlayback();
                    } catch (Throwable ignored) {
                    }
                }
                transportState = "PAUSED_PLAYBACK";
                return avtResponse("PauseResponse", "");
            }
            case "Stop": {
                com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                if (c != null) {
                    try {
                        c.stopPlayback();
                    } catch (Throwable ignored) {
                    }
                }
                transportState = "STOPPED";
                positionSeconds = 0;
                return avtResponse("StopResponse", "");
            }
            case "Seek": {
                String target = tag(body, "Target");
                if (target != null && !target.isEmpty()) {
                    try {
                        long seconds = parseDuration(target);
                        com.phairplay.dlna.renderer.DlnaPlayerControl c = DlnaPlayerBridge.get();
                        if (c != null) {
                            c.seekTo(seconds);
                        }
                        positionSeconds = seconds;
                    } catch (Throwable ignored) {
                    }
                }
                return avtResponse("SeekResponse", "");
            }
            case "GetTransportInfo": {
                return avtResponse("GetTransportInfoResponse",
                    "<CurrentTransportState>" + transportState + "</CurrentTransportState>"
                        + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                        + "<CurrentSpeed>1</CurrentSpeed>");
            }
            case "GetPositionInfo": {
                return avtResponse("GetPositionInfoResponse",
                    "<Track>1</Track>"
                        + "<TrackDuration>00:00:00</TrackDuration>"
                        + "<TrackMetaData></TrackMetaData>"
                        + "<TrackURI>" + xmlEscape(currentUri) + "</TrackURI>"
                        + "<RelTime>" + formatDuration(positionSeconds) + "</RelTime>"
                        + "<AbsTime>00:00:00</AbsTime>"
                        + "<RelCount>0</RelCount>"
                        + "<AbsCount>0</AbsCount>");
            }
            case "GetMediaInfo": {
                return avtResponse("GetMediaInfoResponse",
                    "<NrTracks>1</NrTracks>"
                        + "<MediaDuration>00:00:00</MediaDuration>"
                        + "<CurrentURI>" + xmlEscape(currentUri) + "</CurrentURI>"
                        + "<CurrentURIMetaData></CurrentURIMetaData>"
                        + "<NextURI></NextURI>"
                        + "<NextURIMetaData></NextURIMetaData>"
                        + "<PlayMedium>NONE</PlayMedium>"
                        + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                        + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>");
            }
            case "GetDeviceCapabilities": {
                return avtResponse("GetDeviceCapabilitiesResponse",
                    "<PlayMedia>NONE</PlayMedia>"
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
            case "SetNextAVTransportURI":
                return avtResponse("SetNextAVTransportURIResponse", "");
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
                return rcResponse("SetVolumeResponse", "");
            }
            case "GetMute": {
                return rcResponse("GetMuteResponse", "<CurrentMute>0</CurrentMute>");
            }
            case "SetMute": {
                return rcResponse("SetMuteResponse", "");
            }
            default:
                return null;
        }
    }

    private static String handleCmAction(String action, String body) {
        switch (action) {
            case "GetProtocolInfo": {
                return cmResponse("GetProtocolInfoResponse",
                    "<Source></Source>"
                        + "<Sink>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/x-msvideo:*,http-get:*:video/quicktime:*,http-get:*:audio/mpeg:*,http-get:*:audio/x-wav:*</Sink>");
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
            default:
                return null;
        }
    }

    // ─────────────────────────────── helpers ───────────────────────────────

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

    private static String extractAction(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = P_ACTION.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String tag(String body, String name) {
        if (body == null) {
            return null;
        }
        Matcher m = P_TAG.matcher(body);
        while (m.find()) {
            if (name.equals(m.group(1))) {
                String v = m.group(2);
                return v == null ? "" : v;
            }
        }
        return null;
    }

    private static String xmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    /** Parses "HH:MM:SS" or "seconds" into seconds. */
    private static long parseDuration(String s) {
        String[] parts = s.split(":");
        if (parts.length == 3) {
            try {
                return Long.parseLong(parts[0]) * 3600
                    + Long.parseLong(parts[1]) * 60
                    + Long.parseLong(parts[2]);
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

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
