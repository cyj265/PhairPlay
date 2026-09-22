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
            String line;
            while ((line = readLine(raw)) != null && !line.isEmpty()) {
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

            if (contentLength > 0 && contentLength <= 1024 * 1024) {
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
