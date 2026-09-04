package com.github.pfichtner.espressomachine.emit;

/**
 * Bridge class for intercepted {@code java.util.Random} calls.
 *
 * The class transformer walks user code and replaces
 * {@code java.util.Random.nextInt()} / {@code nextInt(int)} /
 * {@code nextLong()} invokes with calls to methods here.
 * The {@link com.github.pfichtner.espressomachine.emit.RandomBridgeEmitter}
 * then intercepts these calls and emits {@code @__random_next_int} etc.
 *
 * The methods read from a volatile field so TeaVM's optimizer cannot
 * constant-fold the calls — the field's value is unknown at compile time.
 * At runtime the call is replaced by the AVR intrinsic, so the volatile
 * read never actually executes.
 */
public final class RuntimeRandomBridge {
    private RuntimeRandomBridge() {}

    /** Volatile placeholder — prevents constant-folding of bridge calls. */
    static volatile int __ph;

    /** {@code java.util.Random.next(int bits)} → {@code @__random_next} */
    public static int randomNext(int bits) { return __ph; }

    /** {@code java.util.Random.nextInt()} → {@code @__random_next_int} */
    public static int randomNextInt() { return __ph; }

    /** {@code java.util.Random.nextInt(int bound)} → {@code @__random_next_int_bound} */
    public static int randomNextIntBound(int bound) { return __ph; }

    /** {@code java.util.Random.nextLong()} → {@code @__random_next_long_lo} (low 32 bits) */
    public static long randomNextLong() { return (long) __ph; }
}
