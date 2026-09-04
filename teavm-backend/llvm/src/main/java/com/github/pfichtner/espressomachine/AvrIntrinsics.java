package com.github.pfichtner.espressomachine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

import com.github.pfichtner.espressomachine.emit.DelayEmitter;
import com.github.pfichtner.espressomachine.emit.GpioEmitter;
import com.github.pfichtner.espressomachine.emit.IntrinsicEmitter;
import com.github.pfichtner.espressomachine.emit.JavaRandomEmitter;
import com.github.pfichtner.espressomachine.emit.LlvmWriter;
import com.github.pfichtner.espressomachine.emit.MathBridgeEmitter;
import com.github.pfichtner.espressomachine.emit.RandomBridgeEmitter;
import com.github.pfichtner.espressomachine.emit.RandomEmitter;
import com.github.pfichtner.espressomachine.emit.SerialEmitter;
import com.github.pfichtner.espressomachine.emit.TimeEmitter;

/**
 * Thin dispatcher for ATmega328P intrinsic lowering.
 *
 * When the backend encounters a call to GPIO.*, Delay.* or Serial.*, it
 * delegates to this class which routes to the dedicated per-API emitter in the
 * {@code emit} subpackage. Each emitter owns all knowledge about its API
 * (method dispatch, inline lowering, fallback to runtime declarations).
 */
public class AvrIntrinsics {

    private final TimeEmitter timeEmitter = new TimeEmitter();

	private final List<IntrinsicEmitter> emitters = List.of(
			new GpioEmitter(),
			new DelayEmitter(),
			new SerialEmitter(),
			new RandomEmitter(),
			new JavaRandomEmitter(),
			new RandomBridgeEmitter(),
			new MathBridgeEmitter(),
			timeEmitter);

    public boolean isIntrinsic(String className) {
        return emitters.stream().anyMatch(e -> e.canHandle(className));
    }

    public boolean isIntrinsic(InvokeInstruction insn) {
        return isIntrinsic(insn.getMethod().getClassName());
    }

    /**
     * Emit the intrinsic — delegates to the matching emitter.
     *
     * @return updated tmpCounter
     */
    public int emit(StringBuilder out, InvokeInstruction insn,
                    Map<Integer, String> constVars, int tmpCounter,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        LlvmWriter writer = new LlvmWriter(out, tmpCounter);
        String cls = insn.getMethod().getClassName();
        return emitters.stream()
                .filter(e -> e.canHandle(cls))
                .findFirst()
                .map(e -> e.emit(writer, insn, constVars, resolveVar, objectRefs))
                .orElse(tmpCounter);
    }

    /**
     * Returns all runtime declarations needed for the given program set.
     * Each emitter decides independently whether its declarations are required.
     */
    public String declarations(Map<String, Program> programs) {
        return emitters.stream()
                .map(e -> e.declarations(programs))
                .collect(Collectors.joining());
    }

    public boolean isMillisUsed(Map<String, Program> programs) {
        return timeEmitter.isMillisUsed(programs);
    }
}
