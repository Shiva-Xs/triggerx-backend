package com.triggerx.extension;

import com.triggerx.auth.JwtService;
import com.triggerx.auth.OtpVerifyResponse;
import com.triggerx.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtensionAuthService {

    private static final int TTL_SECONDS = 120;

    private final UserRepository userRepository;
    private final JwtService jwtService;

    private record PendingExchange(UUID userId, Instant expiresAt) {}

    private final Map<String, PendingExchange> tokens = new ConcurrentHashMap<>();

    public String generateExchangeToken(UUID userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new PendingExchange(userId, Instant.now().plusSeconds(TTL_SECONDS)));
        log.debug("Extension exchange token generated for userId={}", userId);
        return token;
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredTokens() {
        tokens.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue().expiresAt()));
    }

    public Optional<OtpVerifyResponse> redeemToken(String token) {
        PendingExchange exchange = tokens.remove(token);
        if (exchange == null) return Optional.empty();
        if (Instant.now().isAfter(exchange.expiresAt())) {
            log.debug("Extension exchange token expired for token={}", token);
            return Optional.empty();
        }
        return userRepository.findById(exchange.userId()).map(user -> {
            JwtService.TokenResult tokenResult = jwtService.generateToken(user.getId(), user.getEmail());
            log.info("Extension exchange token redeemed for userId={}", user.getId());
            return new OtpVerifyResponse(user.getId(), user.getEmail(), tokenResult.token(), tokenResult.expiresAt());
        });
    }
}
