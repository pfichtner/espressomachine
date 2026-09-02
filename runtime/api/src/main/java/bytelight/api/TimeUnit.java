package bytelight.api;

/**
 * ByteLight embedded time unit enum (mirrors the constants of the JDK's
 * {@code java.util.concurrent.TimeUnit}).
 *
 * The standalone JDK enum cannot be lowered to AVR code: its static
 * initializer pulls in the {@code jdk.internal.*} machinery (Unsafe, arrays,
 * String) that the embedded target does not support, and its enum globals are
 * never emitted by the ByteLight backend.
 */
public enum TimeUnit {
    NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS;

    /** Convert {@code amount} in this unit to milliseconds. */
    public long toMillis(long amount) {
        if (this == NANOSECONDS) {
            return amount / 1_000_000;
        }
        if (this == MICROSECONDS) {
            return amount / 1_000;
        }
        if (this == SECONDS) {
            return amount * 1_000;
        }
        if (this == MINUTES) {
            return amount * 60_000;
        }
        if (this == HOURS) {
            return amount * 3_600_000;
        }
        if (this == DAYS) {
            return amount * 86_400_000;
        }
        return amount;
    }
}