package com.github.pfichtner.espressomachine.emit;

import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isClassname;
import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isUsedIn;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.BasicBlock;
import org.teavm.model.Instruction;
import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits AVR intrinsic lowering for the custom {@code com.github.pfichtner.espressomachine.api.Random} API.
 *
 * {@code Random.random(bound)} / {@code random(min, max)} delegate to an
 * LCG-based runtime declared as external symbols in {@code random.ll}.
 */
public class RandomEmitter implements IntrinsicEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.api.Random";

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
            case "random" -> {
                if (args.size() == 1) {
                    writer.callI32(recv, "__espressomachine_random_long",
                            resolveVar.apply(args.get(0)));
                } else if (args.size() == 2) {
                    writer.callI32(recv, "__espressomachine_random_range",
                            resolveVar.apply(args.get(0)), resolveVar.apply(args.get(1)));
                }
            }
            default -> emitFallback(writer, insn, args, resolveVar);
        }
        return writer.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        if (!isUsedIn(programs, isClassname(CLASS))) return "";
        return """
                declare i32 @__espressomachine_random_long(i32 %bound)
                declare i32 @__espressomachine_random_range(i32 %min, i32 %max)
                """;
    }

}
