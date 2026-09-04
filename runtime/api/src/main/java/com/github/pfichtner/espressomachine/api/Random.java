package com.github.pfichtner.espressomachine.api;

/**
 * EspressoMachine random number API for ATmega328P.
 *
 * Calls to these methods are recognized as intrinsics by the EspressoMachine backend
 * and lowered to a Linear Congruential Generator (LCG) in AVR machine code —
 * no Java runtime is emitted.
 *
 * The LCG uses the same constants as the GNU C library:
 * {@code state = state * 1103515245 + 12345}.
 */
public class Random {
    /** Return a pseudo-random int in the range [0, bound). */
    public static native int random(int bound);

    /** Return a pseudo-random int in the range [min, max). */
    public static native int random(int min, int max);

    private Random() {}
}
