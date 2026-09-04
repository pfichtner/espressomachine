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
     * Emit a Delay intrinsic call into {@code w}.
     *
     * @return updated tmpCounter
     */
    public static int emit(LlvmWriter w, InvokeInstruction insn,
                           Map<Integer, String> constVars,
                           Function<Variable, String> resolveVar,
                           Map<Integer, String> objectRefs) {
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();
        switch (method) {
            case "ms"   -> emitMs(w, args, resolveVar);
            case "time" -> emitTime(w, args, constVars, objectRefs, resolveVar);
            default     -> emitFallback(w, insn, args, resolveVar);
        }
        return w.tmpCounter();
    }

    public static String declarations() {
        return """
                declare void @__espressomachine_delay_ms(i32 %ms)
                """;
    }

    // ---- Internal helpers ----

    private static void emitMs(LlvmWriter w, List<? extends Variable> args,
                               Function<Variable, String> resolveVar) {
        w.callVoid("__espressomachine_delay_ms", resolveVar.apply(args.get(0)));
    }

    private static void emitTime(LlvmWriter w, List<? extends Variable> args,
                                 Map<Integer, String> constVars,
                                 Map<Integer, String> objectRefs,
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
            w.callVoid("__espressomachine_delay_time",
                    resolveVar.apply(args.get(0)), resolveVar.apply(args.get(1)));
            return;
        }

        Integer amount = constInt(args.get(0), constVars);
        if (amount != null) {
            // Constant amount + constant unit → statically calculated millis.
            long millis = (denominator != null) ? amount / denominator : amount * multiplier;
            w.callVoid("__espressomachine_delay_ms", millis);
            return;
        }

        // Runtime amount (i64) with a known unit — scale inline to milliseconds.
        String tmp = w.temp();
        w.trunc64to32(tmp, resolveVar.apply(args.get(0)));
        if (denominator != null) {
            String tmp2 = w.temp();
            w.sdiv32(tmp2, tmp, denominator);
            tmp = tmp2;
        } else if (multiplier != 1) {
            String tmp2 = w.temp();
            w.mul32(tmp2, tmp, (int) multiplier);
            tmp = tmp2;
        }
        w.callVoid("__espressomachine_delay_ms", tmp);
    }

    private static void emitFallback(LlvmWriter w, InvokeInstruction insn,
                                     List<? extends Variable> args,
                                     Function<Variable, String> resolveVar) {
        String fqn = insn.getMethod().getClassName();
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        w.callVoid("__espressomachine_" + simpleName.toLowerCase() + "_" + insn.getMethod().getName(),
                args.stream().map(resolveVar).toArray());
    }

    static Integer constInt(Variable v, Map<Integer, String> constVars) {
        String s = constVars.get(v.getIndex());
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }
}
