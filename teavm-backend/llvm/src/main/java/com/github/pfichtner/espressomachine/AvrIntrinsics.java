package com.github.pfichtner.espressomachine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.BasicBlock;
import org.teavm.model.Instruction;
import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

import com.github.pfichtner.espressomachine.emit.DelayEmitter;
import com.github.pfichtner.espressomachine.emit.GpioEmitter;
import com.github.pfichtner.espressomachine.emit.IntrinsicEmitter;
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

    private final IntrinsicEmitter gpio   = new GpioEmitter();
    private final IntrinsicEmitter delay  = new DelayEmitter();
    private final IntrinsicEmitter serial = new SerialEmitter();
    private final List<IntrinsicEmitter> emitters = List.of(gpio, delay, serial);

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
        LlvmWriter w = new LlvmWriter(out, tmpCounter);
        String cls = insn.getMethod().getClassName();
        return emitters.stream()
                .filter(e -> e.canHandle(cls))
                .findFirst()
                .map(e -> e.emit(w, insn, constVars, resolveVar, objectRefs))
                .orElse(tmpCounter);
    }

    /**
     * Returns all runtime declarations needed for the given program set.
     * GPIO and Delay declarations are always included; Serial is only added when
     * the programs contain at least one Serial call.
     */
    public String declarations(Map<String, Program> programs) {
        String base = gpio.declarations() + delay.declarations();
        return usesSerial(programs) ? base + serial.declarations() : base;
    }

    private boolean usesSerial(Map<String, Program> programs) {
        for (Program prog : programs.values()) {
            if (prog == null) continue;
            for (int bi = 0; bi < prog.basicBlockCount(); bi++) {
                BasicBlock bb = prog.basicBlockAt(bi);
                if (bb == null) continue;
                for (Instruction insn : bb) {
                    if (insn instanceof InvokeInstruction inv
                            && serial.canHandle(inv.getMethod().getClassName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
