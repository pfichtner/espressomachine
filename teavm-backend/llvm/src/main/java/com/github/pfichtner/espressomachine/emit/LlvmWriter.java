package com.github.pfichtner.espressomachine.emit;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Minimal DSL for emitting textual LLVM IR lines.
 *
 * Owns the SSA temporary counter so callers don't have to thread an {@code int}
 * through their methods, and centralises the textual LLVM mechanics (indent,
 * temp naming, {@code inttoptr} casts, i8 arithmetic) used by the emitters.
 */
public final class LlvmWriter {

    private final StringBuilder out;
    private int tc;

    public LlvmWriter(StringBuilder out, int tmpCounter) {
        this.out = out;
        this.tc = tmpCounter;
    }

    /** Current temporary counter value (to hand back to the caller). */
    public int tmpCounter() {
        return tc;
    }

    /** Allocate the next SSA temporary name (e.g. {@code %_t7}). */
    public String temp() {
        return "%_t" + tc++;
    }

    /** Cast a memory-mapped IO address to an LLVM {@code ptr}. */
    public String ptr(int addr) {
        return "inttoptr (i16 " + addr + " to ptr)";
    }

    public String ptr(RegisterFile reg) {
        return ptr(reg.address());
    }

    /** {@code dst = load volatile i8, ptr ...} */
    public void loadVolatile(String dst, String ptrExpr) {
        line(dst + " = load volatile i8, ptr " + ptrExpr);
    }

    /** {@code dst = load volatile i8, ptr inttoptr(addr)} */
    public void loadVolatile(String dst, int addr) {
        loadVolatile(dst, ptr(addr));
    }

    public void loadVolatile(String dst, RegisterFile reg) {
        loadVolatile(dst, ptr(reg));
    }

    /** {@code store volatile i8 val, ptr ...} */
    public void storeVolatile(String val, String ptrExpr) {
        line("store volatile i8 " + val + ", ptr " + ptrExpr);
    }

    public void storeVolatile(String val, int addr) {
        storeVolatile(val, ptr(addr));
    }

    public void storeVolatile(String val, RegisterFile reg) {
        storeVolatile(val, ptr(reg));
    }

    /** {@code dst = or i8 src, mask} */
    public void or8(String dst, String src, int mask) {
        line(dst + " = or i8 " + src + ", " + mask);
    }

    /** {@code dst = and i8 src, (~mask) & 0xFF} */
    public void and8(String dst, String src, int mask) {
        line(dst + " = and i8 " + src + ", " + ((~mask) & 0xFF));
    }

    /** {@code dst = and i8 src, mask} (raw mask) */
    public void and8Raw(String dst, String src, int mask) {
        line(dst + " = and i8 " + src + ", " + mask);
    }

    /** {@code dst = trunc i64 src to i32} */
    public void trunc64to32(String dst, String src) {
        line(dst + " = trunc i64 " + src + " to i32");
    }

    /** {@code dst = sdiv i32 src, denom} */
    public void sdiv32(String dst, String src, int denom) {
        line(dst + " = sdiv i32 " + src + ", " + denom);
    }

    /** {@code dst = mul i32 src, factor} */
    public void mul32(String dst, String src, int factor) {
        line(dst + " = mul i32 " + src + ", " + factor);
    }

    /** {@code dst = icmp ne i8 src, 0} */
    public void icmpNe8(String dst, String src) {
        line(dst + " = icmp ne i8 " + src + ", 0");
    }

    /** {@code dst = zext i1 src to i32} */
    public void zext1to32(String dst, String src) {
        line(dst + " = zext i1 " + src + " to i32");
    }

    /** {@code dst = zext i8 src to i32} */
    public void zext8to32(String dst, String src) {
        line(dst + " = zext i8 " + src + " to i32");
    }

    /** {@code dst = zext i32 src to i64} */
    public void zext32to64(String dst, String src) {
        line(dst + " = zext i32 " + src + " to i64");
    }

    /** {@code call void @fn(i32 a, i32 b, ...)} */
    public void callVoid(String fn, Object... args) {
        String argList = Arrays.stream(args).map(arg -> "i32 " + arg).collect(Collectors.joining(", "));
        line("call void @" + fn + "(" + argList + ")");
    }

    /** {@code call void @fn(ptr a, ...)} */
    public void callVoidPtr(String fn, Object... args) {
        String argList = Arrays.stream(args).map(arg -> "ptr " + arg).collect(Collectors.joining(", "));
        line("call void @" + fn + "(" + argList + ")");
    }

    /** {@code dst = call i32 @fn(i32 a, i32 b, ...)} */
    public void callI32(String dst, String fn, Object... args) {
        String argList = Arrays.stream(args).map(arg -> "i32 " + arg).collect(Collectors.joining(", "));
        line(dst + " = call i32 @" + fn + "(" + argList + ")");
    }

    /** Append a raw two-space-indented instruction line. */
    public void line(String instruction) {
        out.append("  ").append(instruction).append("\n");
    }
}
