package com.github.pfichtner.espressomachine.emit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.teavm.model.Program;
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
public class DelayEmitter implements IntrinsicEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.api.Delay";

    public DelayEmitter() {}

    public boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    /**
     * Emit a Delay intrinsic call into {@code w}.
     *
     * @return updated tmpCounter
     */
    public int emit(LlvmWriter writer, InvokeInstruction insn,
                    Map<Integer, String> constVars,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();
        switch (method) {
            case "ms"   -> emitMs(writer, args, resolveVar);
            case "time" -> emitTime(writer, args, constVars, objectRefs, resolveVar);
            default     -> emitFallback(writer, insn, args, resolveVar);
        }
        return writer.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        return """
                declare void @__espressomachine_delay_ms(i32 %ms)
                """;
    }

    // ---- Internal helpers ----

    private void emitMs(LlvmWriter writer, List<? extends Variable> args,
                        Function<Variable, String> resolveVar) {
        writer.callVoid("__espressomachine_delay_ms", resolveVar.apply(args.get(0)));
    }

    private void emitTime(LlvmWriter w, List<? extends Variable> args,
                          Map<Integer, String> constVars,
                          Map<Integer, String> objectRefs,
                          Function<Variable, String> resolveVar) {
        TimeUnit tu = null;
        if (args.size() > 1) {
            String unitGlobal = objectRefs.get(args.get(1).getIndex());
            if (unitGlobal != null) {
                String unit = unitGlobal.substring(unitGlobal.lastIndexOf('_') + 1);
                try {
                    tu = TimeUnit.valueOf(unit);
                } catch (IllegalArgumentException e) {
                    // not a known TimeUnit constant — fall through to runtime fallback
                }
            }
        }

        if (tu == null) {
            // Unknown (non-constant) unit — generic runtime fallback.
            w.callVoid("__espressomachine_delay_time",
                    resolveVar.apply(args.get(0)), resolveVar.apply(args.get(1)));
            return;
        }

        Integer amount = constInt(args.get(0), constVars);
        if (amount != null) {
            // Constant amount + constant unit → statically calculated millis.
            w.callVoid("__espressomachine_delay_ms", TimeUnit.MILLISECONDS.convert(amount, tu));
            return;
        }

        // Runtime amount (i64) with a known unit — scale inline to milliseconds.
        long nanosPerUnit = tu.toNanos(1);
        long nanosPerMs   = TimeUnit.MILLISECONDS.toNanos(1);
        String tmp = w.temp();
        w.trunc64to32(tmp, resolveVar.apply(args.get(0)));
        if (nanosPerUnit < nanosPerMs) {
            String tmp2 = w.temp();
            w.sdiv32(tmp2, tmp, (int) (nanosPerMs / nanosPerUnit));
            tmp = tmp2;
        } else if (nanosPerUnit > nanosPerMs) {
            String tmp2 = w.temp();
            w.mul32(tmp2, tmp, (int) (nanosPerUnit / nanosPerMs));
            tmp = tmp2;
        }
        w.callVoid("__espressomachine_delay_ms", tmp);
    }

    private void emitFallback(LlvmWriter writer, InvokeInstruction insn,
                              List<? extends Variable> args,
                              Function<Variable, String> resolveVar) {
        String fqn = insn.getMethod().getClassName();
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        writer.callVoid("__espressomachine_" + simpleName.toLowerCase() + "_" + insn.getMethod().getName(),
                args.stream().map(resolveVar).toArray());
    }

    Integer constInt(Variable variable, Map<Integer, String> constVars) {
        String s = constVars.get(variable.getIndex());
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }
}
