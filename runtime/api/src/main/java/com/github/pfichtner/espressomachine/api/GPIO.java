package com.github.pfichtner.espressomachine.api;

/**
 * EspressoMachine GPIO API for ATmega328P.
 *
 * Calls to these methods are recognized as intrinsics by the EspressoMachine backend
 * and lowered to AVR memory-mapped I/O operations — no Java runtime is emitted.
 *
 * Digital pin numbering follows Arduino conventions (0–13).
 * Analog channel constants A0–A5 identify ADC channels 0–5 (PC0–PC5).
 */
public class GPIO {
    public static final int OUTPUT      = 1;
    public static final int INPUT       = 0;
    public static final int INPUT_PULLUP = 2;
    public static final int HIGH        = 1;
    public static final int LOW         = 0;

    public static final int A0 = 0;
    public static final int A1 = 1;
    public static final int A2 = 2;
    public static final int A3 = 3;
    public static final int A4 = 4;
    public static final int A5 = 5;

    /** Configure a digital pin as INPUT or OUTPUT. */
    public static native void pinMode(int pin, int mode);

    /** Write HIGH or LOW to a digital output pin. */
    public static native void digitalWrite(int pin, int value);

    /** Read the current state of a digital pin; returns HIGH (1) or LOW (0). */
    public static native int digitalRead(int pin);

    /** Read 10-bit ADC value (0–1023) from analog channel A0–A5. */
    public static native int analogRead(int pin);

    /** Write 8-bit PWM duty cycle (0–255) to a PWM-capable pin (3, 5, 6, 9, 10, 11). */
    public static native void analogWrite(int pin, int value);

    private GPIO() {}
}
