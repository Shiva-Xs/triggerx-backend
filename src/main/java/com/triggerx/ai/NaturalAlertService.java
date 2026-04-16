package com.triggerx.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.triggerx.alert.AlertCondition;
import com.triggerx.alert.AlertRequest;
import com.triggerx.alert.AlertResponse;
import com.triggerx.alert.AlertService;
import com.triggerx.common.TriggerXException;
import com.triggerx.price.BinanceSymbolRegistry;
import com.triggerx.price.BinanceWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NaturalAlertService {

    private final ChatClient chatClient;
    private final AlertService alertService;
    private final BinanceWebSocketService binanceWebSocketService;
    private final BinanceSymbolRegistry symbolRegistry;

    @Value("${spring.ai.openai.api-key:}")
    private String aiApiKey;

    private static final String SYSTEM_PROMPT = """
            You are a crypto price alert assistant. Classify the user's message and extract data.

            Return JSON with these fields:
            - intent: CREATE_ALERT | LIST_ALERTS | DELETE_ALERT | DELETE_ALL | PRICE_CHECK | PCT_ALERT | AMBIGUOUS | UNKNOWN | GREETING | FAREWELL
            - symbol: uppercase ticker — required for CREATE_ALERT, PCT_ALERT, PRICE_CHECK, AMBIGUOUS
            - condition: ABOVE | BELOW | CROSSES — CREATE_ALERT only
            - targetPrice: decimal number — CREATE_ALERT only
            - deleteTarget: integer (1-based) — DELETE_ALERT only
            - percentTarget: signed decimal (e.g. 10.0 or -5.0) — PCT_ALERT only

            Coin name → ticker mappings (always use the ticker in symbol):
            bitcoin/btc → BTC, ethereum/eth → ETH, solana/sol → SOL, dogecoin/doge → DOGE,
            ripple/xrp → XRP, cardano/ada → ADA, avalanche/avax → AVAX, chainlink/link → LINK,
            polkadot/dot → DOT, shiba/shib → SHIB, litecoin/ltc → LTC, polygon/matic → MATIC,
            pepe → PEPE, bnb → BNB, tron/trx → TRX, near → NEAR, atom → ATOM

            CROSSES: price touches this level from either direction ("hits", "reaches", "at", "crosses").
            ABOVE: price goes above/over/past a level.
            BELOW: price drops below/under a level.
            AMBIGUOUS: user gave a symbol and price but NO direction — return AMBIGUOUS, do NOT guess.
            PCT_ALERT: user wants an alert based on % move from current price ("up 10%", "down 5%", "+15%").

            Examples:
            "btc above 80000"              → {"intent":"CREATE_ALERT","symbol":"BTC","condition":"ABOVE","targetPrice":80000}
            "ethereum drops below 2000"    → {"intent":"CREATE_ALERT","symbol":"ETH","condition":"BELOW","targetPrice":2000}
            "alert when bitcoin hits 73400"→ {"intent":"CREATE_ALERT","symbol":"BTC","condition":"CROSSES","targetPrice":73400}
            "notify me at sol 150"         → {"intent":"CREATE_ALERT","symbol":"SOL","condition":"CROSSES","targetPrice":150}
            "btc 80000"                    → {"intent":"AMBIGUOUS","symbol":"BTC","targetPrice":80000}
            "bitcoin 70k"                  → {"intent":"AMBIGUOUS","symbol":"BTC","targetPrice":70000}
            "alert me when btc is up 10%"  → {"intent":"PCT_ALERT","symbol":"BTC","percentTarget":10.0}
            "notify if eth falls 5%"       → {"intent":"PCT_ALERT","symbol":"ETH","percentTarget":-5.0}
            "show my alerts"               → {"intent":"LIST_ALERTS"}
            "delete alert 2"               → {"intent":"DELETE_ALERT","deleteTarget":2}
            "delete btc alerts"            → {"intent":"DELETE_ALERT","symbol":"BTC"}
            "delete all alerts"            → {"intent":"DELETE_ALL"}
            "clear all"                    → {"intent":"DELETE_ALL"}
            "remove all my alerts"         → {"intent":"DELETE_ALL"}
            "price of bitcoin"             → {"intent":"PRICE_CHECK","symbol":"BTC"}
            "hello"                        → {"intent":"GREETING"}
            "how are you"                  → {"intent":"GREETING"}
            "goodbye"                      → {"intent":"FAREWELL"}
            "bye"                          → {"intent":"FAREWELL"}
            """;

    public record ParsedMessage(String intent, String symbol, String condition,
                                BigDecimal targetPrice, Integer deleteTarget,
                                BigDecimal percentTarget) {}

    public record PriceContext(BigDecimal currentPrice, BigDecimal distancePct,
                               boolean alreadyMet, boolean noData) {
        public static PriceContext unknown() { return new PriceContext(null, null, false, true); }
    }

    public AlertResponse parseAndCreate(String text, UUID userId) {
        return createFromParsed(parseIntent(text), userId);
    }

    public PriceContext getPriceContext(ParsedMessage msg) {
        if (msg.symbol() == null || msg.targetPrice() == null) return PriceContext.unknown();
        Optional<BigDecimal> opt = binanceWebSocketService.getLatestPrice(msg.symbol());
        if (opt.isEmpty()) return PriceContext.unknown();
        BigDecimal current = opt.get();
        BigDecimal target  = msg.targetPrice();
        BigDecimal distPct = target.subtract(current)
                .divide(current, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
        boolean alreadyMet = switch (msg.condition() == null ? "" : msg.condition().toUpperCase()) {
            case "ABOVE"  -> current.compareTo(target) > 0;
            case "BELOW"  -> current.compareTo(target) < 0;
            case "CROSSES"-> current.compareTo(target) == 0;
            default       -> false;
        };
        return new PriceContext(current, distPct, alreadyMet, false);
    }

    public static String formatPrice(BigDecimal price) {
        if (price == null) return "?";
        if (price.compareTo(BigDecimal.ONE) >= 0)
            return "$" + String.format("%,.2f", price);
        return "$" + price.stripTrailingZeros().toPlainString();
    }

    public ParsedMessage parseIntent(String text) {
        checkConfigured();
        try {
            ParsedMessage msg = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(text)
                    .call()
                    .entity(ParsedMessage.class);
            if (msg == null || msg.intent() == null)
                return new ParsedMessage("UNKNOWN", null, null, null, null, null);
            log.debug("Intent: {} for: {}", msg.intent(), text);
            return msg;
        } catch (Exception e) {
            log.warn("Groq call failed: {}", e.getMessage());
            throw TriggerXException.aiUnavailable();
        }
    }

    public AlertResponse createFromParsed(ParsedMessage msg, UUID userId) {
        BigDecimal targetPrice = msg.targetPrice();

        if ("PCT_ALERT".equals(msg.intent())) {
            if (msg.symbol() == null || msg.percentTarget() == null)
                throw TriggerXException.parseFailed("Could not extract % alert details. Try: 'BTC up 10%'");
            String pctSym = msg.symbol().toUpperCase();
            if (!symbolRegistry.isSupported(pctSym)) throw TriggerXException.unsupportedSymbol(pctSym);
            BigDecimal current = binanceWebSocketService.getLatestPrice(pctSym)
                    .or(() -> fetchPriceFromRest(pctSym))
                    .orElseThrow(() -> TriggerXException.noLivePrice(pctSym));
            BigDecimal factor = BigDecimal.ONE.add(
                    msg.percentTarget().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            targetPrice = current.multiply(factor).setScale(2, RoundingMode.HALF_UP);
            String cond  = msg.percentTarget().compareTo(BigDecimal.ZERO) >= 0 ? "ABOVE" : "BELOW";
            log.info("PCT_ALERT resolved — userId={} {} {}% → {}", userId, pctSym, msg.percentTarget(), targetPrice);
            return alertService.createAlert(userId, new AlertRequest(pctSym, targetPrice, AlertCondition.valueOf(cond)));
        }

        if (msg.symbol() == null || msg.condition() == null || targetPrice == null)
            throw TriggerXException.parseFailed("Could not extract alert details. Try: 'BTC above 80000'");
        if (targetPrice.compareTo(BigDecimal.ZERO) <= 0)
            throw TriggerXException.parseFailed("Target price must be greater than zero");
        AlertCondition condition;
        try {
            condition = AlertCondition.valueOf(msg.condition().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw TriggerXException.parseFailed("Could not determine direction. Try: 'BTC above 80000'");
        }
        String symbol = msg.symbol().toUpperCase();
        log.info("Alert created — userId={} {} {} {}", userId, symbol, condition, targetPrice);
        return alertService.createAlert(userId, new AlertRequest(symbol, targetPrice, condition));
    }

    public Optional<BigDecimal> fetchPriceFromRest(String sym) {
        try {
            String stream = symbolRegistry.toStream(sym);
            // toStream() returns null for unsupported symbols. Calling .toUpperCase() on
            // null would throw an NPE that gets swallowed by the catch below — not harmful
            // but produces a misleading warn log. Bail early with a clean empty Optional.
            if (stream == null) return Optional.empty();
            String pair = stream.toUpperCase();
            JsonNode node = RestClient.create()
                    .get()
                    .uri("https://api.binance.com/api/v3/ticker/price?symbol=" + pair)
                    .retrieve()
                    .body(JsonNode.class);
            if (node == null || !node.has("price")) return Optional.empty();
            return Optional.of(new BigDecimal(node.get("price").asText()));
        } catch (Exception e) {
            log.warn("Binance REST price fallback failed for {}: {}", sym, e.getMessage());
            return Optional.empty();
        }
    }

    private void checkConfigured() {
        if (aiApiKey == null || aiApiKey.isBlank()) throw TriggerXException.aiNotConfigured();
    }
}
