package com.github.pfichtner.espressomachine.api;

/**
 * EspressoMachine time API for ATmega328P.
 *
 * Backed by Timer0 (prescaler /64, normal mode) with an overflow interrupt.
 * The compiler emits {@code __espressomachine_time_init()} before
 * {@code setup()} when any {@code Time.*} method is referenced; no manual
 * timer setup is needed.
 *
 * Resolution: ~1 ms (one Timer0 overflow ≈ 1.024 ms at 16 MHz).
 * Roll-over: ~49.7 days (identical to Arduino's {@code unsigned long millis()}).
 */
public class Time {

    /**
     * Returns the number of milliseconds elapsed since the program started.
     * The value wraps around to zero after approximately 49.7 days.
     */
    public static native int millis();

    private Time() {}
}
