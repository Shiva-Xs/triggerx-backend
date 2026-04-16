package com.triggerx.alert;

import com.triggerx.common.AlertChangedEvent;
import com.triggerx.common.TriggerXException;
import com.triggerx.price.BinanceSymbolRegistry;
import com.triggerx.user.User;
import com.triggerx.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BinanceSymbolRegistry symbolRegistry;

    @Value("${alert.max.per.user:50}")
    private int maxAlertsPerUser;

    @Transactional
    public AlertResponse createAlert(UUID userId, AlertRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(TriggerXException::accountDeleted);

        long activeCount = alertRepository.countByUserIdAndStatus(userId, AlertStatus.ACTIVE);
        if (activeCount >= maxAlertsPerUser) throw TriggerXException.alertLimitReached(maxAlertsPerUser);

        String symbol = request.symbol().toUpperCase();
        if (!symbolRegistry.isSupported(symbol)) throw TriggerXException.unsupportedSymbol(symbol);

        if (alertRepository.existsDuplicateActive(userId, symbol, request.condition(), request.targetPrice(), AlertStatus.ACTIVE)) {
            throw TriggerXException.duplicateAlert(symbol, request.condition().name(), request.targetPrice());
        }

        Alert alert = new Alert();
        alert.setUser(user);
        alert.setSymbol(symbol);
        alert.setTargetPrice(request.targetPrice());
        alert.setCondition(request.condition());
        alert.setStatus(AlertStatus.ACTIVE);

        AlertResponse response = AlertResponse.from(alertRepository.save(alert));
        eventPublisher.publishEvent(new AlertChangedEvent(true));
        return response;
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> getAlerts(UUID userId, AlertStatus statusFilter) {
        return (statusFilter != null
                ? alertRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, statusFilter)
                : alertRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .stream().map(AlertResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AlertResponse getAlert(UUID userId, UUID alertId) {
        Alert alert = alertRepository.findByIdAndUserId(alertId, userId)
                .orElseThrow(TriggerXException::alertNotFound);

        return AlertResponse.from(alert);
    }

    @Transactional
    public void deleteAlert(UUID userId, UUID alertId) {
        Alert alert = alertRepository.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> alertRepository.existsById(alertId)
                        ? TriggerXException.forbidden()
                        : TriggerXException.alertNotFound());

        String symbol = alert.getSymbol();
        alertRepository.delete(alert);
        boolean symbolGone = alertRepository.countBySymbolAndStatus(symbol, AlertStatus.ACTIVE) == 0;
        eventPublisher.publishEvent(new AlertChangedEvent(symbolGone));
    }

    @Transactional
    public long deleteAllAlerts(UUID userId) {
        long deleted = alertRepository.deleteByUserIdAndStatus(userId, AlertStatus.ACTIVE);
        if (deleted > 0) eventPublisher.publishEvent(new AlertChangedEvent(true));
        return deleted;
    }

    @Transactional
    public long deleteAlertsBySymbol(UUID userId, String symbol) {
        long deleted = alertRepository.deleteByUserIdAndSymbolAndStatus(userId, symbol, AlertStatus.ACTIVE);
        if (deleted > 0) {
            boolean symbolGone = alertRepository.countBySymbolAndStatus(symbol, AlertStatus.ACTIVE) == 0;
            eventPublisher.publishEvent(new AlertChangedEvent(symbolGone));
        }
        return deleted;
    }
}
