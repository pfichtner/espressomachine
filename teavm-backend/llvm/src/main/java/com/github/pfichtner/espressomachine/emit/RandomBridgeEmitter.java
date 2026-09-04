package com.github.pfichtner.espressomachine.emit;

import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isClassname;
import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isUsedIn;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits AVR intrinsic lowering for {@link RuntimeRandomBridge} calls.
 *
 * The class transformer in {@code IrDumper} replaces
 * {@code java.util.Random.nextInt()} / {@code nextInt(int)} / {@code nextLong()}
 * invokes with calls to {@link RuntimeRandomBridge}. This emitter intercepts
 * those bridge calls and emits the 48-bit LCG runtime intrinsics
 * ({@code @__random_next_int} etc.).
 */
public class RandomBridgeEmitter implements IntrinsicEmitter {

    /** Bridge class used by the class transformer to replace java.util.Random invokes. */
    public static final String CLASS = "com.github.pfichtner.espressomachine.emit.RuntimeRandomBridge";

    public boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    public int emit(LlvmWriter writer, InvokeInstruction insn,
                    Map<Integer, String> constVars,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();

        if (insn.getReceiver() == null) return writer.tmpCounter();
        String recv = resolveVar.apply(insn.getReceiver());

        switch (method) {
            case "randomNext" -> {
                // randomNext(int bits) → @__random_next(bits)
                if (!args.isEmpty()) {
                    writer.callI32(recv, "__random_next", resolveVar.apply(args.get(0)));
                }
            }
            case "randomNextInt" -> {
                // randomNextInt() → @__random_next_int()
                writer.callI32(recv, "__random_next_int");
            }
            case "randomNextIntBound" -> {
                // randomNextIntBound(int bound) → @__random_next_int_bound(bound)
                if (!args.isEmpty()) {
                    writer.callI32(recv, "__random_next_int_bound",
                            resolveVar.apply(args.get(0)));
                }
            }
            case "randomNextLong" -> {
                // randomNextLong() → @__random_next_long_lo(), zero-extended to i64
                String lo = writer.temp();
                writer.callI32(lo, "__random_next_long_lo");
                writer.zext32to64(recv, lo);
            }
        }
        return writer.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        if (!isUsedIn(programs, isClassname(CLASS))) return "";
        return RandomIntrinsics.DECLARATIONS;
    }

}