package com.triggerx.notification;

import com.triggerx.alert.AlertCondition;
import com.triggerx.common.EmailUtils;
import com.triggerx.common.TriggerXException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Profile("!dev")
@RequiredArgsConstructor
public class EmailService implements EmailSender {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String mailFrom;

    @Value("${otp.expiry.minutes:10}")
    private int otpExpiryMinutes;

    @Override
    public void sendOtp(String to, String otp) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setTo(to);
            helper.setFrom(mailFrom);
            helper.setSubject(EmailTemplates.otpSubject());
            helper.setText(EmailTemplates.otpText(otp, otpExpiryMinutes), false);

            mailSender.send(message);
            log.info("OTP email sent to {}", EmailUtils.maskEmail(to));

        } catch (MessagingException | MailException e) {
            log.error("Failed to send OTP email to {}: {}", EmailUtils.maskEmail(to), e.getMessage());
            throw TriggerXException.smtpFailed();
        }
    }

    @Override
    public void sendTriggerAlert(String to, String symbol, AlertCondition condition,
                                 BigDecimal targetPrice, BigDecimal triggeredPrice, LocalDateTime triggeredAt) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            String conditionText = condition.actionPhrase();

            helper.setTo(to);
            helper.setFrom(mailFrom);
            helper.setSubject(EmailTemplates.triggerAlertSubject(symbol, conditionText, targetPrice));
            helper.setText(EmailTemplates.triggerAlertHtml(symbol, targetPrice, triggeredPrice, conditionText, triggeredAt), true);

            mailSender.send(message);
            log.info("Trigger email sent to {}", EmailUtils.maskEmail(to));

        } catch (MessagingException | MailException e) {
            log.error("Failed to send trigger email to {}: {}", EmailUtils.maskEmail(to), e.getMessage());
        }
    }

}