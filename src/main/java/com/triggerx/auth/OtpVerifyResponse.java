package com.triggerx.auth;

import java.time.ZonedDateTime;
import java.util.UUID;

public record OtpVerifyResponse(
        UUID userId,
        String email,
        String token,
        ZonedDateTime expiresAt
) {}
