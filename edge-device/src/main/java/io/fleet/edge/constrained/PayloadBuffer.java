package io.fleet.edge.constrained;

/**
 * A fixed-capacity byte buffer with allocation-free encoding primitives.
 *
 * <p>Allocated once per device and reused for every reading. Nothing here
 * produces garbage: integers and fixed-point decimals are written digit by
 * digit into the existing array, and text is copied character by character
 * rather than via {@code String.getBytes}, which would allocate.
 *
 * <p>This is the J2ME-style discipline the research is about, applied on a
 * modern JVM: a bounded, pre-sized buffer with an explicit overflow error
 * instead of a structure that silently grows.
 *
 * <p>Not thread-safe; one instance per device.
 */
public final class PayloadBuffer {

    private static final long[] POW10 = {
            1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L,
            10_000_000L, 100_000_000L, 1_000_000_000L
    };

    private final byte[] buffer;
    private int length;

    public PayloadBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.buffer = new byte[capacity];
    }

    /** Encodes a string as ASCII bytes, for compile-time literals. */
    public static byte[] ascii(String text) {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            out[i] = (byte) text.charAt(i);
        }
        return out;
    }

    public PayloadBuffer reset() {
        length = 0;
        return this;
    }

    /** Appends precomputed bytes. */
    public PayloadBuffer raw(byte[] bytes) {
        require(bytes.length);
        System.arraycopy(bytes, 0, buffer, length, bytes.length);
        length += bytes.length;
        return this;
    }

    /** Appends a string's characters as ASCII, without allocating. */
    public PayloadBuffer text(CharSequence text) {
        int n = text.length();
        require(n);
        for (int i = 0; i < n; i++) {
            buffer[length++] = (byte) text.charAt(i);
        }
        return this;
    }

    /** Appends a base-10 integer. */
    public PayloadBuffer number(long value) {
        if (value == Long.MIN_VALUE) {
            // Negating this overflows; it cannot occur for a timestamp, but
            // failing loudly beats emitting a corrupt payload.
            throw new IllegalArgumentException("Long.MIN_VALUE is not encodable");
        }
        if (value < 0) {
            require(1);
            buffer[length++] = '-';
            value = -value;
        }
        if (value == 0) {
            require(1);
            buffer[length++] = '0';
            return this;
        }
        int start = length;
        while (value > 0) {
            require(1);
            buffer[length++] = (byte) ('0' + (int) (value % 10));
            value /= 10;
        }
        reverse(start, length - 1);
        return this;
    }

    /**
     * Appends a fixed-point decimal with exactly {@code decimals} places.
     *
     * <p>Rounds half-up on the value's binary representation, matching what
     * {@code String.format("%.Nf", v)} produces for the same input — the
     * naive variant uses that, and ADR-003 requires the two to agree byte for
     * byte.
     */
    public PayloadBuffer fixed(double value, int decimals) {
        if (decimals < 0 || decimals >= POW10.length) {
            throw new IllegalArgumentException("unsupported decimals: " + decimals);
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("cannot encode non-finite value: " + value);
        }
        if (value < 0) {
            require(1);
            buffer[length++] = '-';
            value = -value;
        }
        long scale = POW10[decimals];
        double widened = value * scale;
        // Math.round saturates at Long.MAX_VALUE rather than failing, so
        // without this the method would encode the saturated digits as if they
        // were the input — a corrupt payload that still looks well formed. The
        // NaN and infinity checks above already establish that unencodable
        // input must fail loudly.
        if (widened > (double) Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "value too large to encode with " + decimals + " decimals: " + value);
        }
        long scaled = Math.round(widened);
        number(scaled / scale);
        if (decimals == 0) {
            return this;
        }
        require(1);
        buffer[length++] = '.';
        long fraction = scaled % scale;
        for (long divisor = scale / 10; divisor >= 1; divisor /= 10) {
            require(1);
            buffer[length++] = (byte) ('0' + (int) ((fraction / divisor) % 10));
        }
        return this;
    }

    /** The backing array. Valid only up to {@link #length()}, and reused. */
    public byte[] array() {
        return buffer;
    }

    public int length() {
        return length;
    }

    public int capacity() {
        return buffer.length;
    }

    private void reverse(int from, int to) {
        while (from < to) {
            byte tmp = buffer[from];
            buffer[from++] = buffer[to];
            buffer[to--] = tmp;
        }
    }

    private void require(int extra) {
        if (length + extra > buffer.length) {
            throw new IllegalStateException(
                    "payload buffer overflow: capacity " + buffer.length
                            + ", needed at least " + (length + extra));
        }
    }
}
