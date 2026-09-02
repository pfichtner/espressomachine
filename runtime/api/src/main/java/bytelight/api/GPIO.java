package bytelight.api;

/**
 * ByteLight GPIO API for ATmega328P.
 *
 * Calls to these methods are recognized as intrinsics by the ByteLight backend
 * and lowered to AVR memory-mapped I/O operations — no Java runtime is emitted.
 *
 * Pin numbering follows Arduino conventions (digital pins 0–13).
 */
public class GPIO {
    public static final int OUTPUT = 1;
    public static final int INPUT  = 0;
    public static final int HIGH   = 1;
    public static final int LOW    = 0;

    /** Configure a digital pin as INPUT or OUTPUT. */
    public static native void pinMode(int pin, int mode);

    /** Write HIGH or LOW to a digital output pin. */
    public static native void digitalWrite(int pin, int value);

    private GPIO() {}
}
