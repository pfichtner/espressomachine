/**
 * TinyJava delay API for ATmega328P (16 MHz).
 *
 * Lowered to a software delay loop by the TinyJava intrinsic layer.
 */
public class Delay {
    /** Busy-wait for approximately `ms` milliseconds at 16 MHz. */
    public static native void ms(int ms);

    private Delay() {}
}
