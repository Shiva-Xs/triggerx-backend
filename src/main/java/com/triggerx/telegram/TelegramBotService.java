package com.triggerx.telegram;

import com.triggerx.ai.NaturalAlertService;
import com.triggerx.alert.AlertService;
import com.triggerx.alert.AlertStatus;
import com.triggerx.common.TriggerXException;
import com.triggerx.price.BinanceWebSocketService;
import com.triggerx.user.User;
import com.triggerx.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.triggerx.telegram.TelegramUtils.escapeMd;

@Slf4j
@Service
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramBotService extends TelegramLongPollingBot {

    private final TelegramUserRepository   telegramUserRepo;
    private final UserRepository           userRepository;
    private final AlertService             alertService;
    private final NaturalAlertService      naturalAlertService;
    private final TelegramLinkTokenService telegramLinkTokenService;
    private final BinanceWebSocketService  binanceWebSocketService;

    private record Pending(NaturalAlertService.ParsedMessage msg, Instant expiresAt) {
        static Pending of(NaturalAlertService.ParsedMessage msg) {
            return new Pending(msg, Instant.now().plusSeconds(300));
        }
    }

    private final Map<Long, Pending>  pendingConfirmations = new ConcurrentHashMap<>();
    private final Map<Long, Pending>  pendingDirections    = new ConcurrentHashMap<>();
    private final Map<Long, Instant>  pendingDeleteAll     = new ConcurrentHashMap<>();
    private final Map<Long, Instant>  pendingUnlink        = new ConcurrentHashMap<>();

    private final String botUsername;

    private static final String HELP_TEXT = """
            📋 *Commands*

            */add* \\- create a price alert
            */list* \\- view & delete your alerts
            */history* \\- recently triggered alerts
            */price* BTC \\- current price
            */deleteall* \\- remove all active alerts
            */unlink* \\- disconnect this account
            */help* \\- show this menu

            _Or just type anything \\- I understand plain English\\!_
            _Prices are Binance spot, quoted in USDT\\._""";

    public TelegramBotService(TelegramUserRepository telegramUserRepo,
                               UserRepository userRepository,
                               AlertService alertService,
                               NaturalAlertService naturalAlertService,
                               TelegramLinkTokenService telegramLinkTokenService,
                               BinanceWebSocketService binanceWebSocketService,
                               @Value("${telegram.bot.token:}") String botToken,
                               @Value("${telegram.bot.username:TriggerXBot}") String botUsername) {
        super(botToken);
        this.telegramUserRepo         = telegramUserRepo;
        this.userRepository           = userRepository;
        this.alertService             = alertService;
        this.naturalAlertService      = naturalAlertService;
        this.telegramLinkTokenService = telegramLinkTokenService;
        this.binanceWebSocketService  = binanceWebSocketService;
        this.botUsername              = botUsername;
    }

    @PostConstruct
    public void registerBot() {
        try {
            new TelegramBotsApi(DefaultBotSession.class).registerBot(this);
            log.info("Telegram bot '{}' registered successfully", botUsername);
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot '{}': {}", botUsername, e.getMessage(), e);
        }
    }

    @Override
    public String getBotUsername() { return botUsername; }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) { handleCallback(update.getCallbackQuery()); return; }
            if (!update.hasMessage()) return;
            var message = update.getMessage();
            if (!message.hasText()) {
                long chatId = message.getChatId();
                if (message.hasPhoto() || message.hasDocument() || message.hasVideo() || message.hasAudio() || message.hasVoice() || message.hasSticker()) {
                    send(chatId, "❌ Please send text only\\. Try: *BTC above 80000*");
                }
                return;
            }
            long chatId = message.getChatId();
            String text = message.getText().trim();
            Optional<TelegramUser> tu = telegramUserRepo.findByChatIdWithUser(chatId);
            if (tu.isPresent()) handleLinked(chatId, tu.get(), text);
            else handleLinking(chatId, text);
        } catch (Exception e) {
            log.error("onUpdateReceived failed: {}", e.getMessage(), e);
        }
    }

    private void handleLinking(long chatId, String text) {
        if (text.startsWith("/start link_")) {
            String token = text.substring("/start link_".length()).trim();
            telegramLinkTokenService.resolveToken(token).ifPresentOrElse(
                userId -> linkByToken(chatId, userId),
                ()     -> send(chatId, "⚠️ Link expired\\. Go to the website and try again\\.")
            );
            return;
        }
        send(chatId,
            "👋 *Welcome to TriggerX\\!*\n\n" +
            "To get started:\n" +
            "1\\. Go to the website and log in\n" +
            "2\\. Click *Connect Telegram*\n\n" +
            "Your account will link instantly \\- no code needed\\.\n\n" +
            "_Prices are Binance spot, quoted in USDT\\._");
    }

    private void linkByToken(long chatId, UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) { send(chatId, "❌ Account not found\\. Try again\\."); return; }
        if (telegramUserRepo.existsByUserId(userId)) {
            send(chatId, "✅ Already linked to *" + escapeMd(user.getEmail()) + "*\\.", mainMenu());
            return;
        }
        try {
            telegramUserRepo.save(new TelegramUser(chatId, user));
            send(chatId, "✅ *Linked to " + escapeMd(user.getEmail()) + "\\!*\n\nAlerts will now fire here\\.", mainMenu());
        } catch (DataIntegrityViolationException e) {
            send(chatId, "⚠️ Chat already linked to another account\\.", mainMenu());
        }
    }

    private void handleCallback(CallbackQuery query) {
        long chatId = query.getMessage().getChatId();
        String data = query.getData();
        ack(query.getId());
        Optional<TelegramUser> tu = telegramUserRepo.findByChatIdWithUser(chatId);
        if (tu.isEmpty()) { send(chatId, "Visit the website to link your account first\\."); return; }
        UUID userId = tu.get().getUser().getId();
        switch (data) {
            case "action:add"     -> promptNewAlert(chatId);
            case "action:list"    -> showAlerts(chatId, userId);
            case "action:history" -> showHistory(chatId, userId);
            case "action:help"    -> send(chatId, HELP_TEXT, mainMenu());
            case "action:unlink"  -> handleUnlinkPrompt(chatId, userId);
            case "confirm:create"    -> executeConfirmedAlert(chatId, userId);
            case "confirm:deleteall" -> executeDeleteAll(chatId, userId);
            case "confirm:deletesym" -> executeDeleteSymbol(chatId, userId);
            case "confirm:cancel"    -> { clearPending(chatId); send(chatId, "❌ Cancelled\\.", mainMenu()); }
            case "confirm:unlink"    -> executeUnlink(chatId, userId, tu.get());
            case "dir:above"      -> resolveAmbiguous(chatId, userId, "ABOVE");
            case "dir:below"      -> resolveAmbiguous(chatId, userId, "BELOW");
            case "dir:crosses"    -> resolveAmbiguous(chatId, userId, "CROSSES");
            default               -> { if (data.startsWith("delete:")) deleteAlertById(chatId, userId, UUID.fromString(data.substring(7))); }
        }
    }

    private void handleLinked(long chatId, TelegramUser tu, String text) {
        UUID userId = tu.getUser().getId();
        String lower = text.toLowerCase().trim();

        switch (lower) {
            case "/start"           -> { send(chatId, "✅ Already linked\\.", mainMenu()); return; }
            case "/list", "/alerts" -> { showAlerts(chatId, userId); return; }
            case "/add", "/new"     -> { promptNewAlert(chatId); return; }
            case "/history"         -> { showHistory(chatId, userId); return; }
            case "/help"            -> { send(chatId, HELP_TEXT, mainMenu()); return; }
            case "/unlink"          -> { handleUnlinkPrompt(chatId, userId); return; }
            case "/deleteall"        -> { handleDeleteAllPrompt(chatId, userId); return; }
            default -> {}
        }

        if (lower.startsWith("/price ")) { showPrice(chatId, lower.substring(7).trim().toUpperCase()); return; }
        if (lower.equals("/price")) { send(chatId, "Usage: */price BTC*"); return; }
        if (lower.startsWith("/delete ") || lower.matches("^delete\\s+\\d+$")) { handleDeleteByNumber(chatId, userId, lower); return; }

        routeViaGroq(chatId, userId, text);
    }

    private void promptNewAlert(long chatId) {
        send(chatId,
            "💬 *What would you like to be alerted on?*\n\n" +
            "Just type it naturally:\n" +
            "• _BTC drops below 70000_\n" +
            "• _notify me when ETH hits 3500_\n" +
            "• _alert if SOL goes above 150_",
            keyboard(row(btn("❌ Cancel", "action:list"))));
    }

    private void showAlerts(long chatId, UUID userId) {
        var alerts = alertService.getAlerts(userId, AlertStatus.ACTIVE);
        if (alerts.isEmpty()) {
            send(chatId, "📭 No active alerts\\.", keyboard(row(btn("➕ Create Alert", "action:add"))));
            return;
        }
        var sb   = new StringBuilder("📋 *Active alerts \\(" + alerts.size() + "\\)*:\n\n");
        var rows = new ArrayList<List<InlineKeyboardButton>>();
        for (int i = 0; i < alerts.size(); i++) {
            var a       = alerts.get(i);
            var current = binanceWebSocketService.getLatestPrice(a.symbol());
            sb.append(String.format("%d\\. *%s* %s %s", i + 1,
                    escapeMd(a.symbol()), a.condition().label(),
                    fmtMd(a.targetPrice())));
            if (current.isPresent()) {
                BigDecimal pct = a.targetPrice().subtract(current.get())
                        .divide(current.get(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);
                sb.append(String.format("  ←  %s \\(%s%%\\)",
                        fmtMd(current.get()),
                        escapeMd((pct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + pct.toPlainString())));
            }
            sb.append("\n");
            rows.add(row(btn("🗑 Delete #" + (i + 1) + " " + a.symbol(), "delete:" + a.id())));
        }
        rows.add(row(btn("➕ New Alert", "action:add")));
        send(chatId, sb.toString(), new InlineKeyboardMarkup(rows));
    }

    private void showHistory(long chatId, UUID userId) {
        var triggered = alertService.getAlerts(userId, AlertStatus.TRIGGERED);
        if (triggered.isEmpty()) { send(chatId, "📭 No triggered alerts yet\\.", mainMenu()); return; }
        var recent = triggered.size() > 10 ? triggered.subList(0, 10) : triggered;
        var sb = new StringBuilder("🕐 *Recent alerts \\(" + recent.size() + "\\)*:\n\n");
        for (var a : recent) {
            sb.append(String.format("• *%s* %s %s",
                escapeMd(a.symbol()), a.condition().label(), fmtMd(a.targetPrice())));
            if (a.triggeredPrice() != null)
                sb.append(" \\- hit ").append(fmtMd(a.triggeredPrice()));
            sb.append("\n");
        }
        send(chatId, sb.toString(), mainMenu());
    }

    private void showPrice(long chatId, String symbol) {
        binanceWebSocketService.getLatestPrice(symbol)
            .or(() -> naturalAlertService.fetchPriceFromRest(symbol))
            .ifPresentOrElse(
                price -> send(chatId, String.format("💰 *%s* \\- %s", escapeMd(symbol), fmtMd(price))),
                () -> send(chatId, "❌ *" + escapeMd(symbol) + "* is not a supported Binance USDT ticker\\.")
            );
    }

    private void deleteAlertById(long chatId, UUID userId, UUID alertId) {
        try {
            alertService.deleteAlert(userId, alertId);
            send(chatId, "✅ Alert deleted\\.");
            showAlerts(chatId, userId);
        } catch (TriggerXException e) { send(chatId, err(e)); }
    }

    private void handleDeleteByNumber(long chatId, UUID userId, String lower) {
        try {
            int idx = Integer.parseInt(lower.replaceFirst("^/?delete\\s+", "").trim()) - 1;
            var alerts = alertService.getAlerts(userId, AlertStatus.ACTIVE);
            if (idx < 0 || idx >= alerts.size()) { send(chatId, "❌ Invalid number\\. Use \\/list to see your alerts\\."); return; }
            alertService.deleteAlert(userId, alerts.get(idx).id());
            send(chatId, "✅ Alert \\#" + (idx + 1) + " deleted\\.");
            showAlerts(chatId, userId);
        } catch (NumberFormatException e) {
            send(chatId, "❌ Try: *delete 1*");
        } catch (TriggerXException e) { send(chatId, err(e)); }
    }

    private void handleDeleteBySymbolPrompt(long chatId, UUID userId, String symbol) {
        String sym = symbol.toUpperCase();
        long count = alertService.getAlerts(userId, AlertStatus.ACTIVE).stream()
                .filter(a -> a.symbol().equalsIgnoreCase(sym))
                .count();

        if (count == 0) {
            send(chatId, "ℹ️ You have no active alerts for *" + escapeMd(sym) + "*\\.", mainMenu());
            return;
        }

        var resolved = new NaturalAlertService.ParsedMessage(
                "DELETE_BY_SYMBOL", sym, null, null, null, null);
        pendingConfirmations.put(chatId, Pending.of(resolved));

        send(chatId,
            String.format("⚠️ *Delete all %d %s alert%s\\?*",
                count, escapeMd(sym), count == 1 ? "" : "s"),
            keyboard(row(btn("🗑 Yes, delete", "confirm:deletesym"), btn("❌ Cancel", "confirm:cancel"))));
    }

    private void executeDeleteSymbol(long chatId, UUID userId) {
        Pending p = pendingConfirmations.remove(chatId);
        if (p == null || !"DELETE_BY_SYMBOL".equals(p.msg().intent())) { 
            send(chatId, "⏱ Session expired\\. Try again\\.", mainMenu()); 
            return; 
        }
        String sym = p.msg().symbol();
        long deleted = alertService.deleteAlertsBySymbol(userId, sym);
        send(chatId,
            deleted == 0
                ? "ℹ️ No active alerts for *" + escapeMd(sym) + "* to delete\\."
                : String.format("✅ *%d %s alert%s deleted\\.*", deleted, escapeMd(sym), deleted == 1 ? "" : "s"),
            mainMenu());
    }

    private void routeViaGroq(long chatId, UUID userId, String text) {
        try { execute(SendChatAction.builder().chatId(String.valueOf(chatId)).action("typing").build()); } catch (TelegramApiException ignored) {}
        try {
            var msg = naturalAlertService.parseIntent(text);
            switch (msg.intent()) {
                case "CREATE_ALERT" -> handleGroqCreate(chatId, msg);
                case "PCT_ALERT"    -> handlePctAlert(chatId, msg);
                case "AMBIGUOUS"    -> handleAmbiguous(chatId, msg);
                case "LIST_ALERTS"  -> showAlerts(chatId, userId);
                case "DELETE_ALL"   -> handleDeleteAllPrompt(chatId, userId);
                case "DELETE_ALERT" -> {
                    if (msg.deleteTarget() != null) {
                        handleDeleteByNumber(chatId, userId, "delete " + msg.deleteTarget());
                    } else if (msg.symbol() != null) {
                        handleDeleteBySymbolPrompt(chatId, userId, msg.symbol());
                    } else {
                        send(chatId, "❌ Which alert should I delete? Try: *delete 2* or *delete BTC*");
                    }
                }
                case "PRICE_CHECK"  -> {
                    if (msg.symbol() != null) showPrice(chatId, msg.symbol().toUpperCase());
                    else send(chatId, "❌ Which coin? Try: */price BTC*");
                }
                case "GREETING"     -> send(chatId, "👋 Hello\\! I'm ready to create price alerts for you\\. Try: *BTC above 80000*", mainMenu());
                case "FAREWELL"     -> send(chatId, "👋 Catch you later\\! TriggerX is always watching the charts for you\\.\\.\\. rest easy\\! 🌙", mainMenu());
                default -> send(chatId,
                    "🤔 Not sure what you mean \\- try _BTC below 60000_ or /help for commands\\.",
                    mainMenu());
            }
        } catch (TriggerXException e) {
            switch (e.getErrorCode()) {
                case "AI_NOT_CONFIGURED" -> send(chatId, "🤖 AI is not configured on this server\\.");
                case "AI_UNAVAILABLE"    -> send(chatId, "🤖 AI service is unavailable\\. Try again shortly\\.");
                default                  -> send(chatId, err(e));
            }
        } catch (Exception e) {
            log.warn("routeViaGroq failed chatId={}: {}", chatId, e.getMessage());
            send(chatId, "❌ Something went wrong\\. Try again\\.");
        }
    }

    private void handleGroqCreate(long chatId, NaturalAlertService.ParsedMessage msg) {
        pendingConfirmations.put(chatId, Pending.of(msg));
        showConfirmation(chatId, msg);
    }

    private void showConfirmation(long chatId, NaturalAlertService.ParsedMessage msg) {
        NaturalAlertService.PriceContext ctx = naturalAlertService.getPriceContext(msg);
        String condLabel = switch (msg.condition() == null ? "" : msg.condition().toUpperCase()) {
            case "ABOVE"   -> "above";
            case "BELOW"   -> "below";
            default        -> "crosses";
        };
        var sb = new StringBuilder(String.format("*%s* %s *%s*\n",
            escapeMd(msg.symbol()), condLabel, fmtMd(msg.targetPrice())));
        if (!ctx.noData()) {
            BigDecimal pct = ctx.distancePct();
            String arrow = pct.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
            sb.append(String.format("Currently %s  ·  %s%% away %s",
                fmtMd(ctx.currentPrice()),
                escapeMd(String.format("%+.1f", pct)), arrow));
            if (ctx.alreadyMet())
                sb.append("\n\n⚠️ _Price already past this level \\- fires immediately\\!_");
        }
        send(chatId, sb.toString(),
            keyboard(row(btn("✅ Create Alert", "confirm:create"), btn("❌ Cancel", "confirm:cancel"))));
    }

    private static String err(TriggerXException e) { return "❌ " + escapeMd(e.getMessage()); }

    private void executeConfirmedAlert(long chatId, UUID userId) {
        Pending p = pendingConfirmations.remove(chatId);
        if (p == null) { send(chatId, "⏱ Session expired\\. Try again\\.", mainMenu()); return; }
        NaturalAlertService.ParsedMessage msg = p.msg();
        try {
            var alert = naturalAlertService.createFromParsed(msg, userId);
            send(chatId, String.format(
                "✅ *%s* %s %s \\- I'll notify you the moment it fires\\.",
                escapeMd(alert.symbol()), alert.condition().label(),
                fmtMd(alert.targetPrice())),
                keyboard(row(btn("📋 My Alerts", "action:list"), btn("➕ Another", "action:add"))));
        } catch (TriggerXException e) {
            switch (e.getErrorCode()) {
                case "PARSE_FAILED", "UNSUPPORTED_SYMBOL" ->
                    send(chatId, "❌ " + escapeMd(e.getMessage()) + "\n\n_Try rephrasing, e\\.g\\.: BTC above 80000_",
                        keyboard(row(btn("❌ Cancel", "action:list"))));
                default -> send(chatId, "❌ " + escapeMd(e.getMessage()));
            }
        }
    }

    private void handleAmbiguous(long chatId, NaturalAlertService.ParsedMessage msg) {
        if (msg.symbol() == null || msg.targetPrice() == null) {
            send(chatId, "🤔 I didn't catch the coin or price\\. Try\\: _BTC 80000_", mainMenu());
            return;
        }
        pendingDirections.put(chatId, Pending.of(msg));
        NaturalAlertService.PriceContext ctx = naturalAlertService.getPriceContext(msg);
        String priceInfo = ctx.noData() ? "" :
            "  _Currently: " + fmtMd(ctx.currentPrice()) + "_";
        send(chatId,
            String.format("↕️ *%s %s* \\- above or below\\?%s",
                escapeMd(msg.symbol()), fmtMd(msg.targetPrice()), priceInfo),
            keyboard(
                row(btn("📈 Above", "dir:above"), btn("📉 Below", "dir:below")),
                row(btn("↔️ Either way \\(crosses\\)", "dir:crosses"))
            ));
    }

    private void resolveAmbiguous(long chatId, UUID userId, String direction) {
        Pending p = pendingDirections.remove(chatId);
        if (p == null) { send(chatId, "⏱ Session expired\\. Try again\\.", mainMenu()); return; }
        var resolved = new NaturalAlertService.ParsedMessage(
            "CREATE_ALERT", p.msg().symbol(), direction, p.msg().targetPrice(), null, null);
        pendingConfirmations.put(chatId, Pending.of(resolved));
        showConfirmation(chatId, resolved);
    }

    private void handlePctAlert(long chatId, NaturalAlertService.ParsedMessage msg) {
        if (msg.symbol() == null || msg.percentTarget() == null) {
            send(chatId, "❌ Try: _BTC up 10%_ or _ETH down 5%_", mainMenu());
            return;
        }
        String sym = msg.symbol().toUpperCase();
        Optional<BigDecimal> priceOpt = binanceWebSocketService.getLatestPrice(sym)
            .or(() -> naturalAlertService.fetchPriceFromRest(sym));
        if (priceOpt.isEmpty()) {
            send(chatId, "❌ *" + escapeMd(sym) + "* is not a supported Binance USDT ticker\\.");
            return;
        }
        BigDecimal current = priceOpt.get();
        BigDecimal factor  = BigDecimal.ONE.add(
            msg.percentTarget().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        BigDecimal target  = current.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        String cond  = msg.percentTarget().compareTo(BigDecimal.ZERO) >= 0 ? "ABOVE" : "BELOW";
        String arrow = msg.percentTarget().compareTo(BigDecimal.ZERO) > 0 ? "📈" : "📉";
        var resolved = new NaturalAlertService.ParsedMessage(
            "CREATE_ALERT", sym, cond, target, null, msg.percentTarget());
        pendingConfirmations.put(chatId, Pending.of(resolved));
        send(chatId, String.format(
            "*%s* %s *%s* %s\n_%s%% from current %s_",
            escapeMd(sym), cond.toLowerCase(), fmtMd(target), arrow,
            escapeMd(String.format("%+.1f", msg.percentTarget())), fmtMd(current)),
            keyboard(row(btn("✅ Create Alert", "confirm:create"), btn("❌ Cancel", "confirm:cancel"))));
    }

    private void clearPending(long chatId) {
        pendingConfirmations.remove(chatId);
        pendingDirections.remove(chatId);
        pendingDeleteAll.remove(chatId);
        pendingUnlink.remove(chatId);
    }

    @Scheduled(fixedDelay = 300_000)
    public void evictExpiredPending() {
        Instant now = Instant.now();
        pendingConfirmations.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
        pendingDirections.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
        pendingDeleteAll.entrySet().removeIf(e -> now.isAfter(e.getValue()));
        pendingUnlink.entrySet().removeIf(e -> now.isAfter(e.getValue()));
    }

    private void handleDeleteAllPrompt(long chatId, UUID userId) {
        long count = alertService.getAlerts(userId, AlertStatus.ACTIVE).size();
        if (count == 0) { send(chatId, "ℹ️ You have no active alerts\\.", mainMenu()); return; }
        pendingDeleteAll.put(chatId, Instant.now().plusSeconds(300));
        send(chatId,
            String.format("⚠️ *Delete all %d active alert%s\\?*",
                count, count == 1 ? "" : "s"),
            keyboard(row(btn("🗑 Yes, delete all", "confirm:deleteall"), btn("❌ Cancel", "confirm:cancel"))));
    }

    private void executeDeleteAll(long chatId, UUID userId) {
        Instant expiry = pendingDeleteAll.get(chatId);
        if (expiry == null || Instant.now().isAfter(expiry)) {send(chatId, "⏱ Session expired\\. Try again\\.", mainMenu()); return;}
        pendingDeleteAll.remove(chatId);
        long deleted = alertService.deleteAllAlerts(userId);
        send(chatId,
            deleted == 0
                ? "ℹ️ No active alerts to delete\\."
                : String.format("✅ *%d alert%s deleted\\.*", deleted, deleted == 1 ? "" : "s"),
            mainMenu());
    }

    private void handleUnlinkPrompt(long chatId, UUID userId) {
        pendingUnlink.put(chatId, Instant.now().plusSeconds(300));
        send(chatId,
            "⚠️ *Unlink this Telegram account\\?*",
            keyboard(row(btn("🗑 Yes, unlink", "confirm:unlink"), btn("❌ Cancel", "confirm:cancel"))));
    }

    private void executeUnlink(long chatId, UUID userId, TelegramUser tu) {
        Instant expiry = pendingUnlink.get(chatId);
        if (expiry == null || Instant.now().isAfter(expiry)) {send(chatId, "⏱ Session expired\\. Try again\\.", mainMenu()); return;}
        pendingUnlink.remove(chatId);
        telegramUserRepo.delete(tu);
        send(chatId, "✅ *Unlinked*\\. Visit the website to reconnect\\.");
    }

    private static InlineKeyboardMarkup mainMenu() {
        return keyboard(
            row(btn("➕ New Alert", "action:add"), btn("📋 My Alerts", "action:list")),
            row(btn("🕐 History", "action:history"), btn("❓ Help",      "action:help"))
        );
    }

    @SafeVarargs
    private static InlineKeyboardMarkup keyboard(List<InlineKeyboardButton>... rows) {
        return new InlineKeyboardMarkup(List.of(rows));
    }

    private static List<InlineKeyboardButton> row(InlineKeyboardButton... buttons) {
        return List.of(buttons);
    }

    private static InlineKeyboardButton btn(String label, String callbackData) {
        return InlineKeyboardButton.builder().text(label).callbackData(callbackData).build();
    }

    private void ack(String callbackQueryId) {
        try {
            execute(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).build());
        } catch (TelegramApiException ignored) {}
    }

    public void sendWithButtons(long chatId, String text) {
        send(chatId, text,
            keyboard(row(btn("➕ New Alert", "action:add"), btn("📋 My Alerts", "action:list"))));
    }

    private void send(long chatId, String text, InlineKeyboardMarkup markup) {
        try {
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(text)
                    .parseMode("MarkdownV2");
            if (markup != null) builder.replyMarkup(markup);
            execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Send failed chatId={}: {}", chatId, e.getMessage());
        }
    }

    public void send(long chatId, String text) {
        send(chatId, text, null);
    }

    private static String fmtMd(BigDecimal price) {
        return escapeMd(NaturalAlertService.formatPrice(price));
    }
}