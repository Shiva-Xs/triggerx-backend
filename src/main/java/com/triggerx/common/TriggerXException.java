package com.triggerx.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

@Getter
public class TriggerXException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;
    private final Integer attemptsRemaining;
    private final long retryAfterSeconds;

    private TriggerXException(String errorCode, String message, HttpStatus status,
                               Integer attemptsRemaining, long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.attemptsRemaining = attemptsRemaining;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private TriggerXException(String errorCode, String message, HttpStatus status) {
        this(errorCode, message, status, null, 0);
    }

    public static TriggerXException duplicateAlert(String symbol, String condition,
                                                    BigDecimal price) {
        return new TriggerXException("DUPLICATE_ALERT",
                "You already have an active alert for " + symbol
                        + " " + condition.toLowerCase() + " " + price.toPlainString(),
                HttpStatus.CONFLICT);
    }

    public static TriggerXException alertLimitReached(int limit) {
        return new TriggerXException("ALERT_LIMIT_REACHED",
                "Maximum " + limit + " active alerts allowed",
                HttpStatus.BAD_REQUEST);
    }

    public static TriggerXException forbidden() {
        return new TriggerXException("FORBIDDEN",
                "You do not own this alert",
                HttpStatus.FORBIDDEN);
    }

    public static TriggerXException accountDeleted() {
        return new TriggerXException("ACCOUNT_NOT_FOUND",
                "This account no longer exists",
                HttpStatus.FORBIDDEN);
    }

    public static TriggerXException alertNotFound() {
        return new TriggerXException("NOT_FOUND",
                "Alert not found",
                HttpStatus.NOT_FOUND);
    }

    public static TriggerXException unsupportedSymbol(String symbol) {
        return new TriggerXException("UNSUPPORTED_SYMBOL",
                "Symbol '" + symbol.toUpperCase() + "' is not supported. "
                        + "Use GET /api/v1/symbols/search to find valid symbols.",
                HttpStatus.BAD_REQUEST);
    }

    public static TriggerXException rateLimited(long retryAfterSeconds) {
        return new TriggerXException("RATE_LIMITED",
                "Too many OTP requests. Try again later",
                HttpStatus.TOO_MANY_REQUESTS, null, retryAfterSeconds);
    }

    public static TriggerXException otpExpired() {
        return new TriggerXException("OTP_EXPIRED",
                "OTP has expired. Please request a new one",
                HttpStatus.GONE);
    }

    public static TriggerXException maxAttemptsReached() {
        return new TriggerXException("MAX_ATTEMPTS_REACHED",
                "Too many wrong attempts. Please request a new OTP",
                HttpStatus.TOO_MANY_REQUESTS);
    }

    public static TriggerXException invalidOtp(int attemptsRemaining) {
        return new TriggerXException("INVALID_OTP", "Incorrect OTP",
                HttpStatus.BAD_REQUEST, attemptsRemaining, 0);
    }

    public static TriggerXException invalidOtpGeneric() {
        return new TriggerXException("INVALID_OTP", "Invalid OTP",
                HttpStatus.BAD_REQUEST);
    }

    public static TriggerXException smtpFailed() {
        return new TriggerXException("EMAIL_SEND_FAILED",
                "Could not send OTP email right now. Please try again in a moment.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static TriggerXException aiNotConfigured() {
        return new TriggerXException("AI_NOT_CONFIGURED",
                "Natural language alerts require GROQ_API_KEY. Set it and restart the app.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static TriggerXException aiUnavailable() {
        return new TriggerXException("AI_UNAVAILABLE",
                "Could not process your request right now. Use the standard alert form instead.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static TriggerXException noLivePrice(String symbol) {
        return new TriggerXException("NO_LIVE_PRICE",
                "No live price data for '" + symbol.toUpperCase() + "' yet. Try again in a moment.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static TriggerXException parseFailed(String message) {
        return new TriggerXException("PARSE_FAILED", message, HttpStatus.BAD_REQUEST);
    }
}
