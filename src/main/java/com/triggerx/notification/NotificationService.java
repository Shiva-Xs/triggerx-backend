package com.triggerx.notification;

import com.triggerx.common.AlertFiredEvent;
import com.triggerx.telegram.TelegramBotService;
import com.triggerx.telegram.TelegramUser;
import com.triggerx.telegram.TelegramUserRepository;
import com.triggerx.telegram.TelegramUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final TelegramUserRepository telegramUserRepository;
    private final EmailSender emailSender;
    private final Optional<TelegramBotService> telegramBot;

    @Async
    // fallbackExecution=true: AlertFiredEvent is published from WebSocket callback with no transaction
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlertFired(AlertFiredEvent event) {
        try {
            sendEmail(event);

            telegramBot.ifPresent(bot ->
                    telegramUserRepository.findByUserId(event.userId())
                            .map(TelegramUser::getChatId)
                            .ifPresent(chatId -> {
                                String text = String.format(
                                        "🔔 *%s* %s %s\n\nTriggered at: %s\n\nAlert deactivated\\.",
                                        TelegramUtils.escapeMd(event.symbol()),
                                        event.condition().actionPhrase(),
                                        TelegramUtils.escapeMd(fmt(event.targetPrice())),
                                        TelegramUtils.escapeMd(fmt(event.triggeredPrice())));
                                bot.sendWithButtons(chatId, text);
                                log.info("Telegram notification sent to chatId={} for alert {}", chatId, event.alertId());
                            })
            );
        } catch (Exception e) {
            log.error("NotificationService failed for alert {}: {}", event.alertId(), e.getMessage(), e);
        }
    }

    private static String fmt(BigDecimal p) {
        if (p.compareTo(BigDecimal.ONE) >= 0) return "$" + String.format("%,.2f", p);
        return "$" + p.stripTrailingZeros().toPlainString();
    }

    private void sendEmail(AlertFiredEvent event) {
        try {
            emailSender.sendTriggerAlert(
                    event.userEmail(), event.symbol(), event.condition(),
                    event.targetPrice(), event.triggeredPrice(), event.triggeredAt());
            log.info("Email notification sent to {} for alert {}", event.userEmail(), event.alertId());
        } catch (Exception e) {
            log.error("Email notification failed for alert {}: {}", event.alertId(), e.getMessage());
        }
    }
}