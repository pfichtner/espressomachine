package com.github.pfichtner.espressomachine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

import com.github.pfichtner.espressomachine.emit.DelayEmitter;
import com.github.pfichtner.espressomachine.emit.GpioEmitter;
import com.github.pfichtner.espressomachine.emit.SerialEmitter;

/**
 * ATmega328P intrinsic lowering for GPIO, Delay and Serial API calls.
 *
 * When the backend encounters a call to GPIO.*, Delay.* or Serial.*, it delegates
 * to this class which dispatches to the dedicated per-API emitter in the
 * {@code emit} subpackage. Each emitter either:
 *   a) Inlines the AVR memory-mapped I/O directly (when pin is a compile-time constant),
 *   b) Falls back to an external runtime declaration (@__espressomachine_gpio_*).
 *
 * ATmega328P register map (memory-mapped addresses):
 *   DDRB  = 0x24  (Data Direction Register B — controls input/output)
 *   PORTB = 0x25  (Port B Output Register — controls output level)
 *   PINB  = 0x23  (Port B Input Register  — reads input level)
 *   DDRC  = 0x27 / PORTC = 0x28
 *   DDRD  = 0x2A / PORTD = 0x2B
 *
 * Arduino pin → AVR port/bit mapping (relevant subset):
 *   D13 = PB5, D12 = PB4, D11 = PB3, D10 = PB2, D9 = PB1, D8 = PB0
 *   D7  = PD7, D6  = PD6, D5  = PD5, D4  = PD4, D3  = PD3, D2  = PD2
 *   D1  = PD1, D0  = PD0
 *   A0  = PC0, A1  = PC1, A2  = PC2, A3  = PC3, A4  = PC4, A5  = PC5
 */
public class AvrIntrinsics {

    public static final String GPIO_CLASS   = "com.github.pfichtner.espressomachine.api.GPIO";
    public static final String DELAY_CLASS  = "com.github.pfichtner.espressomachine.api.Delay";
    public static final String SERIAL_CLASS = "com.github.pfichtner.espressomachine.api.Serial";
    public static final List<String> ALL_INTRINSICS = List.of(GPIO_CLASS, DELAY_CLASS, SERIAL_CLASS);

    // ---- ATmega328P pin table ----
    // Index = Arduino pin number; value = {DDR addr, PORT addr, bit mask}
    private static final int[][] PIN_MAP = {
        // pin  DDR    PORT   mask
        {  0, 0x2A, 0x2B, 1 },   // D0  = PD0
        {  1, 0x2A, 0x2B, 2 },   // D1  = PD1
        {  2, 0x2A, 0x2B, 4 },   // D2  = PD2
        {  3, 0x2A, 0x2B, 8 },   // D3  = PD3
        {  4, 0x2A, 0x2B, 16},   // D4  = PD4
        {  5, 0x2A, 0x2B, 32},   // D5  = PD5
        {  6, 0x2A, 0x2B, 64},   // D6  = PD6
        {  7, 0x2A, 0x2B,128},   // D7  = PD7
        {  8, 0x24, 0x25, 1 },   // D8  = PB0
        {  9, 0x24, 0x25, 2 },   // D9  = PB1
        { 10, 0x24, 0x25, 4 },   // D10 = PB2
        { 11, 0x24, 0x25, 8 },   // D11 = PB3
        { 12, 0x24, 0x25, 16},   // D12 = PB4
        { 13, 0x24, 0x25, 32},   // D13 = PB5  ← built-in LED
    };

    public static final int GPIO_OUTPUT = 1;
    public static final int GPIO_INPUT  = 0;
    public static final int GPIO_HIGH   = 1;
    public static final int GPIO_LOW    = 0;

    // ------------------------------------------------------------------
    // Check if a method call is an intrinsic
    // ------------------------------------------------------------------

    public static boolean isIntrinsic(InvokeInstruction insn) {
        return ALL_INTRINSICS.contains(insn.getMethod().getClassName());
    }

    // ------------------------------------------------------------------
    // Emit the intrinsic — returns true if handled, false for unknown
    // ------------------------------------------------------------------

    /**
     * @param out        output buffer
     * @param insn       the invoke instruction
     * @param constVars  map from variable index to its compile-time integer value (if known)
     * @param tmpCounter current temporary counter (incremented as needed)
     * @param resolveVar function to resolve a variable to its LLVM string
     * @param objectRefs map from variable index to the LLVM global symbol a static
     *                   object reference (e.g. an enum constant) resolved to
     * @return updated tmpCounter
     */
    public static int emit(StringBuilder out, InvokeInstruction insn,
                    Map<Integer, String> constVars, int tmpCounter,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        String cls = insn.getMethod().getClassName();
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();

        if (GPIO_CLASS.equals(cls)) {
            return switch (method) {
                case "pinMode"      -> GpioEmitter.emitPinMode(out, args, constVars, tmpCounter, resolveVar);
                case "digitalWrite" -> GpioEmitter.emitDigitalWrite(out, args, constVars, tmpCounter, resolveVar);
                default -> emitFallback(out, insn, args, tmpCounter, resolveVar);
            };
        }
        if (DELAY_CLASS.equals(cls)) {
            return switch (method) {
                case "ms"   -> DelayEmitter.emitMs(out, args, tmpCounter, resolveVar);
                case "time" -> DelayEmitter.emitTime(out, args, constVars, objectRefs, tmpCounter, resolveVar);
                default     -> emitFallback(out, insn, args, tmpCounter, resolveVar);
            };
        }
        if (SERIAL_CLASS.equals(cls)) {
            return switch (method) {
                case "begin"     -> SerialEmitter.emitBegin(out, args, constVars, tmpCounter, resolveVar);
                case "write"     -> SerialEmitter.emitWrite(out, args, tmpCounter, resolveVar);
                case "available" -> SerialEmitter.emitAvailable(out, insn, tmpCounter, resolveVar);
                case "read"      -> SerialEmitter.emitRead(out, insn, tmpCounter, resolveVar);
                case "print"     -> SerialEmitter.emitPrint(out, args, insn, objectRefs, tmpCounter, resolveVar);
                case "println"   -> SerialEmitter.emitPrintln(out, args, insn, objectRefs, tmpCounter, resolveVar);
                default          -> emitFallback(out, insn, args, tmpCounter, resolveVar);
            };
        }
        return tmpCounter;
    }

    // ------------------------------------------------------------------
    // Fallback: generic external call
    // ------------------------------------------------------------------

    private static int emitFallback(StringBuilder out, InvokeInstruction insn,
                                    List<? extends Variable> args, int tc,
                                    Function<Variable, String> resolveVar) {
        String fqn = insn.getMethod().getClassName();
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        out.append("  call void @__espressomachine_")
           .append(simpleName.toLowerCase()).append("_")
           .append(insn.getMethod().getName()).append("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) out.append(", ");
            out.append("i32 ").append(resolveVar.apply(args.get(i)));
        }
        out.append(")\n");
        return tc;
    }

    // ------------------------------------------------------------------
    // Utility (shared with the emit subpackage)
    // ------------------------------------------------------------------

    public static Integer constInt(Variable v, Map<Integer, String> constVars) {
        String s = constVars.get(v.getIndex());
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    public static int[] pinMap(int pin) {
        for (int[] row : PIN_MAP) {
            if (row[0] == pin) return new int[]{row[1], row[2], row[3]};
        }
        return null;
    }

    /**
     * Returns LLVM declarations needed for all runtime-dispatch intrinsics
     * (those that couldn't be inlined at compile time).
     */
    public static String runtimeDeclarations() {
        return GpioEmitter.declarations() + DelayEmitter.declarations();
    }

    public static String serialDeclarations() {
        return SerialEmitter.declarations();
    }
}
