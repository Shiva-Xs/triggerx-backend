package com.triggerx.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    public record TokenResult(String token, ZonedDateTime expiresAt) {}

    private final SecretKey signingKey;
    private final long expirySeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiry.seconds:2592000}") long expirySeconds
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET environment variable is not set. "
                    + "Set it to a random string of at least 32 characters before starting the app.");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be at least 32 characters. Current length: " + secret.length());
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
    }

    public TokenResult generateToken(UUID userId, String email) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirySeconds * 1000L);

        String token = Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();

        ZonedDateTime expiresAt = expiry.toInstant().atZone(ZoneOffset.UTC);
        return new TokenResult(token, expiresAt);
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
