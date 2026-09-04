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
 * Emits AVR intrinsic lowering for direct {@code java.util.Random} calls.
 *
 * The class transformer in {@code IrDumper} normally replaces
 * {@code java.util.Random.nextInt()} / {@code nextInt(int)} / {@code nextLong()}
 * invokes with calls to {@link RuntimeRandomBridge}, which is handled by
 * {@link RandomBridgeEmitter}. This emitter is the fallback for any
 * {@code java.util.Random} calls that survive the transform, lowering them to
 * the same 48-bit LCG runtime intrinsics ({@code @__random_next_int} etc.).
 */
public class JavaRandomEmitter implements IntrinsicEmitter {

    public static final String CLASS = "java.util.Random";

    public boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    public int emit(LlvmWriter writer, InvokeInstruction insn,
                    Map<Integer, String> constVars,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();
        String recv = (insn.getReceiver() != null)
                ? resolveVar.apply(insn.getReceiver()) : null;

        switch (method) {
            case "nextInt" -> {
                if (args.isEmpty()) {
                    if (recv != null) writer.callI32(recv, "__random_next_int");
                } else {
                    if (recv != null) {
                        writer.callI32(recv, "__random_next_int_bound",
                                resolveVar.apply(args.get(0)));
                    }
                }
            }
            case "nextLong" -> {
                if (recv != null) {
                    String lo = writer.temp();
                    writer.callI32(lo, "__random_next_long_lo");
                    writer.zext32to64(recv, lo);
                }
            }
            default -> emitFallback(writer, insn, args, resolveVar);
        }
        return writer.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        if (!isUsedIn(programs, isClassname(CLASS))) return "";
        return RandomIntrinsics.DECLARATIONS;
    }

}