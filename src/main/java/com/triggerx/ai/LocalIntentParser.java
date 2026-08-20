package com.triggerx.ai;

import com.triggerx.ai.NaturalAlertService.ParsedMessage;
import com.triggerx.price.BinanceSymbolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic first pass over a user message.
 *
 * The overwhelming majority of what people actually type ("btc above 80000",
 * "eth price", "delete all") is rigidly structured and does not need an LLM.
 * Resolving those here keeps them off the Groq free tier, which caps us at
 * 8K tokens/min (~7 calls). Anything this parser is not confident about
 * returns empty and falls through to {@link NaturalAlertService#parseIntent}.
 *
 * The bias is deliberately toward returning empty: a wrong local match creates
 * a wrong alert, whereas a miss only costs one API call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalIntentParser {

    private final BinanceSymbolRegistry symbolRegistry;

    /** Coin names people type instead of tickers. Mirrors the LLM system prompt. */
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
            "hi", "hey", "hello", "yo", "sup", "hola", "good morning",
            "good evening", "good afternoon", "how are you", "what's up", "whats up");

    private static final Set<String> FAREWELLS = Set.of(
            "bye", "goodbye", "see ya", "see you", "cya", "later",
            "thanks", "thank you", "thx", "ty");

    // Direction keywords. Longest-first matters: "goes above" must win over "above"
    // only insofar as both map to ABOVE, but "drops below" must not match ABOVE.
    private static final Pattern ABOVE  = Pattern.compile(
            "\\b(above|over|past|exceeds?|greater than|more than|higher than|breaks?)\\b");
    private static final Pattern BELOW  = Pattern.compile(
            "\\b(below|under|beneath|less than|lower than|dips? (to|below)|drops? (to|below)|falls? (to|below))\\b");
    private static final Pattern CROSSES = Pattern.compile(
            "\\b(hits?|reaches|reach|crosses|crossing|touches|touch|at)\\b");

    private static final Pattern LIST_RE = Pattern.compile(
            "^(show|list|view|see|get|display)?\\s*(me|my)?\\s*(active\\s+)?alerts?\\??$");
    private static final Pattern DELETE_ALL_RE = Pattern.compile(
            "^(delete|remove|clear|cancel|drop)\\s+(all|everything)(\\s+(my\\s+)?alerts?)?$");
    private static final Pattern DELETE_N_RE = Pattern.compile(
            "^(delete|remove|cancel|drop)\\s+(alert\\s+)?#?(\\d{1,3})$");
    /** A percentage was meant, whether written as a sign or spelled out. */
    private static final Pattern PCT_MARKER = Pattern.compile("%|\\bpercent(age)?\\b|\\bpct\\b");
    private static final Pattern PCT_VALUE = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(?:%|percent(?:age)?\\b|pct\\b)");
    private static final Pattern PCT_DOWN = Pattern.compile(
            "\\b(down|drops?|dips?|falls?|loses?|below|under|decreases?)\\b");
    private static final Pattern PCT_UP = Pattern.compile(
            "\\b(up|rises?|gains?|above|over|increases?|higher)\\b");
    private static final Pattern PRICE_RE = Pattern.compile(
            "^(what'?s?\\s+)?(the\\s+)?(current\\s+)?price\\s+(of|for)?\\s*([a-z]{2,10})\\??$"
          + "|^([a-z]{2,10})\\s+price\\??$");
    /** A bare number, optionally with thousands separators and a k/m suffix. */
    private static final Pattern NUMBER_RE = Pattern.compile(
            "\\b([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([km])?\\b");

    public Optional<ParsedMessage> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();

        String s = raw.toLowerCase().trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[!.]+$", "");
        if (s.length() > 120) return Optional.empty();   // long prose, so let the LLM read it

        if (GREETINGS.contains(s)) return msg("GREETING");
        if (FAREWELLS.contains(s)) return msg("FAREWELL");
        if (LIST_RE.matcher(s).matches()) return msg("LIST_ALERTS");
        if (DELETE_ALL_RE.matcher(s).matches()) return msg("DELETE_ALL");

        Matcher del = DELETE_N_RE.matcher(s);
        if (del.matches()) {
            return Optional.of(new ParsedMessage(
                    "DELETE_ALERT", null, null, null, Integer.parseInt(del.group(3)), null));
        }

        Matcher price = PRICE_RE.matcher(s);
        if (price.matches()) {
            String sym = resolve(price.group(5) != null ? price.group(5) : price.group(6));
            if (sym != null) return Optional.of(
                    new ParsedMessage("PRICE_CHECK", sym, null, null, null, null));
            return Optional.empty();
        }

        // Everything below needs a symbol. Bail early if we cannot find exactly one.
        String symbol = soleSymbol(s);
        if (symbol == null) return Optional.empty();

        // Any percentage phrasing is a move relative to the current price, never an
        // absolute target. Handle every one of them here so none can fall through to
        // the absolute-price branch below.
        if (PCT_MARKER.matcher(s).find()) return percentAlert(s, symbol);

        BigDecimal target = soleNumber(s, symbol);
        if (target == null || target.signum() <= 0) {
            // "btc" or "bitcoin" on its own is a price check.
            return s.equals(symbol.toLowerCase()) || ALIASES.containsKey(s)
                    ? Optional.of(new ParsedMessage("PRICE_CHECK", symbol, null, null, null, null))
                    : Optional.empty();
        }

        String condition = direction(s);
        String intent = condition == null ? "AMBIGUOUS" : "CREATE_ALERT";
        return Optional.of(new ParsedMessage(intent, symbol, condition, target, null, null));
    }

    /**
     * Builds a PCT_ALERT, or defers when the phrasing is not a plain "SYM up N percent".
     * Misreading a percentage as an absolute price produces an alert that is already met
     * and fires the instant it is created, so anything less than unambiguous goes to the LLM.
     */
    private Optional<ParsedMessage> percentAlert(String s, String symbol) {
        Matcher value = PCT_VALUE.matcher(s);
        if (!value.find()) return Optional.empty();   // a marker with no number attached
        BigDecimal amount = new BigDecimal(value.group(1));
        if (value.find()) return Optional.empty();    // more than one percentage
        boolean down = PCT_DOWN.matcher(s).find();
        boolean up   = PCT_UP.matcher(s).find();
        if (down == up) return Optional.empty();      // no direction, or a contradictory one
        return Optional.of(new ParsedMessage(
                "PCT_ALERT", symbol, null, null, null, down ? amount.negate() : amount));
    }

    /** BELOW is tested first so "drops below" is never mistaken for a CROSSES "to". */
    private String direction(String s) {
        if (BELOW.matcher(s).find())   return "BELOW";
        if (ABOVE.matcher(s).find())   return "ABOVE";
        if (CROSSES.matcher(s).find()) return "CROSSES";
        return null;
    }

    /**
     * Returns the single symbol mentioned, or null when there are none or several.
     * Ambiguity is handed to the LLM rather than guessed at.
     */
    private String soleSymbol(String s) {
        String found = null;
        for (String token : s.split("[^a-z0-9]+")) {
            String sym = resolve(token);
            if (sym == null) continue;
            if (found != null && !found.equals(sym)) return null;
            found = sym;
        }
        return found;
    }

    private String resolve(String token) {
        if (token == null || token.isBlank()) return null;
        String alias = ALIASES.get(token);
        if (alias != null) return alias;
        // Only trust the registry for plausible ticker shapes, so ordinary English
        // words are not resolved as coins. Registry is empty until the Binance
        // fetch completes, which safely degrades to an LLM call.
        if (token.length() < 2 || token.length() > 10 || !token.matches("[a-z]+")) return null;
        String upper = token.toUpperCase();
        return symbolRegistry.isSupported(upper) ? upper : null;
    }

    /** Extracts the target price, skipping digits that are part of the ticker itself. */
    private BigDecimal soleNumber(String s, String symbol) {
        Matcher m = NUMBER_RE.matcher(s.replace(symbol.toLowerCase(), " "));
        BigDecimal found = null;
        while (m.find()) {
            BigDecimal value = new BigDecimal(m.group(1).replace(",", ""));
            String suffix = m.group(2);
            if ("k".equals(suffix)) value = value.movePointRight(3);
            else if ("m".equals(suffix)) value = value.movePointRight(6);
            if (found != null && found.compareTo(value) != 0) return null;   // two numbers, so defer
            found = value;
        }
        return found;
    }

    private Optional<ParsedMessage> msg(String intent) {
        return Optional.of(new ParsedMessage(intent, null, null, null, null, null));
    }
}
