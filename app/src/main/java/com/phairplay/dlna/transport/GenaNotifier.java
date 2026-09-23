package com.phairplay.dlna.transport;

import com.phairplay.util.DebugLog;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

/**
 * Minimal GENA event pusher: sends an initial LastChange NOTIFY right after a
 * SUBSCRIBE succeeds, and on transport-state changes. Windows Play To waits
 * for this initial event before it considers the renderer ready and pushes
 * media, so without it the cast silently stalls.
 */
public final class GenaNotifier {

    private static volatile String callbackUrl;
    private static volatile String sid;

    private GenaNotifier() {
    }

    /** Remember the subscriber and push the initial LastChange shortly after. */
    public static synchronized void register(String callback, String newSid) {
        if (callback != null) {
            callbackUrl = callback;
        }
        sid = newSid;
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

    /** Push current LastChange (called after subscribe and on state changes). */
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
            String body = "<?xml version=\"1.0\"?>\n"
                    + "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">\n"
                    + "<e:property>\n"
                    + "<LastChange>&lt;Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"&gt;&lt;InstanceID val=\"0\"&gt;&lt;TransportState val=\""
                    + state + "\"/&gt;&lt;/InstanceID&gt;&lt;/Event&gt;</LastChange>\n"
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
                    + "SEQ: 0\r\n"
                    + "CONTENT-LENGTH: " + body.getBytes("UTF-8").length + "\r\n\r\n";
            os.write((head + body).getBytes("UTF-8"));
            os.flush();
            sock.close();
            DebugLog.INSTANCE.log("SOAP", "事件推送 LastChange -> " + host + ":" + port + " state=" + state);
        } catch (Throwable t) {
            DebugLog.INSTANCE.log("SOAP", "事件推送失败 " + t.getMessage());
        }
    }
}
