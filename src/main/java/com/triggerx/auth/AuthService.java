package com.triggerx.auth;

import com.triggerx.common.TriggerXException;
import com.triggerx.common.EmailUtils;
import com.triggerx.notification.EmailSender;
import com.triggerx.user.User;
import com.triggerx.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OtpTokenRepository otpTokenRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final EmailSender emailSender;

    @Value("${otp.expiry.minutes:10}")
    private int otpExpiryMinutes;

    @Value("${otp.max.attempts:5}")
    private int otpMaxAttempts;

    @Value("${otp.rate.limit.per.hour:10}")
    private int otpRateLimitPerHour;

    @Value("${otp.rate.limit.per.ip.per.hour:30}")
    private int otpRateLimitPerIpPerHour;

    /**
     * Per-address limiting alone does not stop abuse: one host could ask for codes for thousands
     * of different addresses and send that mail from our account, which is how a Gmail app
     * password gets flagged. Kept in memory deliberately - this is a throttle, not an audit
     * trail, and a restart losing it is cheaper than a schema migration on the hot path.
     */
    private final Map<String, Deque<Long>> otpHitsByIp = new ConcurrentHashMap<>();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Sliding one-hour window per address. Prunes as it goes, so no sweeper is needed. */
    private boolean allowForIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return true;
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        Deque<Long> hits = otpHitsByIp.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst() < cutoff) hits.pollFirst();
            if (hits.size() >= otpRateLimitPerIpPerHour) {
                log.warn("OTP request rate limit hit for ip={}", clientIp);
                return false;
            }
            hits.addLast(System.currentTimeMillis());
        }
        if (otpHitsByIp.size() > 10_000) {
            otpHitsByIp.entrySet().removeIf(e -> {
                synchronized (e.getValue()) { return e.getValue().isEmpty(); }
            });
        }
        return true;
    }

    public int getOtpExpirySeconds() {
        return otpExpiryMinutes * 60;
    }

    @Transactional
    public void sendOtp(String rawEmail, String clientIp) {
        String email = rawEmail.toLowerCase().trim();
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        long recentCount = otpTokenRepository.countByEmailSince(email, oneHourAgo);

        if (recentCount >= otpRateLimitPerHour) {
            throw TriggerXException.rateLimited(3600L);
        }
        if (!allowForIp(clientIp)) {
            throw TriggerXException.rateLimited(3600L);
        }

        otpTokenRepository.invalidateAllActiveForEmail(email, LocalDateTime.now());

        String rawOtp  = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        String otpHash = sha256(rawOtp + "|" + email);

        OtpToken token = new OtpToken();
        token.setEmail(email);
        token.setOtpHash(otpHash);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(otpExpiryMinutes));
        otpTokenRepository.save(token);

        emailSender.sendOtp(email, rawOtp);

        log.info("OTP sent to {}", EmailUtils.maskEmail(email));
    }

    @Transactional(noRollbackFor = TriggerXException.class)
    public OtpVerifyResponse verifyOtp(String rawEmail, String rawOtp) {
        String email = rawEmail.toLowerCase().trim();

        OtpToken otpToken = otpTokenRepository
                .findFirstByEmailAndInvalidatedAtIsNullOrderByCreatedAtDesc(email)
                .orElseThrow(TriggerXException::invalidOtpGeneric);

        if (otpToken.isExpired()) {
            otpToken.setInvalidatedAt(LocalDateTime.now());
            otpTokenRepository.save(otpToken);
            throw TriggerXException.otpExpired();
        }

        if (otpToken.getAttempts() >= otpMaxAttempts) {
            otpToken.setInvalidatedAt(LocalDateTime.now());
            otpTokenRepository.save(otpToken);
            throw TriggerXException.maxAttemptsReached();
        }

        String incomingHash = sha256(rawOtp + "|" + email);
        if (!MessageDigest.isEqual(
                incomingHash.getBytes(StandardCharsets.UTF_8),
                otpToken.getOtpHash().getBytes(StandardCharsets.UTF_8))) {
            otpToken.setAttempts(otpToken.getAttempts() + 1);
            otpTokenRepository.save(otpToken);
            int remaining = otpMaxAttempts - otpToken.getAttempts();
            throw TriggerXException.invalidOtp(remaining);
        }

        otpToken.setInvalidatedAt(LocalDateTime.now());
        otpTokenRepository.save(otpToken);

        User user;
        try {
            // saveAndFlush forces an immediate INSERT flush within the current transaction,
            // so DataIntegrityViolationException is thrown right here instead of at commit
            // time — making it catchable. With plain save(), Hibernate batches the INSERT
            // until the transaction commits, after this try-catch has already exited.
            user = userRepository.findByEmail(email)
                    .orElseGet(() -> userRepository.saveAndFlush(new User(email)));
        } catch (DataIntegrityViolationException e) {
            // Concurrent first-login race: another request won the INSERT — retry the read.
            user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalStateException("User vanished after constraint violation"));
        }

        JwtService.TokenResult tokenResult = jwtService.generateToken(user.getId(), user.getTokenVersion());

        log.info("OTP verified — user {} authenticated", user.getId());

        return new OtpVerifyResponse(user.getId(), user.getEmail(), tokenResult.token(), tokenResult.expiresAt());
    }

    @Transactional
    public void logoutEverywhere(UUID userId) {
        userRepository.bumpTokenVersion(userId);
        log.info("Sessions revoked for user {}", userId);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

}
