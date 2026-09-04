package com.github.pfichtner.espressomachine.emit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits the ATmega328P intrinsic lowering for {@code Delay} API calls.
 *
 * While {@code Delay.ms} lowers straight to the runtime delay loop,
 * {@code Delay.time} statically computes the millisecond equivalent when the
 * {@code TimeUnit} argument is a compile-time enum constant and folds it into
 * {@code __espressomachine_delay_ms}; non-constant units fall back to a runtime
 * {@code __espressomachine_delay_time} call.
 */
public class DelayEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.api.Delay";

    private DelayEmitter() {}

    public static boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    /**
     * Emit a Delay intrinsic call. Handles class check, method dispatch and
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
            case "ms"   -> emitMs(out, args, tmpCounter, resolveVar);
            case "time" -> emitTime(out, args, constVars, objectRefs, tmpCounter, resolveVar);
            default     -> emitFallback(out, insn, args, tmpCounter, resolveVar);
        };
    }

    public static String declarations() {
        return """
                declare void @__espressomachine_delay_ms(i32 %ms)
                """;
    }

    // ---- Internal helpers ----

    private static int emitMs(StringBuilder out, List<? extends Variable> args, int tc,
                              Function<Variable, String> resolveVar) {
        out.append("  call void @__espressomachine_delay_ms(i32 ")
           .append(resolveVar.apply(args.get(0))).append(")\n");
        return tc;
    }

    private static int emitTime(StringBuilder out, List<? extends Variable> args,
                                Map<Integer, String> constVars,
                                Map<Integer, String> objectRefs, int tc,
                                Function<Variable, String> resolveVar) {
        String unitGlobal = (args.size() > 1) ? objectRefs.get(args.get(1).getIndex()) : null;
        Integer denominator = null;   // unit < 1 ms (nanos/micros)
        long multiplier = 1;          // unit >= 1 ms
        if (unitGlobal != null) {
            String unit = unitGlobal.substring(unitGlobal.lastIndexOf('_') + 1);
            switch (unit) {
                case "NANOSECONDS"  -> denominator = 1_000_000;
                case "MICROSECONDS" -> denominator = 1_000;
                case "MILLISECONDS" -> { }
                case "SECONDS"      -> multiplier = 1_000;
                case "MINUTES"      -> multiplier = 60_000;
                case "HOURS"        -> multiplier = 3_600_000;
                case "DAYS"         -> multiplier = 86_400_000;
                default -> unitGlobal = null;
            }
        }

        if (unitGlobal == null) {
            // Unknown (non-constant) unit — generic runtime fallback.
            out.append("  call void @__espressomachine_delay_time(i32 ")
               .append(resolveVar.apply(args.get(0))).append(", i32 ")
               .append(resolveVar.apply(args.get(1))).append(")\n");
            return tc;
        }

        Integer amount = constInt(args.get(0), constVars);
        if (amount != null) {
            // Constant amount + constant unit → statically calculated millis.
            long millis = (denominator != null) ? amount / denominator : amount * multiplier;
            out.append("  call void @__espressomachine_delay_ms(i32 ").append(millis).append(")\n");
            return tc;
        }

        // Runtime amount (i64) with a known unit — scale inline to milliseconds.
        String tmp = "%_t" + tc++;
        out.append("  ").append(tmp)
           .append(" = trunc i64 ").append(resolveVar.apply(args.get(0))).append(" to i32\n");
        if (denominator != null) {
            String tmp2 = "%_t" + tc++;
            out.append("  ").append(tmp2)
               .append(" = sdiv i32 ").append(tmp).append(", ").append(denominator).append("\n");
            tmp = tmp2;
        } else if (multiplier != 1) {
            String tmp2 = "%_t" + tc++;
            out.append("  ").append(tmp2)
               .append(" = mul i32 ").append(tmp).append(", ").append(multiplier).append("\n");
            tmp = tmp2;
        }
        out.append("  call void @__espressomachine_delay_ms(i32 ").append(tmp).append(")\n");
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
