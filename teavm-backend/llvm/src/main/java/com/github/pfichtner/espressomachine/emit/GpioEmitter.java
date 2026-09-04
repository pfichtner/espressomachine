package com.github.pfichtner.espressomachine.emit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.BasicBlock;
import org.teavm.model.Instruction;
import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits the ATmega328P intrinsic lowering for {@code GPIO} API calls.
 *
 * When the pin and mode/value are compile-time constants the AVR memory-mapped
 * register is manipulated directly; otherwise the call falls back to an external
 * runtime declaration ({@code @__espressomachine_gpio_*}).
 */
public class GpioEmitter implements IntrinsicEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.api.GPIO";

    public static final int GPIO_OUTPUT = 1;
    public static final int GPIO_INPUT  = 0;
    public static final int GPIO_HIGH   = 1;
    public static final int GPIO_LOW    = 0;

    /**
     * An Arduino pin and the AVR registers/bits that back it.
     */
    private record PinSpec(int pin, RegisterFile ddr, RegisterFile port, int mask) {}

    // ---- ATmega328P pin table ----
    private static final List<PinSpec> PIN_MAP = List.of(
        new PinSpec( 0, RegisterFile.DDRD, RegisterFile.PORTD, 1   ),   // D0  = PD0
        new PinSpec( 1, RegisterFile.DDRD, RegisterFile.PORTD, 2   ),   // D1  = PD1
        new PinSpec( 2, RegisterFile.DDRD, RegisterFile.PORTD, 4   ),   // D2  = PD2
        new PinSpec( 3, RegisterFile.DDRD, RegisterFile.PORTD, 8   ),   // D3  = PD3
        new PinSpec( 4, RegisterFile.DDRD, RegisterFile.PORTD, 16  ),   // D4  = PD4
        new PinSpec( 5, RegisterFile.DDRD, RegisterFile.PORTD, 32  ),   // D5  = PD5
        new PinSpec( 6, RegisterFile.DDRD, RegisterFile.PORTD, 64  ),   // D6  = PD6
        new PinSpec( 7, RegisterFile.DDRD, RegisterFile.PORTD, 128 ),   // D7  = PD7
        new PinSpec( 8, RegisterFile.DDRB, RegisterFile.PORTB, 1   ),   // D8  = PB0
        new PinSpec( 9, RegisterFile.DDRB, RegisterFile.PORTB, 2   ),   // D9  = PB1
        new PinSpec(10, RegisterFile.DDRB, RegisterFile.PORTB, 4   ),   // D10 = PB2
        new PinSpec(11, RegisterFile.DDRB, RegisterFile.PORTB, 8   ),   // D11 = PB3
        new PinSpec(12, RegisterFile.DDRB, RegisterFile.PORTB, 16  ),   // D12 = PB4
        new PinSpec(13, RegisterFile.DDRB, RegisterFile.PORTB, 32  )    // D13 = PB5  ← built-in LED
    );

    public GpioEmitter() {}

    public boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    /**
     * Emit a GPIO intrinsic call into {@code w}.
     *
     * @return updated tmpCounter
     */
    public int emit(LlvmWriter w, InvokeInstruction insn,
                    Map<Integer, String> constVars,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();
        switch (method) {
            case "pinMode"      -> emitPinMode(w, args, constVars, resolveVar);
            case "digitalWrite" -> emitDigitalWrite(w, args, constVars, resolveVar);
            case "analogRead"   -> emitAnalogRead(w, insn, args, resolveVar);
            default -> emitFallback(w, insn, args, resolveVar);
        }
        return w.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        String base = """
                declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
                declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
                """;
        if (!isAnalogUsedIn(programs)) return base;
        return base + """
                declare i32  @__espressomachine_gpio_analogread(i32 %pin)
                declare void @__espressomachine_gpio_analogWrite(i32 %pin, i32 %value)
                """;
    }

    private boolean isAnalogUsedIn(Map<String, Program> programs) {
        for (Program prog : programs.values()) {
            if (prog == null) continue;
            for (int bi = 0; bi < prog.basicBlockCount(); bi++) {
                BasicBlock bb = prog.basicBlockAt(bi);
                if (bb == null) continue;
                for (Instruction insn : bb) {
                    if (insn instanceof InvokeInstruction inv && canHandle(inv.getMethod().getClassName())) {
                        String name = inv.getMethod().getName();
                        if ("analogRead".equals(name) || "analogWrite".equals(name)) return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- Internal helpers ----

    private void emitPinMode(LlvmWriter writer, List<? extends Variable> args,
                             Map<Integer, String> constVars,
                             Function<Variable, String> resolveVar) {
        Integer pin  = constInt(args.get(0), constVars);
        Integer mode = constInt(args.get(1), constVars);

        if (pin != null && mode != null) {
            PinSpec pm = pinMap(pin);
            if (pm != null) {
                // Inline: DDRx |= mask  or  DDRx &= ~mask
                String ptr = writer.ptr(pm.ddr());
                String tmp1 = writer.temp();
                String tmp2 = writer.temp();
                writer.loadVolatile(tmp1, ptr);
                if (mode == GPIO_OUTPUT) {
                    writer.or8(tmp2, tmp1, pm.mask());
                } else {
                    writer.and8(tmp2, tmp1, pm.mask());
                }
                writer.storeVolatile(tmp2, ptr);
                return;
            }
        }
        // Fallback: runtime call
        writer.callVoid("__espressomachine_gpio_pinmode",
                resolveVar.apply(args.get(0)), resolveVar.apply(args.get(1)));
    }

    private void emitDigitalWrite(LlvmWriter writer, List<? extends Variable> args,
                                  Map<Integer, String> constVars,
                                  Function<Variable, String> resolveVar) {
        Integer pin   = constInt(args.get(0), constVars);
        Integer value = constInt(args.get(1), constVars);

        if (pin != null && value != null) {
            PinSpec pm = pinMap(pin);
            if (pm != null) {
                String ptr = writer.ptr(pm.port());
                String tmp1 = writer.temp();
                String tmp2 = writer.temp();
                writer.loadVolatile(tmp1, ptr);
                if (value == GPIO_HIGH) {
                    writer.or8(tmp2, tmp1, pm.mask());
                } else {
                    writer.and8(tmp2, tmp1, pm.mask());
                }
                writer.storeVolatile(tmp2, ptr);
                return;
            }
        }
        writer.callVoid("__espressomachine_gpio_digitalwrite",
                resolveVar.apply(args.get(0)), resolveVar.apply(args.get(1)));
    }

    private void emitAnalogRead(LlvmWriter writer, InvokeInstruction insn,
                               List<? extends Variable> args,
                               Function<Variable, String> resolveVar) {
        String recv = resolveVar.apply(insn.getReceiver());
        writer.callI32(recv, "__espressomachine_gpio_analogread",
                args.stream().map(resolveVar).toArray());
    }

    private void emitFallback(LlvmWriter writer, InvokeInstruction insn,
                              List<? extends Variable> args,
                              Function<Variable, String> resolveVar) {
        String fqn = insn.getMethod().getClassName();
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        writer.callVoid("__espressomachine_" + simpleName.toLowerCase() + "_" + insn.getMethod().getName(),
                args.stream().map(resolveVar).toArray());
    }

    Integer constInt(Variable v, Map<Integer, String> constVars) {
        String s = constVars.get(v.getIndex());
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    PinSpec pinMap(int pin) {
        return PIN_MAP.stream().filter(pm -> pm.pin() == pin).findFirst().orElse(null);
    }
}
