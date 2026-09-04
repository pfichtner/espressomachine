package com.github.pfichtner.espressomachine.emit;

/**
 * Shared declarations for the {@code __random_next*} AVR runtime intrinsics.
 *
 * Both {@link JavaRandomEmitter} (direct {@code java.util.Random} calls) and
 * {@link RandomBridgeEmitter} (transformed bridge calls) lower to the same
 * 48-bit LCG intrinsics, so the required {@code declare} text is shared.
 * A plain utility class is used instead of one emitter referencing the other
 * so that sibling emitters stay decoupled.
 */
final class RandomIntrinsics {

    private RandomIntrinsics() {}

    static final String DECLARATIONS = """
            declare i32 @__random_next(i32 %bits)
            declare i32 @__random_next_int()
            declare i32 @__random_next_int_bound(i32 %bound)
            declare i32 @__random_next_long_lo()
            declare i32 @__random_next_long_hi()
            """;
}