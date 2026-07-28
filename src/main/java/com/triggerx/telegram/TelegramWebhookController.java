package com.triggerx.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramWebhookController {

    private final TelegramBotService bot;

    @PostMapping("/api/telegram/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody Update update,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken) {

        String expected = bot.getWebhookSecret();
        if (expected != null && !expected.isBlank() && !constantTimeEquals(expected, secretToken)) {
            log.warn("Rejected Telegram webhook call with missing or invalid secret token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        bot.onWebhookUpdateReceived(update);
        return ResponseEntity.ok().build();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
