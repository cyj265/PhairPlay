package com.phairplay.dlna.transport;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.phairplay.util.DebugLog;
import org.jupnp.model.message.Connection;
import org.jupnp.model.message.StreamRequestMessage;
import org.jupnp.model.message.StreamResponseMessage;
import org.jupnp.model.message.UpnpHeaders;
import org.jupnp.model.message.UpnpMessage;
import org.jupnp.model.message.UpnpRequest;
import org.jupnp.protocol.ProtocolFactory;
import org.jupnp.transport.spi.UpnpStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal, socket-based {@link UpnpStream} implementation used by
 * {@link AndroidStreamServer}. It parses a single HTTP request from the
 * socket, hands it to the jUPnP protocol stack, and writes the response back.
 *
 * <p>This replaces the Jetty/servlet based transport that jUPnP ships by
 * default, so the app does not need the Jetty libraries (which would otherwise
 * surface as NoClassDefFoundError on Android and are too new to dex for the
 * minSdk 25 Fire TV flavor).</p>
 */
public class AndroidUpnpStream extends UpnpStream {

    private static final Logger logger = LoggerFactory.getLogger(AndroidUpnpStream.class.getName());

    private final Socket socket;

    public AndroidUpnpStream(ProtocolFactory protocolFactory, Socket socket) {
        super(protocolFactory);
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            try {
                socket.setSoTimeout(30000);
            } catch (IOException ignored) {
            }

            HttpRequest request = HttpRequest.read(socket);
            if (request == null) {
                return;
            }

            logger.trace("Processing HTTP request: {} {}", request.method, request.uri);

            // Manual DLNA layer: answer descriptor / SCPD / SOAP requests
            // directly, bypassing jUPnP's registry resource matching which
            // returns 404 on device (despite a healthy registry).
            String reqPath = request.uri != null ? request.uri.getPath() : "";
            if ("GET".equals(request.method)) {
                if ("/debug".equals(reqPath)) {
                    // Plain-text diagnostics dump, reachable from any phone
                    // browser on the LAN (http://<box-ip>:8899/debug). A TV box
                    // has no practical way to move a clipboard elsewhere, and a
                    // phone camera pointed at a TV screen loses half the lines —
                    // this path makes the counters directly fetchable.
                    // Query strings are stripped first: the log carries the
                    // signed playback URLs the phone handed over, and this port
                    // answers anyone on the LAN.
                    byte[] body = DebugLog.INSTANCE.redactUrls(
                            DebugLog.INSTANCE.dump()).getBytes("UTF-8");
                    String resp = "HTTP/1.1 200 OK\r\n"
                            + "SERVER: PhairPlay/1.0 UPnP/1.0\r\n"
                            + "CONTENT-TYPE: text/plain; charset=utf-8\r\n"
                            + "CONTENT-LENGTH: " + body.length + "\r\n"
                            + "CACHE-CONTROL: no-store\r\n"
                            + "\r\n";
                    socket.getOutputStream().write(resp.getBytes("UTF-8"));
                    socket.getOutputStream().write(body);
                    return;
                }
                if (ManualDlnaHttp.isDeviceDesc(reqPath)) {
                    DebugLog.INSTANCE.log("HTTP", "GET " + reqPath + " -> 200 设备描述");
                    writeXml(socket.getOutputStream(), ManualDlnaHttp.deviceDescriptorXml());
                    return;
                }
                if (ManualDlnaHttp.isScpd(reqPath)) {
                    String scpd = ManualDlnaHttp.scpdXml(reqPath);
                    if (scpd != null) {
                        DebugLog.INSTANCE.log("HTTP", "GET " + reqPath + " -> 200 SCPD");
                        writeXml(socket.getOutputStream(), scpd);
                        return;
                    }
                }
            }
            if ("POST".equals(request.method) && ManualDlnaHttp.isAction(reqPath)) {
                String body = new String(request.body, "UTF-8");
                String actionName = ManualDlnaHttp.actionNameOf(body);
                String respBody = ManualDlnaHttp.handleAction(reqPath, body);
                if (respBody != null) {
                    DebugLog.INSTANCE.log("HTTP", "POST " + reqPath + " -> SOAP OK 动作=" + actionName);
                    String fullResp = ManualDlnaHttp.wrapEnvelope(respBody);
                    DebugLog.INSTANCE.log("SOAP", "响应 " + actionName + ": " + fullResp);
                    writeXml(socket.getOutputStream(), fullResp);
                    return;
                }
                // Unknown action -> SOAP fault (500 with fault body)
                DebugLog.INSTANCE.log("HTTP", "POST " + reqPath + " -> SOAP fault (未知动作) action=" + actionName);
                writeXml(socket.getOutputStream(), ManualDlnaHttp.wrapEnvelope(ManualDlnaHttp.faultBody()));
                return;
            }
            // GENA event subscription: answer manually instead of relying on
            // jUPnP's event machinery (Windows Play To reports an "unexpected
            // device error" when the AVTransport subscribe does not complete).
            if (("SUBSCRIBE".equals(request.method) || "UNSUBSCRIBE".equals(request.method))
                    && ManualDlnaHttp.isService(reqPath)) {
                // v122: a GENA subscription belongs to one service URL, and a
                // control point opens AVTransport + RenderingControl +
                // ConnectionManager in one burst. Everything below is keyed on
                // the service; with the old single shared slot the last
                // SUBSCRIBE won and the events a cast was waiting for went out
                // under a different service's SID.
                String service = ManualDlnaHttp.serviceOf(reqPath);
                if ("UNSUBSCRIBE".equals(request.method)) {
                    // Never mint a new SID or re-push events on unsubscribe.
                    //
                    // v122: release only when the presented SID is the one we
                    // handed out for THIS service. A stale or foreign SID used
                    // to tear down the live subscription, which is how one
                    // sender's cleanup could kill another's cast. It is still
                    // answered 200 (as a no-op) rather than 453: senders echo
                    // the SID with enough variation that a hard error reads as
                    // broken eventing, and the failure net in GenaNotifier
                    // eventually reclaims it.
                    String presented = headerValue(request, "SID");
                    String known = GenaNotifier.currentSid(service);
                    boolean mine = presented != null && presented.equals(known);
                    if (mine) {
                        GenaNotifier.unsubscribe(service);
                    }
                    String resp = "HTTP/1.1 200 OK\r\nSERVER: PhairPlay/1.0 UPnP/1.0\r\n"
                            + "CONTENT-LENGTH: 0\r\n\r\n";
                    DebugLog.INSTANCE.log("HTTP", "UNSUBSCRIBE " + service + " -> 200"
                            + (mine ? " 已退订" : "（SID 不匹配，保留 " + known + "）"));
                    socket.getOutputStream().write(resp.getBytes("UTF-8"));
                    socket.getOutputStream().flush();
                    return;
                }
                // GENA renewal: a SUBSCRIBE carrying the current SID must be
                // answered with the SAME SID and must NOT restart eventing
                // (a fresh SID + re-sent SEQ 0 makes control points drop the
                // subscription and abort long casts).
                String existingSid = headerValue(request, "SID");
                if (existingSid != null && !existingSid.isEmpty()
                        && existingSid.equals(GenaNotifier.currentSid(service))) {
                    String resp = "HTTP/1.1 200 OK\r\n"
                            + "SID: " + existingSid + "\r\n"
                            + "TIMEOUT: Second-1800\r\n"
                            + "SERVER: PhairPlay/1.0 UPnP/1.0\r\n"
                            + "CONTENT-LENGTH: 0\r\n\r\n";
                    DebugLog.INSTANCE.log("HTTP", "SUBSCRIBE " + service + " -> 200 续订 sid=" + existingSid);
                    socket.getOutputStream().write(resp.getBytes("UTF-8"));
                    socket.getOutputStream().flush();
                    return;
                }
                String sid = "uuid:" + java.util.UUID.randomUUID().toString();
                String resp = "HTTP/1.1 200 OK\r\n"
                        + "SID: " + sid + "\r\n"
                        + "TIMEOUT: Second-1800\r\n"
                        + "SERVER: PhairPlay/1.0 UPnP/1.0\r\n"
                        + "CONTENT-LENGTH: 0\r\n\r\n";
                DebugLog.INSTANCE.log("HTTP", "SUBSCRIBE " + service + " -> 200 事件订阅(手动) sid=" + sid);
                socket.getOutputStream().write(resp.getBytes("UTF-8"));
                socket.getOutputStream().flush();
                // Remember the subscriber's callback URL and push the initial
                // LastChange event — Windows stalls the cast without it.
                String callback = headerValue(request, "CALLBACK");
                if (callback != null) {
                    int s = callback.indexOf('<');
                    int e2 = callback.indexOf('>');
                    if (s >= 0 && e2 > s) {
                        callback = callback.substring(s + 1, e2).trim();
                    }
                }
                GenaNotifier.register(service, callback, sid);
                return;
            }
            DebugLog.INSTANCE.log("HTTP", request.method + " " + reqPath + " -> 走 jUPnP");

            StreamRequestMessage requestMessage = new StreamRequestMessage(
                    UpnpRequest.Method.getByHttpName(request.method),
                    request.uri);

            if (requestMessage.getOperation().getMethod().equals(UpnpRequest.Method.UNKNOWN)) {
                logger.trace("Method not supported by UPnP stack: {}", request.method);
                throw new RuntimeException("Method not supported: " + request.method);
            }

            requestMessage.getOperation().setHttpMinorVersion(request.http11 ? 1 : 0);

            requestMessage.setConnection(createConnection());

            UpnpHeaders headers = new UpnpHeaders(request.headers);
            requestMessage.setHeaders(headers);

            if (request.body.length > 0 && requestMessage.isContentTypeMissingOrText()) {
                requestMessage.setBodyCharacters(request.body);
            } else if (request.body.length > 0) {
                requestMessage.setBody(UpnpMessage.BodyType.BYTES, request.body);
            }

            StreamResponseMessage responseMessage = process(requestMessage);

            if (responseMessage != null) {
                logger.trace("Preparing HTTP response message: {}", responseMessage);
                writeResponse(socket.getOutputStream(), responseMessage);
            } else {
                logger.trace("Sending HTTP response status: 404");
                writeStatus(socket.getOutputStream(), 404, "Not Found");
            }

            responseSent(responseMessage);

        } catch (HttpRequest.RequestTooLarge tooLarge) {
            // Answered as a status instead of being logged away: the socket is
            // closed in the finally block either way, but a control point that
            // sees a clean 4xx re-issues a normal request rather than retrying
            // whatever it was dribbling.
            logger.warn("Rejecting oversized request from {}: {}",
                    socket.getInetAddress(), tooLarge.getMessage());
            try {
                writeStatus(socket.getOutputStream(), 413, "Request Entity Too Large");
            } catch (IOException ioe) {
                logger.trace("Couldn't send 413: {}", ioe.getMessage());
            }
        } catch (Exception e) {
            logger.trace("Exception occurred during UPnP stream processing", e);
            try {
                writeStatus(socket.getOutputStream(), 500, "Internal Server Error");
            } catch (IOException ioe) {
                logger.warn("Couldn't send error response: {}", ioe.getMessage());
            }
            responseException(e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void writeResponse(OutputStream os, StreamResponseMessage response) throws IOException {
        int status = response.getOperation().getStatusCode();
        String reason = response.getOperation().getStatusMessage();
        if (reason == null || reason.isEmpty()) {
            reason = "OK";
        }

        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");

        byte[] body = response.hasBody() ? response.getBodyBytes() : null;
        head.append("Content-Length: ").append(body != null ? body.length : 0).append("\r\n");

        Map<String, List<String>> responseHeaders = new HashMap<>();
        if (response.getHeaders() != null) {
            responseHeaders.putAll(response.getHeaders());
        }
        responseHeaders.put("Connection", java.util.Collections.singletonList("close"));
        for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Connection")) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values != null) {
                for (String value : values) {
                    head.append(name).append(": ").append(value).append("\r\n");
                }
            }
        }
        head.append("\r\n");

        os.write(head.toString().getBytes("UTF-8"));
        if (body != null && body.length > 0) {
            os.write(body);
        }
        os.flush();
    }

    private void writeStatus(OutputStream os, int status, String reason) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        head.append("Content-Length: 0\r\n");
        head.append("Connection: close\r\n");
        head.append("\r\n");
        os.write(head.toString().getBytes("UTF-8"));
        os.flush();
    }

    /** Case-insensitive single header lookup (headers are stored as received). */
    private static String headerValue(HttpRequest request, String name) {
        if (request.headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : request.headers.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) {
                List<String> vals = e.getValue();
                return (vals == null || vals.isEmpty()) ? null : vals.get(0);
            }
        }
        return null;
    }

    /** Writes a 200 response with an XML body (device descriptor / SCPD / SOAP). */
    private void writeXml(OutputStream os, String xml) throws IOException {
        byte[] body = xml.getBytes("UTF-8");
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: text/xml; charset=\"utf-8\"\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Server: PhairPlay/1.0 UPnP/1.0\r\n");
        head.append("EXT:\r\n");
        head.append("\r\n");
        os.write(head.toString().getBytes("UTF-8"));
        os.write(body);
        os.flush();
    }

    protected Connection createConnection() {
        return new Connection() {
            @Override
            public boolean isOpen() {
                return socket.isConnected() && !socket.isClosed();
            }

            @Override
            public InetAddress getRemoteAddress() {
                return socket.getInetAddress();
            }

            @Override
            public InetAddress getLocalAddress() {
                return socket.getLocalAddress();
            }
        };
    }

    /** A minimal parsed HTTP request. */
    static class HttpRequest {
        /** Longest request/header line kept in memory. */
        private static final int MAX_LINE_BYTES = 8192;
        /** Most header lines kept in memory. */
        private static final int MAX_HEADER_LINES = 100;
        /** Largest body (SOAP envelope) kept in memory. */
        private static final int MAX_BODY_BYTES = 1024 * 1024;

        /**
         * The peer sent more than this server is willing to buffer. Without
         * these caps, one LAN device holding a TCP connection open and dribbling
         * bytes with no line feed grows a ByteArrayOutputStream until the
         * foreground service is killed — and every cast running at the time dies
         * with it. Both cases answer 413 and drop the connection.
         */
        static final class RequestTooLarge extends IOException {
            RequestTooLarge(String what) {
                super("Request " + what + " too large");
            }
        }

        String method;
        URI uri;
        boolean http11;
        Map<String, List<String>> headers = new HashMap<>();
        byte[] body = new byte[0];

        static HttpRequest read(Socket socket) throws IOException {
            InputStream raw = new BufferedInputStream(socket.getInputStream());

            String requestLine = readLine(raw);
            if (requestLine == null || requestLine.isEmpty()) {
                return null;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return null;
            }

            HttpRequest req = new HttpRequest();
            req.method = parts[0].toUpperCase(Locale.ROOT);
            try {
                req.uri = URI.create(parts[1]);
            } catch (IllegalArgumentException e) {
                return null;
            }
            req.http11 = parts.length >= 3
                    && parts[2].toUpperCase(Locale.ROOT).equals("HTTP/1.1");

            long contentLength = -1;
            int headerLines = 0;
            String line;
            while ((line = readLine(raw)) != null && !line.isEmpty()) {
                if (++headerLines > MAX_HEADER_LINES) {
                    throw new RequestTooLarge("headers");
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if (name.equalsIgnoreCase("Content-Length")) {
                        try {
                            contentLength = Long.parseLong(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    req.headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
                }
            }

            if (contentLength > MAX_BODY_BYTES) {
                throw new RequestTooLarge("body");
            }
            if (contentLength > 0) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long remaining = contentLength;
                while (remaining > 0) {
                    int n = raw.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) {
                        break;
                    }
                    bos.write(buf, 0, n);
                    remaining -= n;
                }
                req.body = bos.toByteArray();
            }

            return req;
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    if (bos.size() >= MAX_LINE_BYTES) {
                        throw new RequestTooLarge("line");
                    }
                    bos.write(c);
                }
            }
            if (bos.size() == 0 && c == -1) {
                return null;
            }
            return new String(bos.toByteArray(), "ISO-8859-1");
        }
    }
}
