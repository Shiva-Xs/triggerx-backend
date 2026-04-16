package com.triggerx.common;

public final class EmailUtils {

    private EmailUtils() {}

    public static String maskEmail(String email) {
        if (email == null) return "***";
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "*".repeat(at - 1) + email.substring(at);
    }
}
