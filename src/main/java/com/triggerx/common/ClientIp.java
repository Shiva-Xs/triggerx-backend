package com.triggerx.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller's address for abuse controls.
 *
 * <p>api.triggerx.in is DNS-only, so requests reach App Service directly and Azure appends the
 * real peer as the <em>last</em> X-Forwarded-For entry. Taking the first entry instead would let
 * a caller pick its own bucket by sending the header itself.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) {
                return stripPort(last);
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : stripPort(remote);
    }

    /** Azure includes the source port, e.g. "1.2.3.4:53201". IPv6 arrives bracketed. */
    private static String stripPort(String address) {
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            return close > 0 ? address.substring(1, close) : address;
        }
        int firstColon = address.indexOf(':');
        int lastColon = address.lastIndexOf(':');
        // A bare IPv6 address has several colons and no port; only strip when there is exactly one.
        if (firstColon > -1 && firstColon == lastColon) {
            return address.substring(0, firstColon);
        }
        return address;
    }
}
