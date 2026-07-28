package com.triggerx.notification;

import com.triggerx.alert.AlertCondition;
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
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm 'UTC'");

    // ── OTP ───────────────────────────────────────────────────────────────────

    public static String otpSubject() {
        return "Your TriggerX verification code";
    }

    public static String otpHtml(String otp, int expiryMinutes) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1.0">
                  <title>TriggerX verification code</title>
                </head>
                <body style="margin:0;padding:0;background:#04040A;-webkit-text-size-adjust:100%%;-ms-text-size-adjust:100%%;">
                <table role="presentation" border="0" cellpadding="0" cellspacing="0" width="100%%"
                       style="background:#04040A;width:100%%;">
                  <tr>
                    <td align="center" style="padding:40px 16px;">
                      <table role="presentation" border="0" cellpadding="0" cellspacing="0" width="480"
                             style="width:100%%;max-width:480px;background:#0d0d1a;
                                    border-radius:8px;border:1px solid rgba(79,91,213,0.25);">
                        <tr>
                          <td height="3" style="background:#4F5BD5;font-size:0;line-height:0;">&nbsp;</td>
                        </tr>
                        <tr>
                          <td style="padding:24px 32px 0 32px;font-family:Arial,Helvetica,sans-serif;">
                            <span style="color:#ffffff;font-size:14px;font-weight:700;letter-spacing:3px;">TRIGGERX</span>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:22px 32px 28px 32px;font-family:Arial,Helvetica,sans-serif;">
                            <p style="margin:0 0 6px 0;font-size:11px;font-weight:700;color:#4F5BD5;
                                      text-transform:uppercase;letter-spacing:1.5px;">Verification Code</p>
                            <p style="margin:0 0 24px 0;font-size:14px;color:#94a3b8;line-height:1.6;">
                              Enter the code below to sign in. It expires in %d minutes.
                            </p>
                            <table role="presentation" border="0" cellpadding="0" cellspacing="0"
                                   align="center" style="margin:0 auto 24px auto;">
                              <tr>
                                <td align="center" style="background:#04040A;border:1px solid rgba(79,91,213,0.40);
                                           border-radius:6px;padding:18px 36px;">
                                  <span style="display:block;font-family:'Courier New',Courier,monospace;
                                               font-size:36px;font-weight:700;letter-spacing:10px;
                                               color:#7E8FFF;white-space:nowrap;">%s</span>
                                </td>
                              </tr>
                            </table>
                            <p style="margin:0;font-size:12px;color:#64748b;line-height:1.7;">
                              Do not share this code with anyone.<br>
                              If you did not request this, you can safely ignore this email.
                            </p>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:14px 32px;border-top:1px solid rgba(255,255,255,0.06);
                                     font-family:Arial,Helvetica,sans-serif;">
                            <p style="margin:0;color:#475569;font-size:11px;">
                              triggerx.in &nbsp;&middot;&nbsp; Real-time crypto price alerts
                            </p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(expiryMinutes, otp);
    }

    // ── Trigger alert ─────────────────────────────────────────────────────────

    public static String triggerAlertSubject(String symbol, String conditionText, BigDecimal targetPrice) {
        return symbol + " " + conditionText + " $" + fmt(targetPrice);
    }

    public static String triggerAlertHtml(String symbol, BigDecimal targetPrice,
                                          BigDecimal triggeredPrice, AlertCondition condition,
                                          String conditionText, LocalDateTime triggeredAt) {
        String formattedTime = triggeredAt.format(DATE_FORMAT);
        String accentColor = switch (condition) {
            case ABOVE   -> "#22c55e";
            case BELOW   -> "#ef4444";
            case CROSSES -> "#f59e0b";
        };
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1.0">
                  <title>TriggerX Alert Fired</title>
                </head>
                <body style="margin:0;padding:0;background:#04040A;-webkit-text-size-adjust:100%%;-ms-text-size-adjust:100%%;">
                <table role="presentation" border="0" cellpadding="0" cellspacing="0" width="100%%"
                       style="background:#04040A;width:100%%;">
                  <tr>
                    <td align="center" style="padding:40px 16px;">
                      <table role="presentation" border="0" cellpadding="0" cellspacing="0" width="520"
                             style="width:100%%;max-width:520px;background:#0d0d1a;
                                    border-radius:8px;border:1px solid rgba(79,91,213,0.20);">
                        <tr>
                          <td height="3" style="background:%s;font-size:0;line-height:0;">&nbsp;</td>
                        </tr>
                        <tr>
                          <td style="background:#04040A;padding:20px 32px;
                                     border-bottom:1px solid rgba(255,255,255,0.06);
                                     font-family:Arial,Helvetica,sans-serif;">
                            <span style="color:#ffffff;font-size:14px;font-weight:700;letter-spacing:3px;">TRIGGERX</span>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:32px 32px 28px 32px;font-family:Arial,Helvetica,sans-serif;">
                            <p style="margin:0 0 4px 0;font-size:11px;font-weight:700;color:#4F5BD5;
                                      text-transform:uppercase;letter-spacing:1.5px;">Alert Fired</p>
                            <h1 style="margin:0 0 28px 0;font-size:21px;font-weight:700;color:#f1f5f9;line-height:1.3;">
                              %s %s $%s
                            </h1>
                            <table role="presentation" border="0" cellpadding="0" cellspacing="0" width="100%%"
                                   style="width:100%%;border:1px solid rgba(255,255,255,0.08);
                                          border-radius:6px;overflow:hidden;font-size:14px;
                                          font-family:Arial,Helvetica,sans-serif;">
                              <tr style="background:#0a0a14;">
                                <td style="padding:12px 16px;color:#64748b;width:44%%;
                                           border-bottom:1px solid rgba(255,255,255,0.06);">Symbol</td>
                                <td style="padding:12px 16px;color:#e2e8f0;font-weight:600;
                                           border-bottom:1px solid rgba(255,255,255,0.06);">%s</td>
                              </tr>
                              <tr>
                                <td style="padding:12px 16px;color:#64748b;
                                           border-bottom:1px solid rgba(255,255,255,0.06);">Your target</td>
                                <td style="padding:12px 16px;color:#e2e8f0;
                                           border-bottom:1px solid rgba(255,255,255,0.06);">$%s</td>
                              </tr>
                              <tr style="background:#0a0a14;">
                                <td style="padding:12px 16px;color:#64748b;
                                           border-bottom:1px solid rgba(255,255,255,0.06);">Triggered at</td>
                                <td style="padding:12px 16px;color:#e2e8f0;font-weight:600;
                                           border-bottom:1px solid rgba(255,255,255,0.06);">$%s</td>
                              </tr>
                              <tr>
                                <td style="padding:12px 16px;color:#64748b;">Time</td>
                                <td style="padding:12px 16px;color:#e2e8f0;">%s</td>
                              </tr>
                            </table>
                            <table role="presentation" border="0" cellpadding="0" cellspacing="0" width="100%%"
                                   style="width:100%%;margin-top:24px;">
                              <tr>
                                <td style="padding-bottom:10px;">
                                  <a href="https://triggerx.in/dashboard" target="_blank"
                                     style="display:block;padding:12px 22px;color:#ffffff;
                                            background:#4F5BD5;border-radius:6px;font-size:13px;
                                            font-weight:600;text-decoration:none;text-align:center;
                                            font-family:Arial,Helvetica,sans-serif;">
                                    View on TriggerX &#8594;
                                  </a>
                                </td>
                              </tr>
                              <tr>
                                <td>
                                  <a href="https://www.tradingview.com/chart/?symbol=BINANCE:%sUSDT"
                                     target="_blank"
                                     style="display:block;padding:12px 22px;color:#7E8FFF;
                                            border:1px solid rgba(79,91,213,0.40);border-radius:6px;
                                            font-size:13px;font-weight:600;text-decoration:none;
                                            text-align:center;font-family:Arial,Helvetica,sans-serif;">
                                    View Chart &#8594;
                                  </a>
                                </td>
                              </tr>
                            </table>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:16px 32px;border-top:1px solid rgba(255,255,255,0.06);
                                     font-family:Arial,Helvetica,sans-serif;">
                            <p style="margin:0;color:#475569;font-size:11px;line-height:1.7;">
                              This alert has been deactivated. You won&apos;t receive further notifications for it.<br>
                              Set a new alert at <a href="https://triggerx.in"
                                style="color:#7E8FFF;text-decoration:none;">triggerx.in</a>
                              &nbsp;&middot;&nbsp; Not financial advice
                            </p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(
                accentColor,
                symbol, conditionText, fmt(targetPrice),
                symbol,
                fmt(targetPrice),
                fmt(triggeredPrice),
                formattedTime,
                symbol);
    }
}
