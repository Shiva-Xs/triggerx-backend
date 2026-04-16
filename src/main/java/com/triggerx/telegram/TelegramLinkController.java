package com.triggerx.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    @PostMapping("/link-token")
    public ResponseEntity<Map<String, String>> generateLinkToken(
            @AuthenticationPrincipal UUID userId) {
        String deepLink = telegramLinkTokenService.generateDeepLink(userId);
        return ResponseEntity.ok(Map.of("deepLink", deepLink));
    }
}
