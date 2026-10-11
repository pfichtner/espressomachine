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

    /**
     * Extended emit hook. Emitters that need the raw text of string-literal
     * arguments (e.g. {@code Serial.printf} format parsing) or the ability to
     * intern new literal globals override this. The default forwards to the
     * 5-argument form so most emitters are unaffected.
     *
     * @param stringLiterals variable index → string literal text (for
     *                       {@code StringConstantInstruction} results)
     * @param internString   interns a literal and returns its LLVM global symbol
     */
    default int emit(LlvmWriter writer, InvokeInstruction insn, Map<Integer, String> constVars,
            Function<Variable, String> resolveVar, Map<Integer, String> objectRefs,
            Map<Integer, String> stringLiterals, Function<String, String> internString) {
        return emit(writer, insn, constVars, resolveVar, objectRefs);
    }

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