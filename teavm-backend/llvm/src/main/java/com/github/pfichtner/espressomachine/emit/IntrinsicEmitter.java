package com.github.pfichtner.espressomachine.emit;

import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

public interface IntrinsicEmitter {

    boolean canHandle(String className);

    int emit(LlvmWriter w, InvokeInstruction insn,
             Map<Integer, String> constVars,
             Function<Variable, String> resolveVar,
             Map<Integer, String> objectRefs);

    String declarations();
}
