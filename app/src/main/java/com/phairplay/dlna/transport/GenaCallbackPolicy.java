package com.phairplay.dlna.transport;

import com.phairplay.util.HostAddressPolicy;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * SSRF guard for GENA event callbacks (ported from upstream).
 *
 * <p>The callback URL comes straight from a control point. If the renderer
 * posted events to an arbitrary URL, a hostile device on the LAN could use the
 * TV as a request forwarder into a router's metadata service, into a loopback
 * port of the TV itself, or into another private machine.
 *
 * <p>Rules: only the http scheme; the host must be a literal dotted-quad IPv4
 * address (no DNS names, no decimal/octal/hex notation); only 10/8, 172.16/12
 * and 192.168/16; loopback, link-local, multicast, broadcast, "this network"
 * and every IPv6 form are refused; and the address has to be in the same /24 as
 * one of this box's own IPv4 addresses.
 */
public final class GenaCallbackPolicy {

    private static volatile List<int[]> localOctets = Collections.emptyList();
    private static volatile long localRefreshedAtMs = 0L;
    private static final long LOCAL_TTL_MS = 60_000L;

    private GenaCallbackPolicy() {
    }

    /** @return null when the callback may be used, otherwise the refusal reason. */
    public static String check(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.trim().isEmpty()) {
            return "empty callback URL";
        }
        URI uri;
        try {
            uri = new URI(callbackUrl.trim());
        } catch (Exception e) {
            return "callback URL cannot be parsed";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"http".equals(scheme.toLowerCase(Locale.US))) {
            return "callback scheme is not http";
        }
        if (uri.getUserInfo() != null) {
            return "callback carries a user info part";
        }
        String host = uri.getHost();
        if (host == null) {
            return "callback has no host";
        }
        int[] remote = HostAddressPolicy.parseIpv4Literal(host);
        if (remote == null) {
            return "callback host is not a literal IPv4 address";
        }
        if (!HostAddressPolicy.isPrivate(remote)) {
            return "callback address is not in a private range";
        }
        List<int[]> locals = localAddresses();
        if (locals.isEmpty()) {
            // Nothing to compare against — no interface information at all is a
            // transient state, not a reason to disable eventing entirely.
            return null;
        }
        for (int[] local : locals) {
            if (HostAddressPolicy.isSameSubnet(remote, local)) {
                return null;
            }
        }
        return "callback address is outside this box's subnets";
    }

    private static List<int[]> localAddresses() {
        long now = System.currentTimeMillis();
        if (now - localRefreshedAtMs < LOCAL_TTL_MS) {
            return localOctets;
        }
        List<int[]> found = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        int[] octets = HostAddressPolicy.parseIpv4Literal(address.getHostAddress());
                        if (octets != null && HostAddressPolicy.isPrivate(octets)) {
                            found.add(octets);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through with what we have; the caller treats an empty list as
            // "no subnet information" rather than a refusal.
        }
        localOctets = found;
        localRefreshedAtMs = now;
        return found;
    }
}
