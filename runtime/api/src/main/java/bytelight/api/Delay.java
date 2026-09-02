package bytelight.api;

import java.util.concurrent.TimeUnit;

/**
 * ByteLight delay API for ATmega328P (16 MHz).
 *
 * Lowered to a software delay loop by the ByteLight intrinsic layer.
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
     * {@code __bytelight_delay_ms(1000)}.
     */
    public static void time(long amount, TimeUnit unit) {
        ms((int) unit.toMillis(amount));
    }

    private Delay() {}
}
