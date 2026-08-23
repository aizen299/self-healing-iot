package io.fleet.edge.constrained;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-rolled encoder replaces {@code String.format} on the constrained
 * hot path, so it has to agree with it exactly — not approximately.
 */
class PayloadBufferTest {

    @Test
    void encodesIntegers() {
        assertEquals("0", encode(b -> b.number(0)));
        assertEquals("7", encode(b -> b.number(7)));
        assertEquals("1787480547123", encode(b -> b.number(1_787_480_547_123L)));
        assertEquals("-42", encode(b -> b.number(-42)));
        assertEquals(String.valueOf(Long.MAX_VALUE), encode(b -> b.number(Long.MAX_VALUE)));
    }

    @Test
    void rejectsUnencodableLongMinValue() {
        PayloadBuffer buffer = new PayloadBuffer(64);
        assertThrows(IllegalArgumentException.class, () -> buffer.number(Long.MIN_VALUE));
    }

    @Test
    void encodesFixedPointWithExactPrecision() {
        assertEquals("21.40", encode(b -> b.fixed(21.4d, 2)));
        assertEquals("0.00", encode(b -> b.fixed(0.0d, 2)));
        assertEquals("52.5200", encode(b -> b.fixed(52.52d, 4)));
        assertEquals("-13.4050", encode(b -> b.fixed(-13.405d, 4)));
        assertEquals("100.00", encode(b -> b.fixed(100.0d, 2)));
        assertEquals("0.05", encode(b -> b.fixed(0.05d, 2)));
    }

    /**
     * The property that actually matters: the encoder and the naive variant's
     * formatter must never disagree, across the full range the sensors emit.
     */
    @Test
    void agreesWithStringFormatAcrossSensorRange() {
        for (int i = 0; i <= 20_000; i++) {
            double value = -60.0d + (i * 0.01d);
            assertFormatsIdentically(value, 2);
            assertFormatsIdentically(value, 4);
        }
    }

    @Test
    void rejectsNonFiniteValues() {
        PayloadBuffer buffer = new PayloadBuffer(64);
        assertThrows(IllegalArgumentException.class, () -> buffer.fixed(Double.NaN, 2));
        assertThrows(IllegalArgumentException.class,
                () -> buffer.fixed(Double.POSITIVE_INFINITY, 2));
    }

    @Test
    void overflowFailsLoudlyRatherThanGrowing() {
        PayloadBuffer buffer = new PayloadBuffer(4);
        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> buffer.text("far too long for four bytes"));
        assertTrue(error.getMessage().contains("overflow"), error.getMessage());
    }

    @Test
    void resetReusesTheSameArray() {
        PayloadBuffer buffer = new PayloadBuffer(64);
        byte[] first = buffer.text("alpha").array();
        byte[] second = buffer.reset().text("beta").array();

        assertEquals(4, buffer.length());
        assertTrue(first == second, "reset must not reallocate the backing array");
    }

    private static void assertFormatsIdentically(double value, int decimals) {
        String expected = String.format(Locale.ROOT, "%." + decimals + "f", value);
        String actual = encode(b -> b.fixed(value, decimals));
        assertEquals(expected, actual, "disagreement at value " + value);
    }

    private static String encode(java.util.function.Consumer<PayloadBuffer> write) {
        PayloadBuffer buffer = new PayloadBuffer(128);
        write.accept(buffer);
        return new String(buffer.array(), 0, buffer.length(), StandardCharsets.UTF_8);
    }
}
