package com.github.pfichtner.espressomachine.api;

/**
 * Arduino-compatible utility functions.
 *
 * All methods are pure Java and will be inlined by TeaVM — no AVR intrinsic
 * or runtime declaration is needed.
 */
public class Functions {

    /**
     * Re-maps a number from one range to another.
     * Equivalent to Arduino's {@code map()} macro.
     */
    public static int map(int value, int fromLow, int fromHigh, int toLow, int toHigh) {
        return (value - fromLow) * (toHigh - toLow) / (fromHigh - fromLow) + toLow;
    }

    /**
     * Constrains a number to be within a range.
     * Equivalent to Arduino's {@code constrain()} macro.
     */
    public static int constrain(int value, int min, int max) {
        return value < min ? min : value > max ? max : value;
    }

    private Functions() {}
}
