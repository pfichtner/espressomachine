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

    /**
     * Whether the invoke targets a varargs method whose trailing array argument
     * should be spliced into individual arguments. Emitters that opt in receive
     * {@link #emitVarargs} instead of {@link #emit}; the caller is then
     * responsible for removing the now-unused array construction.
     */
    default boolean isVarargs(InvokeInstruction insn) {
        return false;
    }

    /**
     * Emit a varargs intrinsic. {@code args} holds the method's fixed arguments
     * (the leading non-array parameters) followed by the elements of the trailing
     * array argument, in order. The default is never reached for emitters that
     * report {@link #isVarargs}.
     */
    default int emitVarargs(LlvmWriter writer, InvokeInstruction insn,
            List<? extends Variable> fixedArgs, List<Variable> varargs,
            Map<Integer, String> constVars, Function<Variable, String> resolveVar,
            Map<Integer, String> objectRefs, Map<Integer, String> stringLiterals,
            Function<String, String> internString) {
        return emit(writer, insn, constVars, resolveVar, objectRefs, stringLiterals, internString);
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