package com.triggerx.ai;

import com.triggerx.common.TriggerXException;
import com.triggerx.price.BinanceSymbolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * What happens to alert creation when the AI provider is unreachable. The bot has no
 * other way to create an alert, so an outage must not take the feature down entirely.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NaturalAlertServiceFallbackTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private ChatClient chatClient;
    @Mock private BinanceSymbolRegistry symbolRegistry;

    private NaturalAlertService service;

    @BeforeEach
    void setUp() {
        when(symbolRegistry.isSupported(anyString()))
                .thenAnswer(i -> Set.of("BTC", "ETH", "SOL")
                        .contains(i.getArgument(0, String.class).toUpperCase()));
        service = new NaturalAlertService(chatClient, new LocalIntentParser(symbolRegistry),
                null, null, symbolRegistry);
        ReflectionTestUtils.setField(service, "aiApiKey", "test-key");
    }

    private void aiFailsWith(String message) {
        when(chatClient.prompt()).thenThrow(new RuntimeException(message));
    }

    @Test
    void servesACanonicalAlertWhenTheProviderIsOverQuota() {
        aiFailsWith("429 - RESOURCE_EXHAUSTED quota exceeded");
        var m = service.parseIntent("btc above 80000");
        assertEquals("CREATE_ALERT", m.intent());
        assertEquals("BTC", m.symbol());
        assertEquals("ABOVE", m.condition());
        assertEquals(0, new BigDecimal("80000").compareTo(m.targetPrice()));
    }

    @Test
    void servesACanonicalAlertWhenTheProviderIsDown() {
        aiFailsWith("Connection refused");
        var m = service.parseIntent("eth below 2000");
        assertEquals("CREATE_ALERT", m.intent());
        assertEquals(0, new BigDecimal("2000").compareTo(m.targetPrice()));
    }

    @Test
    void servesACanonicalAlertWhenNoApiKeyIsConfigured() {
        ReflectionTestUtils.setField(service, "aiApiKey", "");
        var m = service.parseIntent("sol hits 150");
        assertEquals("CREATE_ALERT", m.intent());
        assertEquals("CROSSES", m.condition());
    }

    @Test
    void stillReportsRateLimitingWhenTheMessageIsNotCanonical() {
        aiFailsWith("429 - RESOURCE_EXHAUSTED quota exceeded");
        var e = assertThrows(TriggerXException.class,
                () -> service.parseIntent("alert me when bitcoin goes up a bit"));
        assertEquals("AI_RATE_LIMITED", e.getErrorCode());
    }

    @Test
    void stillReportsAnOutageWhenTheMessageIsNotCanonical() {
        aiFailsWith("Connection refused");
        var e = assertThrows(TriggerXException.class,
                () -> service.parseIntent("eth above 2 percent"));
        assertEquals("AI_UNAVAILABLE", e.getErrorCode());
    }

    @Test
    void localCommandsNeverTouchTheProviderAtAll() {
        aiFailsWith("429 - RESOURCE_EXHAUSTED quota exceeded");
        assertEquals("LIST_ALERTS", service.parseIntent("my alerts").intent());
        assertEquals("DELETE_ALL", service.parseIntent("delete all").intent());
        assertEquals("PRICE_CHECK", service.parseIntent("btc price").intent());
        assertEquals("GREETING", service.parseIntent("hello").intent());
    }
}
