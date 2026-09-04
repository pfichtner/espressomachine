package com.github.pfichtner.espressomachine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

import com.github.pfichtner.espressomachine.emit.DelayEmitter;
import com.github.pfichtner.espressomachine.emit.GpioEmitter;
import com.github.pfichtner.espressomachine.emit.LlvmWriter;
import com.github.pfichtner.espressomachine.emit.SerialEmitter;

/**
 * Thin dispatcher for ATmega328P intrinsic lowering.
 *
 * When the backend encounters a call to GPIO.*, Delay.* or Serial.*, it
 * delegates to this class which routes to the dedicated per-API emitter in the
 * {@code emit} subpackage. Each emitter owns all knowledge about its API
 * (method dispatch, inline lowering, fallback to runtime declarations).
 */
public class AvrIntrinsics {

    public static final String GPIO_CLASS   = GpioEmitter.CLASS;
    public static final String DELAY_CLASS  = DelayEmitter.CLASS;
    public static final String SERIAL_CLASS = SerialEmitter.CLASS;
    public static final List<String> ALL_INTRINSICS = List.of(GPIO_CLASS, DELAY_CLASS, SERIAL_CLASS);

    public static boolean isIntrinsic(InvokeInstruction insn) {
        return ALL_INTRINSICS.contains(insn.getMethod().getClassName());
    }

    /**
     * Emit the intrinsic — delegates to the matching emitter.
     *
     * @return updated tmpCounter
     */
    public static int emit(StringBuilder out, InvokeInstruction insn,
                    Map<Integer, String> constVars, int tmpCounter,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        LlvmWriter w = new LlvmWriter(out, tmpCounter);
        String cls = insn.getMethod().getClassName();
        if (GpioEmitter.canHandle(cls)) {
            return GpioEmitter.emit(w, insn, constVars, resolveVar, objectRefs);
        }
        if (DelayEmitter.canHandle(cls)) {
            return DelayEmitter.emit(w, insn, constVars, resolveVar, objectRefs);
        }
        if (SerialEmitter.canHandle(cls)) {
            return SerialEmitter.emit(w, insn, constVars, resolveVar, objectRefs);
        }
        return tmpCounter;
    }

    public static String runtimeDeclarations() {
        return GpioEmitter.declarations() + DelayEmitter.declarations();
    }

    public static String serialDeclarations() {
        return SerialEmitter.declarations();
    }
}
