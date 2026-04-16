package com.triggerx.alert;

import com.triggerx.common.TriggerXException;
import com.triggerx.price.BinanceSymbolRegistry;
import com.triggerx.user.User;
import com.triggerx.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BinanceSymbolRegistry symbolRegistry;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AlertService alertService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(alertService, "maxAlertsPerUser", 50);
    }

    @Test
    void createAlert_RejectsWhenLimitReached() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User("test@gmail.com")));
        when(alertRepository.countByUserIdAndStatus(userId, AlertStatus.ACTIVE)).thenReturn(50L);

        AlertRequest request = new AlertRequest("BTC", new BigDecimal("80000"), AlertCondition.ABOVE);

        TriggerXException ex = assertThrows(TriggerXException.class, () ->
                alertService.createAlert(userId, request));

        assertEquals("ALERT_LIMIT_REACHED", ex.getErrorCode());
    }

    @Test
    void createAlert_RejectsUnsupportedSymbol() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User("test@gmail.com")));
        when(alertRepository.countByUserIdAndStatus(userId, AlertStatus.ACTIVE)).thenReturn(0L);
        when(symbolRegistry.isSupported(anyString())).thenReturn(false);

        AlertRequest request = new AlertRequest("FAKECOIN", new BigDecimal("1"), AlertCondition.ABOVE);

        TriggerXException ex = assertThrows(TriggerXException.class, () ->
                alertService.createAlert(userId, request));

        assertEquals("UNSUPPORTED_SYMBOL", ex.getErrorCode());
    }

    @Test
    void deleteAlert_RejectsWrongUserId() {
        UUID userId = UUID.randomUUID();
        UUID alertId = UUID.randomUUID();

        when(alertRepository.findByIdAndUserId(alertId, userId)).thenReturn(Optional.empty());
        when(alertRepository.existsById(alertId)).thenReturn(true);

        TriggerXException ex = assertThrows(TriggerXException.class, () ->
                alertService.deleteAlert(userId, alertId));

        assertEquals("FORBIDDEN", ex.getErrorCode());
    }
}
