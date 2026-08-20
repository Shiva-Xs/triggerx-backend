package com.triggerx.ai;

import com.triggerx.ai.NaturalAlertService.ParsedMessage;
import com.triggerx.price.BinanceSymbolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the fixed command phrasings without an API call.
 *
 * Scope is deliberately narrow: whole-string matches against a closed set of
 * shapes, where the message either is the command or is not. Nothing here reads
 * a number, a direction or a percentage out of free text.
 *
 * That restriction is the point. An earlier version of this class also built
 * alerts, and produced two wrong ones: "eth above 0.1 percent from current
 * price" became an absolute $0.1 target, and the percentage branch could not
 * see the words "above" or "percent" at all. Extracting meaning from a sentence
 * is what the model is for, and the provider's free tier allows 1,500 requests
 * a day, so there is nothing to save by guessing here. Alert creation, deletion
 * by symbol and every percentage now go to the model.
 *
 * What stays local are the messages a menu button would send: they cost a
 * round trip for no interpretation, and they keep working when the AI provider
 * is rate-limited or down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalIntentParser {

    private final BinanceSymbolRegistry symbolRegistry;

    /** Coin names people type instead of tickers, for price checks only. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("bitcoin", "BTC"), Map.entry("ethereum", "ETH"),
            Map.entry("ether", "ETH"), Map.entry("solana", "SOL"),
            Map.entry("dogecoin", "DOGE"), Map.entry("ripple", "XRP"),
            Map.entry("cardano", "ADA"), Map.entry("avalanche", "AVAX"),
            Map.entry("chainlink", "LINK"), Map.entry("polkadot", "DOT"),
            Map.entry("shiba", "SHIB"), Map.entry("litecoin", "LTC"),
            Map.entry("polygon", "MATIC"), Map.entry("tron", "TRX"),
            Map.entry("binance", "BNB"), Map.entry("cosmos", "ATOM"));

    private static final Set<String> GREETINGS = Set.of(
            "hi", "hey", "hello", "yo", "sup", "hola", "namaste",
            "good morning", "good evening", "good afternoon",
            "how are you", "what's up", "whats up", "wassup");

    private static final Set<String> FAREWELLS = Set.of(
            "bye", "goodbye", "good bye", "see ya", "see you", "cya", "later",
            "good night", "goodnight", "thanks", "thank you", "thx", "ty", "tysm");

    private static final Pattern LIST_RE = Pattern.compile(
            "^(show|list|view|see|get|display|check)?\\s*(me\\s+)?(my\\s+)?(all\\s+)?"
          + "(active\\s+|current\\s+)?alerts?$");
    private static final Pattern DELETE_ALL_RE = Pattern.compile(
            "^(delete|remove|clear|cancel|drop|kill|wipe)\\s+(them\\s+)?(all|everything)"
          + "(\\s+(of\\s+)?(my\\s+)?(the\\s+)?alerts?)?$");
    private static final Pattern DELETE_N_RE = Pattern.compile(
            "^(delete|remove|cancel|drop|kill)\\s+(alert\\s+|alert\\s+number\\s+|number\\s+)?"
          + "#?(\\d{1,3})$");
    /** "btc price" / "price of btc" / "what is the current price of btc". */
    private static final Pattern PRICE_SUFFIX_RE = Pattern.compile(
            "^([a-z]{2,12})\\s+price$");
    private static final Pattern PRICE_PREFIX_RE = Pattern.compile(
            "^((what\\s+is|what'?s)\\s+)?(the\\s+)?(current\\s+|live\\s+|latest\\s+)?"
          + "price\\s+(of|for)?\\s*([a-z]{2,12})$");

    public Optional<ParsedMessage> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();

        String s = raw.toLowerCase().trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[!?.]+$", "")
                .trim();
        if (s.isEmpty() || s.length() > 60) return Optional.empty();

        // A digit anywhere means a price, a percentage or an amount is involved, and
        // reading those out of a sentence is the model's job. The one exception is
        // "delete 2", which is matched before this guard.
        Matcher del = DELETE_N_RE.matcher(s);
        if (del.matches()) {
            return Optional.of(new ParsedMessage(
                    "DELETE_ALERT", null, null, null, Integer.parseInt(del.group(3)), null));
        }
        if (s.matches(".*\\d.*")) return Optional.empty();
        if (s.indexOf('%') >= 0) return Optional.empty();

        if (GREETINGS.contains(s)) return intentOnly("GREETING");
        if (FAREWELLS.contains(s)) return intentOnly("FAREWELL");
        if (LIST_RE.matcher(s).matches()) return intentOnly("LIST_ALERTS");
        if (DELETE_ALL_RE.matcher(s).matches()) return intentOnly("DELETE_ALL");

        Matcher suffix = PRICE_SUFFIX_RE.matcher(s);
        if (suffix.matches()) return priceCheck(suffix.group(1));

        Matcher prefix = PRICE_PREFIX_RE.matcher(s);
        if (prefix.matches()) return priceCheck(prefix.group(6));

        // A bare ticker or coin name on its own.
        if (s.matches("[a-z]+")) return priceCheck(s);

        return Optional.empty();
    }

    private Optional<ParsedMessage> priceCheck(String token) {
        String symbol = resolve(token);
        return symbol == null ? Optional.empty()
                : Optional.of(new ParsedMessage("PRICE_CHECK", symbol, null, null, null, null));
    }

    /**
     * Resolves a token to a ticker, or null. Only plausible ticker shapes are put to
     * the registry so ordinary English words are not read as coins. The registry is
     * empty until the Binance fetch completes, which degrades to an LLM call.
     */
    private String resolve(String token) {
        if (token == null || token.isBlank()) return null;
        String alias = ALIASES.get(token);
        if (alias != null) return alias;
        if (token.length() < 2 || token.length() > 10 || !token.matches("[a-z]+")) return null;
        String upper = token.toUpperCase();
        return symbolRegistry.isSupported(upper) ? upper : null;
    }

    private Optional<ParsedMessage> intentOnly(String intent) {
        return Optional.of(new ParsedMessage(intent, null, null, null, null, null));
    }
}
