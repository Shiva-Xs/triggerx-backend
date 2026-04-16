package com.triggerx.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramLinkTokenService {

    private static final int TTL_MINUTES = 5;

    @Value("${telegram.bot.username:TriggerXBot}")
    private String botUsername;

    private record PendingLink(UUID userId, Instant expiresAt) {}

    private final Map<String, PendingLink> tokens = new ConcurrentHashMap<>();

    public String generateDeepLink(UUID userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new PendingLink(userId, Instant.now().plusSeconds(TTL_MINUTES * 60L)));
        log.debug("Telegram link token generated for userId={}", userId);
        return "https://t.me/" + botUsername + "?start=link_" + token;
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredTokens() {
        tokens.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue().expiresAt()));
    }

    public Optional<UUID> resolveToken(String token) {
        PendingLink link = tokens.get(token);
        if (link == null) return Optional.empty();
        if (Instant.now().isAfter(link.expiresAt())) {
            tokens.remove(token);
            log.debug("Telegram link token expired");
            return Optional.empty();
        }
        tokens.remove(token);
        log.debug("Telegram link token resolved for userId={}", link.userId());
        return Optional.of(link.userId());
    }
}
