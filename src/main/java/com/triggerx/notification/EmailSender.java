package com.triggerx.notification;

import com.triggerx.alert.AlertCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface EmailSender {

    void sendOtp(String to, String otp);

    void sendTriggerAlert(String to, String symbol, AlertCondition condition,
                          BigDecimal targetPrice, BigDecimal triggeredPrice, LocalDateTime triggeredAt);

    @Slf4j
    @Service
    @Profile("dev")
    class DevEmailSender implements EmailSender {

        @Override
        public void sendOtp(String to, String otp) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("  [DEV] OTP for {} → {}", to, otp);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }

        @Override
        public void sendTriggerAlert(String to, String symbol, AlertCondition condition,
                                     BigDecimal targetPrice, BigDecimal triggeredPrice, LocalDateTime triggeredAt) {
            log.info("[DEV] Alert triggered → {} | {} {} {} | hit at {} @ {}",
                    to, symbol, condition, targetPrice.toPlainString(),
                    triggeredPrice.toPlainString(), triggeredAt);
        }
    }
}
