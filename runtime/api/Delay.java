/**
 * TinyJava delay API for ATmega328P (16 MHz).
 *
 * Lowered to a software delay loop by the TinyJava intrinsic layer.
 *
 * {@code TimeUnit} refers to the embedded stub in the same package — see
 * {@code runtime/api/TimeUnit.java}. The standalone JDK
 * {@code java.util.concurrent.TimeUnit} cannot be lowered to AVR code.
 */
public class Delay {
    /** Busy-wait for approximately `ms` milliseconds at 16 MHz. */
    public static native void ms(int ms);

    /**
     * Busy-wait for the given duration expressed in {@code unit}.
     *
     * When both {@code amount} and {@code unit} are compile-time constants the
     * millisecond equivalent is computed statically and folded into
     * {@link #ms(int)} — e.g. {@code Delay.time(1, TimeUnit.SECONDS)} produces
     * {@code __tinyjava_delay_ms(1000)}.
     */
    public static void time(long amount, TimeUnit unit) {
        ms((int) unit.toMillis(amount));
    }

    private Delay() {}
}
