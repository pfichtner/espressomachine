package com.github.pfichtner.espressomachine.emit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

public interface IntrinsicEmitter {

    boolean canHandle(String className);

    int emit(LlvmWriter writer, InvokeInstruction insn, Map<Integer, String> constVars,
            Function<Variable, String> resolveVar, Map<Integer, String> objectRefs);

    String declarations(Map<String, Program> programs);

    default void emitFallback(LlvmWriter writer, InvokeInstruction insn, List<? extends Variable> args,
            Function<Variable, String> resolveVar) {
        String fqn = insn.getMethod().getClassName();
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        writer.callVoid("__espressomachine_" + simpleName.toLowerCase() + "_" + insn.getMethod().getName(),
                args.stream().map(resolveVar).toArray());
    }

    default Integer constInt(Variable variable, Map<Integer, String> constVars) {
        String s = constVars.get(variable.getIndex());
        if (s == null)
            return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}