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

    /**
     * Printf-style formatted output.
     *
     * The format string must be a compile-time string literal; the backend expands
     * it into {@code print}/{@code write} calls at compile time (no String heap, no
     * runtime formatter). Supported specifiers:
     *
     * <pre>
     *   %d   signed decimal int
     *   %u   unsigned decimal int
     *   %x   hexadecimal int (lower case)
     *   %X   hexadecimal int (upper case)
     *   %b   binary int
     *   %c   single character (int code point)
     *   %s   String literal
     *   %%   a literal percent sign
     * </pre>
     *
     * Each specifier consumes the next argument: numeric/char specifiers take an
     * {@code int} argument, {@code %s} takes a {@code String} argument. Unlike C
     * printf no newline is appended; include {@code "\r\n"} in the format if needed.
     *
     * The {@code int}/{@code String} overloads below cover every argument
     * combination of up to four arguments.
     */
    public static native void printf(String fmt);
    public static native void printf(String fmt, int a);
    public static native void printf(String fmt, String a);
    public static native void printf(String fmt, int a, int b);
    public static native void printf(String fmt, int a, String b);
    public static native void printf(String fmt, String a, int b);
    public static native void printf(String fmt, String a, String b);
    public static native void printf(String fmt, int a, int b, int c);
    public static native void printf(String fmt, int a, int b, String c);
    public static native void printf(String fmt, int a, String b, int c);
    public static native void printf(String fmt, int a, String b, String c);
    public static native void printf(String fmt, String a, int b, int c);
    public static native void printf(String fmt, String a, int b, String c);
    public static native void printf(String fmt, String a, String b, int c);
    public static native void printf(String fmt, String a, String b, String c);
    public static native void printf(String fmt, int a, int b, int c, int d);
    public static native void printf(String fmt, int a, int b, int c, String d);
    public static native void printf(String fmt, int a, int b, String c, int d);
    public static native void printf(String fmt, int a, int b, String c, String d);
    public static native void printf(String fmt, int a, String b, int c, int d);
    public static native void printf(String fmt, int a, String b, int c, String d);
    public static native void printf(String fmt, int a, String b, String c, int d);
    public static native void printf(String fmt, int a, String b, String c, String d);
    public static native void printf(String fmt, String a, int b, int c, int d);
    public static native void printf(String fmt, String a, int b, int c, String d);
    public static native void printf(String fmt, String a, int b, String c, int d);
    public static native void printf(String fmt, String a, int b, String c, String d);
    public static native void printf(String fmt, String a, String b, int c, int d);
    public static native void printf(String fmt, String a, String b, int c, String d);
    public static native void printf(String fmt, String a, String b, String c, int d);
    public static native void printf(String fmt, String a, String b, String c, String d);

    private Serial() {}
}
