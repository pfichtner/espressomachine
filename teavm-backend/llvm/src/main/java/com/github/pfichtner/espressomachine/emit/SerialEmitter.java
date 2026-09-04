package com.github.pfichtner.espressomachine.emit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits the ATmega328P intrinsic lowering for {@code Serial} API calls.
 *
 * {@code begin}, {@code write}, {@code available}, {@code read} and the
 * String overloads of {@code print}/{@code println} are lowered here; the
 * {@code char}/{@code int} overloads are typically inlined by TeaVM into
 * {@code write()} calls.
 */
public class SerialEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.api.Serial";

    private static final int F_CPU = 16_000_000; // ATmega328P @ 16 MHz

    private SerialEmitter() {}

    public static boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    /**
     * Emit a Serial intrinsic call. Handles class check, method dispatch and
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
            case "begin"     -> emitBegin(out, args, constVars, tmpCounter, resolveVar);
            case "write"     -> emitWrite(out, args, tmpCounter, resolveVar);
            case "available" -> emitAvailable(out, insn, tmpCounter, resolveVar);
            case "read"      -> emitRead(out, insn, tmpCounter, resolveVar);
            case "print"     -> emitPrint(out, args, insn, objectRefs, tmpCounter, resolveVar);
            case "println"   -> emitPrintln(out, args, insn, objectRefs, tmpCounter, resolveVar);
            default          -> emitFallback(out, insn, args, tmpCounter, resolveVar);
        };
    }

    public static String declarations() {
        return """
                declare void @__espressomachine_serial_begin(i32 %baud)
                declare void @__espressomachine_serial_write(i32 %b)
                declare void @__espressomachine_serial_print_int(i32 %n)
                declare void @__espressomachine_serial_print_str(ptr %s)
                """;
    }

    // ---- Internal helpers ----

    private static int emitBegin(StringBuilder out, List<? extends Variable> args,
                                 Map<Integer, String> constVars, int tc,
                                 Function<Variable, String> resolveVar) {
        Integer baud = constInt(args.get(0), constVars);
        if (baud != null && baud > 0) {
            int ubrr = F_CPU / (16 * baud) - 1;
            out.append("  store volatile i8 ").append((ubrr >> 8) & 0xFF)
               .append(", ptr inttoptr (i16 197 to ptr)\n");  // UBRR0H
            out.append("  store volatile i8 ").append(ubrr & 0xFF)
               .append(", ptr inttoptr (i16 196 to ptr)\n");  // UBRR0L
            out.append("  store volatile i8 24, ptr inttoptr (i16 193 to ptr)\n"); // UCSR0B = RXEN|TXEN
            out.append("  store volatile i8 6,  ptr inttoptr (i16 194 to ptr)\n"); // UCSR0C = 8N1
        } else {
            out.append("  call void @__espressomachine_serial_begin(i32 ")
               .append(resolveVar.apply(args.get(0))).append(")\n");
        }
        return tc;
    }

    private static int emitWrite(StringBuilder out, List<? extends Variable> args,
                                 int tc, Function<Variable, String> resolveVar) {
        out.append("  call void @__espressomachine_serial_write(i32 ")
           .append(resolveVar.apply(args.get(0))).append(")\n");
        return tc;
    }

    private static int emitAvailable(StringBuilder out, InvokeInstruction insn,
                                     int tc, Function<Variable, String> resolveVar) {
        // Read RXC0 (bit 7 = 0x80) from UCSR0A (0xC0 = 192); return 1 if set, 0 otherwise.
        out.append("  %_t").append(tc)
           .append(" = load volatile i8, ptr inttoptr (i16 192 to ptr)\n");
        out.append("  %_t").append(tc + 1)
           .append(" = and i8 %_t").append(tc).append(", -128\n");
        out.append("  %_t").append(tc + 2)
           .append(" = icmp ne i8 %_t").append(tc + 1).append(", 0\n");
        if (insn.getReceiver() != null) {
            out.append("  ").append(resolveVar.apply(insn.getReceiver()))
               .append(" = zext i1 %_t").append(tc + 2).append(" to i32\n");
        }
        return tc + 3;
    }

    private static int emitRead(StringBuilder out, InvokeInstruction insn,
                                int tc, Function<Variable, String> resolveVar) {
        // Read UDR0 (0xC6 = 198) and zero-extend to i32; call available() first.
        out.append("  %_t").append(tc)
           .append(" = load volatile i8, ptr inttoptr (i16 198 to ptr)\n");
        if (insn.getReceiver() != null) {
            out.append("  ").append(resolveVar.apply(insn.getReceiver()))
               .append(" = zext i8 %_t").append(tc).append(" to i32\n");
        }
        return tc + 1;
    }

    private static int emitPrintln(StringBuilder out, List<? extends Variable> args,
                                   InvokeInstruction insn,
                                   Map<Integer, String> objectRefs, int tc,
                                   Function<Variable, String> resolveVar) {
        if (args.isEmpty()) {
            // println() → CR + LF
            out.append("  call void @__espressomachine_serial_write(i32 13)\n");
            out.append("  call void @__espressomachine_serial_write(i32 10)\n");
            return tc;
        }
        tc = emitPrint(out, args, insn, objectRefs, tc, resolveVar);
        out.append("  call void @__espressomachine_serial_write(i32 13)\n");
        out.append("  call void @__espressomachine_serial_write(i32 10)\n");
        return tc;
    }

    private static int emitPrint(StringBuilder out, List<? extends Variable> args,
                                 InvokeInstruction insn,
                                 Map<Integer, String> objectRefs, int tc,
                                 Function<Variable, String> resolveVar) {
        if (args.isEmpty()) return tc;
        Variable arg = args.get(0);

        // Distinguish by the declared parameter type: String (object) vs char/int.
        ValueType ptype = insn.getMethod().getDescriptor().parameterType(0);
        boolean isString = (ptype instanceof ValueType.Object)
                && "java.lang.String".equals(((ValueType.Object) ptype).getClassName());

        if (isString) {
            // String: prefer a string-literal global, else the raw ptr.
            String global = objectRefs.get(arg.getIndex());
            String target = (global != null) ? global : resolveVar.apply(arg);
            out.append("  call void @__espressomachine_serial_print_str(ptr ")
               .append(target).append(")\n");
            return tc;
        }

        // Numeric (int or char) — transmit the decimal representation.
        out.append("  call void @__espressomachine_serial_print_int(i32 ")
           .append(resolveVar.apply(arg)).append(")\n");
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
}
