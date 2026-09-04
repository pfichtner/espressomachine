package com.github.pfichtner.espressomachine.emit;

import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isClassname;
import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isMethodname;
import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isUsedIn;

import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits the ATmega328P intrinsic lowering for {@code Time} API calls.
 *
 * {@code Time.millis()} lowers to a call to the runtime function
 * {@code @__espressomachine_time_millis()} which reads the volatile
 * 32-bit overflow counter maintained by the Timer0 ISR in {@code time.ll}.
 */
public class TimeEmitter implements IntrinsicEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.api.Time";

    public boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    public int emit(LlvmWriter w, InvokeInstruction insn,
                    Map<Integer, String> constVars,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        if ("millis".equals(insn.getMethod().getName())) {
            String recv = resolveVar.apply(insn.getReceiver());
            w.callI32(recv, "__espressomachine_time_millis");
        }
        return w.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        if (!isUsedIn(programs, isClassname(CLASS).and(isMethodname("millis")))) {
            return "";
        }
        return """
                declare i32  @__espressomachine_time_millis()
                declare void @__espressomachine_time_init()
                """;
    }

    public boolean isMillisUsed(Map<String, Program> programs) {
        return isUsedIn(programs, isClassname(CLASS).and(isMethodname("millis")));
    }
}
