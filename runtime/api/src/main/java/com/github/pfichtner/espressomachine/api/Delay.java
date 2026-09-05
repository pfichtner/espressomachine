package com.github.pfichtner.espressomachine.api;

import java.util.concurrent.TimeUnit;

/**
 * EspressoMachine delay API for ATmega328P (16 MHz).
 *
 * Lowered to a software delay loop by the EspressoMachine intrinsic layer.
 */
public class Delay {
    /** Busy-wait for approximately {@code ms} milliseconds at 16 MHz. */
    public static native void delay(int ms);

    /** Busy-wait for approximately {@code ms} milliseconds at 16 MHz. */
    public static native void delay(long ms);

    /**
     * Busy-wait for the given duration expressed in {@code unit}.
     *
     * When both {@code amount} and {@code unit} are compile-time constants the
     * millisecond equivalent is computed statically and folded into
     * {@link #delay(int)} — e.g. {@code Delay.delay(1, TimeUnit.SECONDS)} produces
     * {@code __espressomachine_delay_ms(1000)}.
     */
    public static void delay(long amount, TimeUnit unit) {
        delay((int) unit.toMillis(amount));
    }

    private Delay() {}
}
