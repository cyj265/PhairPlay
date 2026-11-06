package com.phairplay.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Validates a media URI that a peer hands to the receiver (ported from upstream).
 *
 * <p>WHY: the receiver fetches the URI itself, so the value is untrusted network
 * input that reaches both the media stack and the log. It is bounded in length,
 * free of control characters, restricted to HTTP(S) and its target address is
 * checked by {@link HostAddressPolicy}.
 *
 * <p>WHY THIS CLASS IS THE ONLY PLACE: both protocol stacks let a peer name the
 * address the receiver then opens — AirPlay through {@code POST /play}, DLNA
 * through {@code SetAVTransportURI}. Two scheme lists would be two truths, so
 * both paths call this one rule. The scheme list was narrowed to {@code http}
 * and {@code https} in the upstream security cycle: {@code file}, {@code smb},
 * {@code ftp} and {@code rtsp} either read the local file system or reach a
 * protocol the receiver does not speak. A control point that offers one of them
 * gets {@code 402 Invalid Args} instead of a playback attempt — the trade-off is
 * recorded upstream in {@code docs/security/FINDINGS.md}.
 */
public final class MediaUriPolicy {

    /** Longest URI accepted; beyond this the request is refused instead of truncated. */
    public static final int MAX_URI_LENGTH = 4096;

    private static final Set<String> ALLOWED_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https"
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
