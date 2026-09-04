package com.github.pfichtner.espressomachine.emit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits the ATmega328P intrinsic lowering for {@code GPIO} API calls.
 *
 * When the pin and mode/value are compile-time constants the AVR memory-mapped
 * register is manipulated directly; otherwise the call falls back to an external
 * runtime declaration ({@code @__espressomachine_gpio_*}).
 */
public class GpioEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.api.GPIO";

    public static final int GPIO_OUTPUT = 1;
    public static final int GPIO_INPUT  = 0;
    public static final int GPIO_HIGH   = 1;
    public static final int GPIO_LOW    = 0;

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

    private GpioEmitter() {}

    public static boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    /**
     * Emit a GPIO intrinsic call. Handles class check, method dispatch and
     * fallback for unknown methods.
     *
     * @return updated tmpCounter
     */
    public static int emit(StringBuilder out, InvokeInstruction insn,
                           Map<Integer, String> constVars, int tmpCounter,
                           Function<Variable, String> resolveVar,
                           Map<Integer, String> objectRefs) {
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();
        return switch (method) {
            case "pinMode"      -> emitPinMode(out, args, constVars, tmpCounter, resolveVar);
            case "digitalWrite" -> emitDigitalWrite(out, args, constVars, tmpCounter, resolveVar);
            default -> emitFallback(out, insn, args, tmpCounter, resolveVar);
        };
    }

    public static String declarations() {
        return """
                declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
                declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
                """;
    }

    // ---- Internal helpers ----

    private static int emitPinMode(StringBuilder out, List<? extends Variable> args,
                                   Map<Integer, String> constVars, int tc,
                                   Function<Variable, String> resolveVar) {
        Integer pin  = constInt(args.get(0), constVars);
        Integer mode = constInt(args.get(1), constVars);

        if (pin != null && mode != null) {
            int[] pm = pinMap(pin);
            if (pm != null) {
                // Inline: DDRB |= mask  or  DDRB &= ~mask
                int ddrAddr = pm[0];
                int mask = pm[2];
                String addr = "inttoptr (i16 " + ddrAddr + " to ptr)";
                String tmp1 = "%_t" + tc++;
                String tmp2 = "%_t" + tc++;
                out.append("  ").append(tmp1)
                   .append(" = load volatile i8, ptr ").append(addr).append("\n");
                if (mode == GPIO_OUTPUT) {
                    out.append("  ").append(tmp2)
                       .append(" = or i8 ").append(tmp1).append(", ").append(mask).append("\n");
                } else {
                    out.append("  ").append(tmp2)
                       .append(" = and i8 ").append(tmp1).append(", ")
                       .append((~mask) & 0xFF).append("\n");
                }
                out.append("  store volatile i8 ").append(tmp2)
                   .append(", ptr ").append(addr).append("\n");
                return tc;
            }
        }
        // Fallback: runtime call
        out.append("  call void @__espressomachine_gpio_pinmode(i32 ")
           .append(resolveVar.apply(args.get(0))).append(", i32 ")
           .append(resolveVar.apply(args.get(1))).append(")\n");
        return tc;
    }

    private static int emitDigitalWrite(StringBuilder out, List<? extends Variable> args,
                                        Map<Integer, String> constVars, int tc,
                                        Function<Variable, String> resolveVar) {
        Integer pin   = constInt(args.get(0), constVars);
        Integer value = constInt(args.get(1), constVars);

        if (pin != null && value != null) {
            int[] pm = pinMap(pin);
            if (pm != null) {
                int portAddr = pm[1];
                int mask = pm[2];
                String addr = "inttoptr (i16 " + portAddr + " to ptr)";
                String tmp1 = "%_t" + tc++;
                String tmp2 = "%_t" + tc++;
                out.append("  ").append(tmp1)
                   .append(" = load volatile i8, ptr ").append(addr).append("\n");
                if (value == GPIO_HIGH) {
                    out.append("  ").append(tmp2)
                       .append(" = or i8 ").append(tmp1).append(", ").append(mask).append("\n");
                } else {
                    out.append("  ").append(tmp2)
                       .append(" = and i8 ").append(tmp1).append(", ")
                       .append((~mask) & 0xFF).append("\n");
                }
                out.append("  store volatile i8 ").append(tmp2)
                   .append(", ptr ").append(addr).append("\n");
                return tc;
            }
        }
        out.append("  call void @__espressomachine_gpio_digitalwrite(i32 ")
           .append(resolveVar.apply(args.get(0))).append(", i32 ")
           .append(resolveVar.apply(args.get(1))).append(")\n");
        return tc;
    }

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

    static Integer constInt(Variable v, Map<Integer, String> constVars) {
        String s = constVars.get(v.getIndex());
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    static int[] pinMap(int pin) {
        for (int[] row : PIN_MAP) {
            if (row[0] == pin) return new int[]{row[1], row[2], row[3]};
        }
        return null;
    }
}
