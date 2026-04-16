package com.triggerx.price;

import com.triggerx.alert.AlertRepository;
import com.triggerx.alert.AlertStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final BinanceWebSocketService binanceWebSocketService;
    private final AlertRepository alertRepository;

    private volatile long cachedActiveAlerts = 0;
    private volatile long cacheExpiresAt = 0;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        LocalDateTime lastPrice = binanceWebSocketService.getLastPriceReceived();
        long now = System.currentTimeMillis();
        if (now > cacheExpiresAt) {
            cachedActiveAlerts = alertRepository.countByStatus(AlertStatus.ACTIVE);
            cacheExpiresAt = now + 30_000;
        }
        long activeAlerts = cachedActiveAlerts;

        Map<String, Object> response = new LinkedHashMap<>();
        BinanceWebSocketService.WsStatus ws = binanceWebSocketService.getWsStatus();
        boolean degraded = ws == BinanceWebSocketService.WsStatus.RECONNECTING
                        || ws == BinanceWebSocketService.WsStatus.CONNECTING;
        response.put("status", degraded ? "DEGRADED" : "UP");

        response.put("wsStatus", binanceWebSocketService.getWsStatus().name());

        if (lastPrice != null) {
            response.put("lastPriceReceived", lastPrice.toString());
            response.put("secondsSinceLastPrice",
                    ChronoUnit.SECONDS.between(lastPrice, LocalDateTime.now()));
        } else {
            response.put("lastPriceReceived", "never");
            response.put("secondsSinceLastPrice", null);
        }

        response.put("activeAlerts", activeAlerts);
        return ResponseEntity.ok(response);
    }
}
