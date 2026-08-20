package com.triggerx.ai;

import com.triggerx.ai.NaturalAlertService.ParsedMessage;
import com.triggerx.price.BinanceSymbolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalIntentParserTest {

    private static final Set<String> LISTED = Set.of(
            "BTC", "ETH", "SOL", "DOGE", "XRP", "ADA", "LINK", "PEPE", "NEAR", "ATOM", "BNB");

    /** The only intents this parser is allowed to claim. */
    private static final Set<String> ALLOWED = Set.of(
            "GREETING", "FAREWELL", "LIST_ALERTS", "DELETE_ALL", "DELETE_ALERT", "PRICE_CHECK");

    @Mock private BinanceSymbolRegistry symbolRegistry;
    private LocalIntentParser parser;

    @BeforeEach
    void setUp() {
        when(symbolRegistry.isSupported(anyString()))
                .thenAnswer(i -> LISTED.contains(i.getArgument(0, String.class).toUpperCase()));
        parser = new LocalIntentParser(symbolRegistry);
    }

    private ParsedMessage parse(String t) {
        return parser.parse(t).orElseThrow(() -> new AssertionError("expected a local match: " + t));
    }

    private void assertDefers(String t) {
        assertTrue(parser.parse(t).isEmpty(), "must defer to the LLM: " + t);
    }

    // ─── The invariant that makes the class safe ────────────────────────────

    /**
     * The parser must never claim an intent that builds an alert, and must never
     * emit a price or a percentage. Both production bugs were violations of this.
     */
    @Test
    void neverClaimsAnAlertBuildingIntentAcrossAGeneratedCorpus() {
        List<String> corpus = new ArrayList<>();
        String[] syms = {"btc", "eth", "bitcoin", "ethereum", "sol", "doge", "BTC", "Eth"};
        String[] dirs = {"above", "below", "over", "under", "hits", "crosses", "reaches",
                         "up", "down", "drops below", "rises above", "falls", "gains", "past", ""};
        String[] nums = {"80000", "0.1", "2", "70k", "1.5m", "80,000", "2300.55", "5"};
        String[] pcts = {"%", " percent", " pct", " percentage", "  %", " PERCENT"};
        String[] leads = {"", "alert me when ", "notify if ", "tell me when ", "ping me if ",
                          "buzz me the moment ", "please ", "can you alert when "};
        String[] tails = {"", " please", " from current price", " from currrent price",
                          " right now", " thanks", "!", "?", "."};

        for (String sym : syms)
            for (String dir : dirs)
                for (String num : nums) {
                    for (String lead : leads) corpus.add(lead + sym + " " + dir + " " + num);
                    for (String pct : pcts)  corpus.add(sym + " " + dir + " " + num + pct);
                    for (String tail : tails) corpus.add(sym + " " + dir + " " + num + tail);
                }

        int deferred = 0;
        for (String text : corpus) {
            Optional<ParsedMessage> got = parser.parse(text);
            if (got.isEmpty()) { deferred++; continue; }
            ParsedMessage m = got.get();
            assertTrue(ALLOWED.contains(m.intent()),
                    "claimed a forbidden intent " + m.intent() + " for: " + text);
            assertNull(m.targetPrice(), "emitted a price for: " + text);
            assertNull(m.percentTarget(), "emitted a percentage for: " + text);
        }
        assertTrue(corpus.size() > 3000, "corpus too small: " + corpus.size());
        // Every one of these describes an alert, so every one belongs to the model.
        assertEquals(corpus.size(), deferred,
                "some alert phrasings were handled locally instead of deferred");
    }

    @Test
    void defersTheExactMessagesThatCausedProductionBugs() {
        assertDefers("Eth above 0.1 percent from currrent price");
        assertDefers("Eth above 2 percent");
        assertDefers("Eth 5 percent");
        assertDefers("eth above 0.1");
        assertDefers("btc above 80000");
        assertDefers("bitcoin 70k");
        assertDefers("btc up 10%");
        assertDefers("notify if eth falls 5%");
        assertDefers("delete my btc alerts");
    }

    // ─── What it does handle ────────────────────────────────────────────────

    @Test
    void handlesListAlerts() {
        for (String t : new String[]{"alerts", "my alerts", "show my alerts", "list alerts",
                "view my alerts", "show me my alerts", "display active alerts",
                "check my current alerts", "ALERTS", "  my   alerts  ", "my alerts?"})
            assertEquals("LIST_ALERTS", parse(t).intent(), t);
    }

    @Test
    void handlesDeleteAll() {
        for (String t : new String[]{"delete all", "clear all", "remove all", "cancel all",
                "delete all alerts", "remove all my alerts", "clear everything",
                "delete them all", "wipe all alerts", "DELETE ALL!"})
            assertEquals("DELETE_ALL", parse(t).intent(), t);
    }

    @Test
    void handlesDeleteByNumber() {
        assertEquals(2, parse("delete 2").deleteTarget());
        assertEquals(2, parse("delete alert 2").deleteTarget());
        assertEquals(4, parse("remove #4").deleteTarget());
        assertEquals(7, parse("cancel alert number 7").deleteTarget());
        assertEquals(11, parse("delete 11").deleteTarget());
    }

    @Test
    void handlesPriceChecks() {
        for (String t : new String[]{"btc price", "BTC price", "price of btc", "price for eth",
                "price btc", "what is the price of btc", "what's the current price of btc",
                "the latest price of sol", "btc", "bitcoin", "ethereum", "  ETH  "}) {
            ParsedMessage m = parse(t);
            assertEquals("PRICE_CHECK", m.intent(), t);
            assertNull(m.targetPrice(), t);
        }
        assertEquals("BTC", parse("price of bitcoin").symbol());
        assertEquals("ETH", parse("ethereum").symbol());
    }

    @Test
    void handlesGreetingsAndFarewells() {
        for (String t : new String[]{"hi", "hello", "hey", "how are you", "good morning", "HELLO!"})
            assertEquals("GREETING", parse(t).intent(), t);
        for (String t : new String[]{"bye", "goodbye", "thanks", "thank you", "ty", "good night"})
            assertEquals("FAREWELL", parse(t).intent(), t);
    }

    // ─── Boundaries ─────────────────────────────────────────────────────────

    @Test
    void defersEverythingElse() {
        for (String t : new String[]{"", "   ", "wen lambo ser", "hello there", "can you help me",
                "what can this bot do", "delete", "delete all my btc alerts", "price",
                "price of", "price of tesla", "set something up", "help", "/start",
                "i want to buy bitcoin", "is bitcoin going up",
                "a very long message that goes well past the sixty character ceiling on purpose"})
            assertDefers(t);
    }

    @Test
    void doesNotReadEnglishWordsAsTickers() {
        for (String t : new String[]{"price of tesla", "price of nothing", "help", "status",
                "settings", "cancel", "stop", "start"})
            assertDefers(t);
    }

    @Test
    void anyDigitOrPercentSignDefersExceptDeleteByNumber() {
        for (String t : new String[]{"btc 5", "alerts 2", "price of btc 5", "eth 100%",
                "50 percent", "show 3 alerts", "100"})
            assertDefers(t);
        assertEquals(2, parse("delete 2").deleteTarget());   // the single carve-out
    }

    @Test
    void handlesNullAndWhitespaceWithoutThrowing() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("\n\t  ").isEmpty());
        assertTrue(parser.parse("!!!").isEmpty());
    }

    @Test
    void defersWhenTheSymbolRegistryHasNotLoaded() {
        when(symbolRegistry.isSupported(anyString())).thenReturn(false);
        LocalIntentParser cold = new LocalIntentParser(symbolRegistry);
        assertTrue(cold.parse("btc price").isEmpty());
        assertTrue(cold.parse("sol").isEmpty());
        // Alias-backed names still resolve, and command shapes never needed the registry.
        assertEquals("BTC", cold.parse("bitcoin").orElseThrow().symbol());
        assertEquals("LIST_ALERTS", cold.parse("my alerts").orElseThrow().intent());
    }
}
