package com.phairplay.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the media URI a control point hands to SetAVTransportURI
 * (ported from upstream).
 *
 * <p>WHY: the value is untrusted network input and reaches both the media stack
 * and the log, so it is bounded in length and free of control characters. The
 * scheme list is wide on purpose — a renderer that refuses a URI the sender
 * offers is useless — but the address is checked separately by
 * {@link HostAddressPolicy}, because the box fetches the media itself.
 */
public final class MediaUriPolicy {

    /** Longest URI accepted; beyond this the request is refused instead of truncated. */
    public static final int MAX_URI_LENGTH = 4096;

    private static final Set<String> ALLOWED_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "rtsp", "rtmp", "rtmpe", "rtmps", "ftp", "file", "smb",
            "mms", "mmsh", "mmst", "udp", "pnm"
    ));

    private MediaUriPolicy() {
    }

    /**
     * @param uri the raw CurrentURI value of the SOAP action.
     * @return the trimmed URI when it may be used, null when it must be refused.
     *     The refusal reason goes to {@link #rejectReason(String)} for the log.
     */
    public static String accept(String uri) {
        return check(uri) == null ? (uri == null ? "" : uri.trim()) : null;
    }

    /** @return null when the URI is acceptable, otherwise the refusal reason. */
    public static String check(String uri) {
        String trimmed = uri == null ? "" : uri.trim();
        if (trimmed.isEmpty()) {
            return "empty URI";
        }
        if (trimmed.length() > MAX_URI_LENGTH) {
            return "URI too long";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return "URI contains control characters";
            }
        }
        int separator = trimmed.indexOf(':');
        if (separator <= 0) {
            return "URI has no scheme";
        }
        String scheme = trimmed.substring(0, separator).toLowerCase(Locale.US);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '-' || c == '.';
            if (!ok) {
                return "URI scheme is malformed";
            }
        }
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return "unsupported URI scheme";
        }
        return HostAddressPolicy.check(trimmed);
    }
}
