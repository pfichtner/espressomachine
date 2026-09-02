package bytelight.api;

/**
 * ByteLight Serial API for ATmega328P (USART0).
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

    private Serial() {}
}
