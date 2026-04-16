package com.triggerx.notification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class EmailTemplates {

    private EmailTemplates() {}

    private static String fmt(BigDecimal p) {
        if (p.compareTo(BigDecimal.ONE) >= 0) return String.format("%,.2f", p);
        return p.stripTrailingZeros().toPlainString();
    }

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss");

    public static String otpSubject() {
        return "Your TriggerX verification code";
    }

    public static String otpText(String otp, int expiryMinutes) {
        return "Your TriggerX verification code:\n\n" +
               otp + "\n\n" +
               "This code expires in " + expiryMinutes + " minutes.\n" +
               "Do not share it with anyone.\n\n" +
               "If you did not request this, you can safely ignore this email.";
    }

    public static String triggerAlertSubject(String symbol, String conditionText, BigDecimal targetPrice) {
        return "\uD83D\uDD14 " + symbol + " " + conditionText + " $" + fmt(targetPrice);
    }

    public static String triggerAlertHtml(String symbol, BigDecimal targetPrice,
                                          BigDecimal triggeredPrice, String conditionText,
                                          LocalDateTime triggeredAt) {
        String formattedTime = triggeredAt.format(DATE_FORMAT);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1.0">
                  <title>TriggerX Alert Fired</title>
                </head>
                <body style="margin:0;padding:0;background:#f1f5f9;font-family:Arial,Helvetica,sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                       style="background:#f1f5f9;padding:40px 16px;">
                  <tr><td align="center">
                    <table role="presentation" width="520" cellpadding="0" cellspacing="0"
                           style="background:#ffffff;border-radius:8px;overflow:hidden;
                                  box-shadow:0 2px 8px rgba(0,0,0,0.08);">
                      <tr>
                        <td style="background:#0f172a;padding:20px 32px;">
                          <span style="color:#ffffff;font-size:18px;font-weight:700;
                                       letter-spacing:0.3px;">TriggerX</span>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:32px 32px 24px;">
                          <p style="margin:0 0 4px;font-size:12px;font-weight:700;color:#6366f1;
                                    text-transform:uppercase;letter-spacing:1px;">Alert Fired</p>
                          <h1 style="margin:0 0 24px;font-size:22px;font-weight:700;color:#0f172a;">
                            %s %s $%s
                          </h1>
                          <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                                 style="border:1px solid #e2e8f0;border-radius:6px;
                                        overflow:hidden;font-size:14px;">
                            <tr style="background:#f8fafc;">
                              <td style="padding:12px 16px;color:#64748b;width:44%%;
                                         border-bottom:1px solid #e2e8f0;">Symbol</td>
                              <td style="padding:12px 16px;color:#0f172a;font-weight:600;
                                         border-bottom:1px solid #e2e8f0;">%s</td>
                            </tr>
                            <tr>
                              <td style="padding:12px 16px;color:#64748b;
                                         border-bottom:1px solid #e2e8f0;">Your target</td>
                              <td style="padding:12px 16px;color:#0f172a;
                                         border-bottom:1px solid #e2e8f0;">$%s</td>
                            </tr>
                            <tr style="background:#f8fafc;">
                              <td style="padding:12px 16px;color:#64748b;
                                         border-bottom:1px solid #e2e8f0;">Triggered at</td>
                              <td style="padding:12px 16px;color:#0f172a;font-weight:600;
                                         border-bottom:1px solid #e2e8f0;">$%s</td>
                            </tr>
                            <tr>
                              <td style="padding:12px 16px;color:#64748b;">Time (UTC)</td>
                              <td style="padding:12px 16px;color:#0f172a;">%s</td>
                            </tr>
                          </table>
                          <table role="presentation" cellpadding="0" cellspacing="0"
                                 style="margin-top:28px;">
                            <tr>
                              <td style="background:#2563eb;border-radius:6px;">
                                <a href="https://www.tradingview.com/chart/?symbol=BINANCE:%sUSDT"
                                   target="_blank"
                                   style="display:inline-block;padding:12px 24px;color:#ffffff;
                                          font-size:14px;font-weight:600;text-decoration:none;">
                                  View Chart on TradingView &#8594;
                                </a>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:20px 32px;border-top:1px solid #e2e8f0;
                                   background:#f8fafc;">
                          <p style="margin:0;color:#94a3b8;font-size:12px;line-height:1.6;">
                            This alert has been deactivated. You will not receive further
                            notifications for it. You can set a new alert from the TriggerX dashboard.
                          </p>
                        </td>
                      </tr>
                    </table>
                  </td></tr>
                </table>
                </body>
                </html>
                """.formatted(
                symbol, conditionText, fmt(targetPrice),
                symbol,
                fmt(targetPrice),
                fmt(triggeredPrice),
                formattedTime,
                symbol);
    }
}
