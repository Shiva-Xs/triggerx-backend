package com.triggerx.price;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class BinanceSymbolRegistry {

    private static final String EXCHANGE_INFO_URL =
            "https://api.binance.com/api/v3/exchangeInfo";

    private final AtomicReference<Map<String, String>> tickerToStream = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, String>> streamToTicker = new AtomicReference<>(Map.of());

    @PostConstruct
    public void loadSupportedPairs() {
        try {
            JsonNode root = RestClient.create().get().uri(EXCHANGE_INFO_URL).retrieve().body(JsonNode.class);
            if (root == null || !root.has("symbols")) {
                log.warn("Binance exchangeInfo response missing 'symbols'");
                return;
            }
            Map<String, String> fwd = new LinkedHashMap<>();
            Map<String, String> rev = new LinkedHashMap<>();
            root.get("symbols").forEach(node -> {
                if ("TRADING".equals(node.path("status").asText())
                        && "USDT".equals(node.path("quoteAsset").asText())) {
                    String stream = node.path("symbol").asText().toLowerCase();
                    fwd.put(node.path("baseAsset").asText(), stream);
                    rev.put(stream, node.path("baseAsset").asText());
                }
            });
            tickerToStream.set(Collections.unmodifiableMap(fwd));
            streamToTicker.set(Collections.unmodifiableMap(rev));
            log.info("BinanceSymbolRegistry loaded: {} USDT trading pairs", fwd.size());
        } catch (Exception e) {
            log.warn("Failed to load Binance exchange info — registry is empty. Cause: {}", e.getMessage());
        }
    }

    public boolean isSupported(String ticker) {
        return tickerToStream.get().containsKey(ticker.toUpperCase());
    }

    public String toStream(String ticker) {
        return tickerToStream.get().get(ticker.toUpperCase());
    }

    public String fromStream(String streamSymbol) {
        return streamToTicker.get().get(streamSymbol.toLowerCase());
    }

    public List<String> search(String query) {
        String q = query.toUpperCase();
        return tickerToStream.get().keySet().stream()
                .filter(ticker -> ticker.startsWith(q))
                .sorted()
                .limit(10)
                .toList();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void retryIfEmpty() {
        if (tickerToStream.get().isEmpty()) {
            log.warn("BinanceSymbolRegistry is empty — retrying Binance exchange info load");
            loadSupportedPairs();
        }
    }

    @Scheduled(fixedRate = 6 * 60 * 60 * 1000L, initialDelay = 6 * 60 * 60 * 1000L)
    public void periodicRefresh() {
        log.info("BinanceSymbolRegistry — periodic full refresh");
        loadSupportedPairs();
    }
}
