package com.triggerx.common;

import com.triggerx.alert.AlertCondition;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AlertFiredEvent(
        UUID userId,
        UUID alertId,
        String userEmail,
        String symbol,
        AlertCondition condition,
        BigDecimal targetPrice,
        BigDecimal triggeredPrice,
        LocalDateTime triggeredAt
) {}
