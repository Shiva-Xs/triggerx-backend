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

import java.math.BigDecimal;
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

    private static final Set<String> LISTED =
            Set.of("BTC", "ETH", "SOL", "DOGE", "XRP", "ADA", "LINK", "PEPE", "NEAR", "ATOM");

    @Mock
    private BinanceSymbolRegistry symbolRegistry;

    private LocalIntentParser parser;

    @BeforeEach
    void setUp() {
        when(symbolRegistry.isSupported(anyString()))
                .thenAnswer(inv -> LISTED.contains(inv.getArgument(0, String.class).toUpperCase()));
        parser = new LocalIntentParser(symbolRegistry);
    }

    private ParsedMessage parse(String text) {
        return parser.parse(text).orElseThrow(
                () -> new AssertionError("expected a local match for: " + text));
    }

    private void assertDefers(String text) {
        assertTrue(parser.parse(text).isEmpty(), "expected fall-through to LLM for: " + text);
    }

    private void assertAlert(String text, String symbol, String condition, String price) {
        ParsedMessage m = parse(text);
        assertEquals("CREATE_ALERT", m.intent(), text);
        assertEquals(symbol, m.symbol(), text);
        assertEquals(condition, m.condition(), text);
        assertEquals(0, new BigDecimal(price).compareTo(m.targetPrice()), text);
    }

    @Test
    void parsesDirectionalAlerts() {
        assertAlert("btc above 80000", "BTC", "ABOVE", "80000");
        assertAlert("BTC above 80,000", "BTC", "ABOVE", "80000");
        assertAlert("ethereum drops below 2000", "ETH", "BELOW", "2000");
        assertAlert("alert when bitcoin hits 73400", "BTC", "CROSSES", "73400");
        assertAlert("notify me at sol 150", "SOL", "CROSSES", "150");
        assertAlert("alert me when btc goes above 90k", "BTC", "ABOVE", "90000");
        assertAlert("tell me if eth falls below 1.5k", "ETH", "BELOW", "1500");
    }

    @Test
    void treatsMissingDirectionAsAmbiguous() {
        ParsedMessage m = parse("bitcoin 70k");
        assertEquals("AMBIGUOUS", m.intent());
        assertEquals("BTC", m.symbol());
        assertEquals(0, new BigDecimal("70000").compareTo(m.targetPrice()));
        assertEquals("AMBIGUOUS", parse("btc 80000").intent());
    }

    @Test
    void parsesPercentageAlerts() {
        ParsedMessage up = parse("btc up 10%");
        assertEquals("PCT_ALERT", up.intent());
        assertEquals("BTC", up.symbol());
        assertEquals(0, new BigDecimal("10").compareTo(up.percentTarget()));

        ParsedMessage down = parse("notify if eth falls 5%");
        assertEquals("PCT_ALERT", down.intent());
        assertEquals(0, new BigDecimal("-5").compareTo(down.percentTarget()));
    }

    @Test
    void treatsTheSpelledOutWordPercentAsAPercentage() {
        // Reported from the Telegram bot: "Eth above 0.1 percent from currrent price"
        // was read as an absolute $0.1 target and fired immediately.
        ParsedMessage m = parse("eth above 0.1 percent from currrent price");
        assertEquals("PCT_ALERT", m.intent());
        assertEquals("ETH", m.symbol());
        assertEquals(0, new BigDecimal("0.1").compareTo(m.percentTarget()));

        ParsedMessage two = parse("eth above 2 percent");
        assertEquals("PCT_ALERT", two.intent());
        assertEquals(0, new BigDecimal("2").compareTo(two.percentTarget()));

        ParsedMessage down = parse("btc below 5 percent");
        assertEquals("PCT_ALERT", down.intent());
        assertEquals(0, new BigDecimal("-5").compareTo(down.percentTarget()));
    }

    @Test
    void neverReadsAPercentageAsAnAbsolutePrice() {
        for (String text : new String[]{
                "eth above 0.1 percent from currrent price", "eth above 2 percent",
                "btc up 3 pct", "sol down 4 percentage", "btc above 1.5%"}) {
            ParsedMessage m = parser.parse(text).orElse(null);
            if (m == null) continue;                    // deferring to the LLM is acceptable
            assertEquals("PCT_ALERT", m.intent(), text);
            assertNull(m.targetPrice(), "percentage must not become an absolute price: " + text);
        }
    }

    @Test
    void parsesPriceChecks() {
        assertEquals("PRICE_CHECK", parse("btc price").intent());
        assertEquals("BTC", parse("price of bitcoin").symbol());
        assertEquals("BTC", parse("what's the price of btc").symbol());
        assertEquals("PRICE_CHECK", parse("btc").intent());
        assertEquals("BTC", parse("bitcoin").symbol());
    }

    @Test
    void parsesListAndDelete() {
        assertEquals("LIST_ALERTS", parse("show my alerts").intent());
        assertEquals("LIST_ALERTS", parse("alerts").intent());
        assertEquals("LIST_ALERTS", parse("list alerts").intent());
        assertEquals("DELETE_ALL", parse("delete all alerts").intent());
        assertEquals("DELETE_ALL", parse("clear all").intent());
        assertEquals("DELETE_ALL", parse("remove all my alerts").intent());
        assertEquals(2, parse("delete alert 2").deleteTarget());
        assertEquals(3, parse("delete 3").deleteTarget());
    }

    @Test
    void parsesGreetings() {
        assertEquals("GREETING", parse("hi").intent());
        assertEquals("GREETING", parse("hello").intent());
        assertEquals("GREETING", parse("how are you").intent());
        assertEquals("FAREWELL", parse("bye").intent());
        assertEquals("FAREWELL", parse("thanks").intent());
    }

    @Test
    void isCaseAndPunctuationInsensitive() {
        assertAlert("BTC ABOVE 80000!", "BTC", "ABOVE", "80000");
        assertAlert("  Bitcoin   Above   80000  ", "BTC", "ABOVE", "80000");
    }

    @Test
    void defersAnythingItCannotResolveConfidently() {
        assertDefers("delete my btc alerts");                 // symbol-scoped delete
        assertDefers("wen lambo ser");                        // no symbol, no intent
        assertDefers("btc above 80000 and eth below 2000");   // two symbols
        assertDefers("alert me between 70000 and 80000 btc"); // two numbers
        assertDefers("swap my btc for eth");                  // two symbols
        assertDefers("");
        assertDefers("   ");
        assertDefers("I was thinking that maybe if bitcoin were to go somewhere "
                   + "near about eighty thousand dollars you could ping me");  // long prose
    }

    @Test
    void doesNotMistakeEnglishWordsForTickers() {
        assertDefers("can you help me");
        assertDefers("what can this bot do");
        assertDefers("set something up for me");
    }
}
