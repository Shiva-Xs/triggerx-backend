package com.triggerx.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/telegram")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramLinkController {

    private final TelegramLinkTokenService telegramLinkTokenService;
    private final TelegramUserRepository   telegramUserRepository;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> getStatus(
            @AuthenticationPrincipal UUID userId) {
        boolean linked = telegramUserRepository.existsByUserId(userId);
        return ResponseEntity.ok(Map.of("linked", linked));
    }

    @PostMapping("/link-token")
    public ResponseEntity<Map<String, String>> generateLinkToken(
            @AuthenticationPrincipal UUID userId) {
        String deepLink = telegramLinkTokenService.generateDeepLink(userId);
        return ResponseEntity.ok(Map.of("deepLink", deepLink));
    }

    @DeleteMapping("/unlink")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal UUID userId) {
        telegramUserRepository.findByUserId(userId)
                .ifPresent(telegramUserRepository::delete);
        return ResponseEntity.noContent().build();
    }
}
