package com.phairplay.util;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Address rules for a URL the receiver fetches itself (ported from upstream).
 *
 * <p>WHY: a sender names the address and the box fetches it. Without an address
 * policy a hostile device on the LAN can aim the TV at the router's cloud
 * metadata service (169.254.169.254) or at a loopback port of the TV itself and
 * read the answer as if the TV had asked for it.
 *
 * <p>Rules: a user-info part is refused (it hides the real host); a literal IPv4
 * address is only accepted in plain dotted-quad notation (decimal/octal/hex
 * forms are refused); loopback, 0/8, link-local, known metadata addresses,
 * multicast and 240/4 are refused; IPv6 loopback/link-local/multicast are
 * refused. Names are not resolved — the known metadata names are refused by
 * name. Everything else is accepted; the owner decides what media to cast.
 */
public final class HostAddressPolicy {

    private static final Set<String> METADATA_NAMES = new HashSet<>(Arrays.asList(
            "metadata",
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "instance-data.ec2.internal"
    ));

    /** A host written only as digits and dots, like "3232235777" or "0177.0.0.1". */
    private static final Pattern DECIMAL_NOTATION = Pattern.compile("^[0-9]+(\\.[0-9]+)*$");

    /** A host written as a hexadecimal number, like "0x7f000001". */
    private static final Pattern HEX_NOTATION = Pattern.compile("^0[xX][0-9a-fA-F]+$");

    /** The IPv4 part of an IPv4-mapped IPv6 address such as ::ffff:127.0.0.1. */
    private static final Pattern MAPPED_IPV4 =
            Pattern.compile("^::ffff:([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})$");

    private HostAddressPolicy() {
    }

    /**
     * @return null when {@code uri} may be fetched, otherwise the refusal reason.
     */
    public static String check(String uri) {
        if (uri == null) {
            return "the URI is empty";
        }
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (Exception e) {
            return "the URI cannot be parsed";
        }
        if (parsed.getUserInfo() != null) {
            return "the URI carries a user info part";
        }
        String host = parsed.getHost();
        if (host == null) {
            // No authority (file:///movie.mp4) means no address to check. An
            // authority that cannot be read (http://3232235777/) is refused.
            return parsed.getRawAuthority() == null ? null : "the URI has no readable host";
        }
        return checkHost(host);
    }

    /** @return null when {@code host} may be used, otherwise the refusal reason. */
    public static String checkHost(String host) {
        if (host == null) {
            return "the URI has no readable host";
        }
        String cleaned = host.trim().toLowerCase(Locale.US);
        if (cleaned.startsWith("[")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            return "the URI has no readable host";
        }
        int[] octets = parseIpv4Literal(cleaned);
        if (octets != null) {
            return checkIpv4(octets);
        }
        if (DECIMAL_NOTATION.matcher(cleaned).matches()) {
            return "the address uses a non-standard notation";
        }
        if (HEX_NOTATION.matcher(cleaned).matches()) {
            return "the address uses a non-standard notation";
        }
        if (cleaned.indexOf(':') >= 0) {
            return checkIpv6(cleaned);
        }
        return METADATA_NAMES.contains(cleaned) ? "the host is a metadata service" : null;
    }

    /**
     * Parses a strict dotted-quad IPv4 literal.
     *
     * @return the four octets, or null when {@code host} is not a plain decimal
     *     dotted quad. A leading zero is refused: resolvers treat "0177.0.0.1"
     *     as octal, so a literal must never mean something else than it looks.
     */
    public static int[] parseIpv4Literal(String host) {
        if (host == null) {
            return null;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return null;
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return null;
            }
            octets[i] = value;
        }
        return octets;
    }

    /** True for 10/8, 172.16/12 and 192.168/16. */
    public static boolean isPrivate(int[] octets) {
        if (octets == null || octets.length != 4) {
            return false;
        }
        int first = octets[0];
        int second = octets[1];
        return first == 10
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }

    /** True when both addresses are in the same /24. */
    public static boolean isSameSubnet(int[] left, int[] right) {
        return left != null && right != null && left.length == 4 && right.length == 4
                && left[0] == right[0] && left[1] == right[1] && left[2] == right[2];
    }

    private static String checkIpv4(int[] octets) {
        int first = octets[0];
        int second = octets[1];
        if (first == 127) {
            return "the address is a loopback address";
        }
        if (first == 0) {
            return "the address belongs to the reserved range 0.0.0.0/8";
        }
        if (first == 169 && second == 254) {
            return "the address is a link-local address";
        }
        if (first == 100 && second == 100 && octets[2] == 100 && octets[3] == 200) {
            return "the address is a cloud metadata address";
        }
        if (first == 192 && second == 0 && octets[2] == 0 && octets[3] == 192) {
            return "the address is a cloud metadata address";
        }
        if (first >= 224 && first <= 239) {
            return "the address is a multicast address";
        }
        if (first >= 240) {
            return "the address is in the reserved range 240.0.0.0/4";
        }
        return null;
    }

    private static String checkIpv6(String address) {
        java.util.regex.Matcher mapped = MAPPED_IPV4.matcher(address);
        if (mapped.matches()) {
            int[] octets = parseIpv4Literal(mapped.group(1));
            if (octets != null) {
                return checkIpv4(octets);
            }
        }
        if ("::1".equals(address)) {
            return "the address is a loopback address";
        }
        if ("::".equals(address)) {
            return "the address is unspecified";
        }
        if (address.startsWith("fe80:")) {
            return "the address is a link-local address";
        }
        if (address.startsWith("ff")) {
            return "the address is a multicast address";
        }
        if ("fd00:ec2::254".equals(address)) {
            return "the address is a cloud metadata address";
        }
        return null;
    }
}
