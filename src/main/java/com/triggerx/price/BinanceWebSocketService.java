package com.triggerx.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.triggerx.alert.Alert;
import com.triggerx.alert.AlertRepository;
import com.triggerx.alert.AlertStatus;
import com.triggerx.common.AlertChangedEvent;
import com.triggerx.common.AlertFiredEvent;
import com.triggerx.common.EmailUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class BinanceWebSocketService {

    private static final String WS_BASE = "wss://stream.binance.com:9443/stream?streams=";
    private static final int MAX_RECONNECT_DELAY_SEC = 64;

    private final AlertRepository alertRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final BinanceSymbolRegistry symbolRegistry;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "binance-ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    public enum WsStatus { CONNECTING, CONNECTED, IDLE, RECONNECTING }

    private final AtomicReference<ConcurrentHashMap<String, CopyOnWriteArrayList<Alert>>> alertCacheRef =
            new AtomicReference<>(new ConcurrentHashMap<>());

    private final ConcurrentHashMap<String, BigDecimal> latestPrices = new ConcurrentHashMap<>();

    private volatile WebSocketClient wsClient;
    private volatile WsStatus wsStatus = WsStatus.IDLE;
    private volatile boolean running = true;
    private volatile boolean refreshPending = false;
    private volatile LocalDateTime lastPriceReceived;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    public BinanceWebSocketService(AlertRepository alertRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   ObjectMapper objectMapper,
                                   BinanceSymbolRegistry symbolRegistry) {
        this.alertRepository = alertRepository;
        this.eventPublisher  = eventPublisher;
        this.objectMapper    = objectMapper;
        this.symbolRegistry = symbolRegistry;
    }

    @PostConstruct
    public void start() {
        connect();
    }

    @PreDestroy
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        WebSocketClient c = wsClient;
        if (c != null) c.close();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertChanged(AlertChangedEvent event) {
        if (event.reconnectRequired()) {
            log.info("Alert changed (post-commit) — refreshing subscriptions and cache");
            refreshSubscriptions();
        } else {
            log.info("Alert changed (post-commit) — symbol set unchanged, rebuilding cache only");
            rebuildCache();
        }
    }

    public void refreshSubscriptions() {
        if (!running) return;
        log.info("Refreshing Binance WebSocket subscriptions");
        rebuildCache();
        refreshPending = true;
        WebSocketClient c = wsClient;
        if (c != null && c.isOpen()) c.close();
        reconnectAttempts.set(0);
        scheduler.schedule(() -> {
            refreshPending = false;
            connect();
        }, 500, TimeUnit.MILLISECONDS);
    }

    public LocalDateTime getLastPriceReceived() { return lastPriceReceived; }
    public WsStatus getWsStatus()               { return wsStatus; }
    public Optional<BigDecimal> getLatestPrice(String symbol) { return Optional.ofNullable(latestPrices.get(symbol.toUpperCase())); }

    private synchronized void connect() {
        if (!running) return;

        // Close any previously open connection before creating a new one.
        // Without this, rapid back-to-back refreshSubscriptions() calls (e.g. two users
        // creating alerts within 500ms) each schedule a connect(). The second connect()
        // would overwrite wsClient, leaving the first connection orphaned — still
        // receiving messages and holding a thread, but no longer reachable for cleanup.
        WebSocketClient prev = wsClient;
        if (prev != null && !prev.isClosed()) {
            try { prev.closeBlocking(); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            List<String> activeSymbols =
                    alertRepository.findDistinctSymbolsByStatus(AlertStatus.ACTIVE);

            List<String> streams = activeSymbols.stream()
                    .filter(symbolRegistry::isSupported)
                    .map(ticker -> symbolRegistry.toStream(ticker) + "@miniTicker")
                    .toList();

            if (streams.isEmpty()) {
                log.info("No active alerts with supported Binance symbols — WebSocket idle");
                wsStatus = WsStatus.IDLE;
                return;
            }

            wsStatus = WsStatus.CONNECTING;
            String url = WS_BASE + String.join("/", streams);
            log.info("Connecting to Binance WebSocket ({} stream(s))", streams.size());

            wsClient = new WebSocketClient(URI.create(url)) {
                @Override public void onOpen(ServerHandshake h) {
                    reconnectAttempts.set(0); wsStatus = WsStatus.CONNECTED; rebuildCache();
                    log.info("Binance WebSocket connected — watching {} symbol(s)", streams.size());
                }
                @Override public void onMessage(String message) { handleMessage(message); }
                @Override public void onClose(int code, String reason, boolean remote) {
                    log.warn("Binance WebSocket closed (code={}, remote={})", code, remote);
                    wsStatus = (running && !refreshPending) ? WsStatus.RECONNECTING : WsStatus.IDLE;
                    if (running && !refreshPending) scheduleReconnect();
                }
                @Override public void onError(Exception e) {
                    log.error("Binance WebSocket error: {}", e.getMessage());
                }
            };
            wsClient.connect();
        } catch (Exception e) {
            log.error("Binance WebSocket connect() failed: {}", e.getMessage());
            if (running) {
                wsStatus = WsStatus.RECONNECTING;
                scheduleReconnect();
            }
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode data = root.get("data");
            if (data == null) return;
            String streamSymbol = data.path("s").asText("").toLowerCase();
            String priceStr     = data.path("c").asText("");
            if (streamSymbol.isEmpty() || priceStr.isEmpty()) return;
            String ticker = symbolRegistry.fromStream(streamSymbol);
            if (ticker == null) return;
            BigDecimal price = new BigDecimal(priceStr);
            lastPriceReceived = LocalDateTime.now();
            BigDecimal prevPrice = latestPrices.get(ticker);  // capture BEFORE update — needed for CROSSES
            latestPrices.put(ticker, price);
            log.debug("Price update: {} = ${}", ticker, price.toPlainString());
            checkAlerts(ticker, price, prevPrice);
        } catch (Exception e) {
            log.error("Error processing Binance message: {}", e.getMessage());
        }
    }

    private void checkAlerts(String ticker, BigDecimal currentPrice, BigDecimal prevPrice) {
        CopyOnWriteArrayList<Alert> alerts = alertCacheRef.get().get(ticker);
        if (alerts == null || alerts.isEmpty()) return;
        for (Alert alert : alerts) {
            if (alert.isTriggeredBy(currentPrice, prevPrice)) {
                triggerAlert(alert, currentPrice);
            }
        }
    }

    private void triggerAlert(Alert alert, BigDecimal currentPrice) {
        try {
            int rowsAffected = alertRepository.triggerAlert(alert.getId(), currentPrice);
            if (rowsAffected != 1) return;
            removeFromCache(alert);

            LocalDateTime triggeredAt = LocalDateTime.now();

            eventPublisher.publishEvent(new AlertFiredEvent(
                    alert.getUser().getId(),
                    alert.getId(),
                    alert.getUser().getEmail(),
                    alert.getSymbol(),
                    alert.getCondition(),
                    alert.getTargetPrice(),
                    currentPrice,
                    triggeredAt
            ));

            log.info("Alert {} triggered for {} — {} {} {} (hit ${})",
                    alert.getId(),
                    EmailUtils.maskEmail(alert.getUser().getEmail()),
                    alert.getSymbol(),
                    alert.getCondition(),
                    alert.getTargetPrice().toPlainString(),
                    currentPrice.toPlainString());

        } catch (Exception e) {
            log.error("Failed to trigger alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    private void rebuildCache() {
        try {
            List<Alert> all = alertRepository.findActiveWithUser(AlertStatus.ACTIVE);
            ConcurrentHashMap<String, CopyOnWriteArrayList<Alert>> fresh = new ConcurrentHashMap<>();
            for (Alert a : all) {
                fresh.computeIfAbsent(a.getSymbol(), k -> new CopyOnWriteArrayList<>()).add(a);
            }
            alertCacheRef.set(fresh);
            log.info("Alert cache rebuilt: {} symbol(s), {} alert(s)", fresh.size(), all.size());
        } catch (Exception e) {
            log.error("Failed to rebuild alert cache: {}", e.getMessage());
        }
    }

    private void removeFromCache(Alert alert) {
        CopyOnWriteArrayList<Alert> list = alertCacheRef.get().get(alert.getSymbol());
        if (list != null) list.remove(alert);
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        int delaySeconds = (int) Math.min(Math.pow(2, attempt), MAX_RECONNECT_DELAY_SEC);
        log.info("Scheduling Binance reconnect in {}s (attempt {})", delaySeconds, attempt);
        scheduler.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }
}
