package com.github.pfichtner.espressomachine.api;

/**
 * EspressoMachine Serial API for ATmega328P (USART0).
 *
 * Serial.begin() and Serial.write() are AVR intrinsics: the backend lowers them
 * to USART register stores / a busy-wait TX loop.  All other methods are
 * implemented in Java on top of write() and will be inlined by TeaVM.
 *
 * Baud rates passed to begin() must be compile-time integer constants; the
 * backend computes UBRR at compile time (F_CPU = 16 000 000 Hz assumed).
 */
public class Serial {

    /** Initialise USART0 at the given baud rate (must be a compile-time constant). */
    public static native void begin(int baud);

    /** Transmit a single byte (busy-waits until the TX register is empty). */
    public static native void write(int b);

    /** Returns 1 if at least one byte is waiting in the receive buffer, 0 otherwise. */
    public static native int available();

    /** Read one received byte (0–255). Only call after available() returns non-zero. */
    public static native int read();

    /** Transmit a single character. */
    public static void print(char c) { write(c); }

    /**
     * Transmit a decimal integer.
     * Handles negative numbers; uses a recursive digit-first approach.
     */
    public static void print(int n) {
        if (n < 0) { write('-'); n = -n; }
        if (n >= 10) print(n / 10);
        write('0' + n % 10);
    }

    /** Transmit CR+LF. */
    public static void println() { write('\r'); write('\n'); }

    /** Transmit a character followed by CR+LF. */
    public static void println(char c) { print(c); println(); }

    /** Transmit a decimal integer followed by CR+LF. */
    public static void println(int n) { print(n); println(); }

    /**
     * Transmit a string.
     *
     * Native so the backend emits {@code __espressomachine_serial_print_str} directly;
     * Java String iteration (length()/charAt()) is not supported on the embedded
     * target. Only string literals are supported.
     */
    public static native void print(String s);

    /**
     * Transmit a string followed by CR+LF.
     *
     * Native so the backend emits {@code __espressomachine_serial_print_str} directly,
     * then CR+LF. Only string literals are supported.
     */
    public static native void println(String s);

    private Serial() {}
}
