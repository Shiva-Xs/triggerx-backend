package com.triggerx.alert;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        String symbol,
        BigDecimal targetPrice,
        AlertCondition condition,
        AlertStatus status,
        LocalDateTime createdAt,
        LocalDateTime triggeredAt,
        BigDecimal triggeredPrice
) {

    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getSymbol(),
                alert.getTargetPrice(),
                alert.getCondition(),
                alert.getStatus(),
                alert.getCreatedAt(),
                alert.getTriggeredAt(),
                alert.getTriggeredPrice()
        );
    }
}
