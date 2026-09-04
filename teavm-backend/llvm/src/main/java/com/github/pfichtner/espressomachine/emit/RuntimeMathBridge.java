package com.github.pfichtner.espressomachine.emit;

/**
 * Bridge class for intercepted {@code java.lang.Math} calls.
 *
 * The class transformer walks user code and replaces
 * {@code java.lang.Math.min(int,int)} / {@code max} / {@code abs} /
 * {@code pow} / {@code sqrt} invokes with calls to methods here.
 * The {@link MathBridgeEmitter} then intercepts these calls and emits
 * LLVM intrinsics or external function calls.
 *
 * The methods read from a volatile field so TeaVM's optimizer cannot
 * constant-fold the calls — the field's value is unknown at compile time.
 * At runtime the call is replaced by the LLVM intrinsic, so the volatile
 * read never actually executes.
 */
public final class RuntimeMathBridge {
    private RuntimeMathBridge() {}

    /** Volatile placeholder — prevents constant-folding of bridge calls. */
    static volatile int __ph;

    /** {@code Math.min(int, int)} → {@code @llvm.smin.i32} */
    public static int mathMinInt(int a, int b) { return __ph; }

    /** {@code Math.max(int, int)} → {@code @llvm.smax.i32} */
    public static int mathMaxInt(int a, int b) { return __ph; }

    /** {@code Math.abs(int)} → {@code @llvm.abs.i32} */
    public static int mathAbsInt(int a) { return __ph; }

    /** {@code Math.pow(double, double)} → {@code @pow} */
    public static double mathPow(double a, double b) { return (double) __ph; }

    /** {@code Math.sqrt(double)} → {@code @sqrt} */
    public static double mathSqrt(double a) { return (double) __ph; }
}
