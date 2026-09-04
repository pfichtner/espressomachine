package com.github.pfichtner.espressomachine.emit;

import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isClassname;
import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isMethodname;
import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isUsedIn;
import static com.github.pfichtner.espressomachine.emit.RegisterFile.DDRB;
import static com.github.pfichtner.espressomachine.emit.RegisterFile.DDRD;
import static com.github.pfichtner.espressomachine.emit.RegisterFile.PINB;
import static com.github.pfichtner.espressomachine.emit.RegisterFile.PIND;
import static com.github.pfichtner.espressomachine.emit.RegisterFile.PORTB;
import static com.github.pfichtner.espressomachine.emit.RegisterFile.PORTD;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    public static final int GPIO_OUTPUT       = 1;
    public static final int GPIO_INPUT        = 0;
    public static final int GPIO_INPUT_PULLUP = 2;
    public static final int GPIO_HIGH         = 1;
    public static final int GPIO_LOW          = 0;

    /**
     * An Arduino pin and the AVR registers/bits that back it.
     * {@code pin} is the PINx register (for reading); {@code ddr}/{@code port} are for direction/output.
     */
    private record PinSpec(int pin, RegisterFile ddr, RegisterFile port, RegisterFile pinReg, int mask) {}

    // ---- ATmega328P pin table ----
    private static final List<PinSpec> PIN_MAP = List.of(
        new PinSpec( 0, DDRD, PORTD, PIND, 1 << 0),   // D0  = PD0
        new PinSpec( 1, DDRD, PORTD, PIND, 1 << 1),   // D1  = PD1
        new PinSpec( 2, DDRD, PORTD, PIND, 1 << 2),   // D2  = PD2
        new PinSpec( 3, DDRD, PORTD, PIND, 1 << 3),   // D3  = PD3
        new PinSpec( 4, DDRD, PORTD, PIND, 1 << 4),   // D4  = PD4
        new PinSpec( 5, DDRD, PORTD, PIND, 1 << 5),   // D5  = PD5
        new PinSpec( 6, DDRD, PORTD, PIND, 1 << 6),   // D6  = PD6
        new PinSpec( 7, DDRD, PORTD, PIND, 1 << 7),   // D7  = PD7
        new PinSpec( 8, DDRB, PORTB, PINB, 1 << 0),   // D8  = PB0
        new PinSpec( 9, DDRB, PORTB, PINB, 1 << 1),   // D9  = PB1
        new PinSpec(10, DDRB, PORTB, PINB, 1 << 2),   // D10 = PB2
        new PinSpec(11, DDRB, PORTB, PINB, 1 << 3),   // D11 = PB3
        new PinSpec(12, DDRB, PORTB, PINB, 1 << 4),   // D12 = PB4
        new PinSpec(13, DDRB, PORTB, PINB, 1 << 5)    // D13 = PB5  ← built-in LED
    );

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
            case "digitalRead"  -> emitDigitalRead(w, insn, args, constVars, resolveVar);
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
        if (isDigitalReadUsedIn(programs)) {
            base += "declare i32  @__espressomachine_gpio_digitalread(i32 %pin)\n";
        }
        if (!isAnalogUsedIn(programs)) return base;
        return base + """
                declare i32  @__espressomachine_gpio_analogread(i32 %pin)
                declare void @__espressomachine_gpio_analogWrite(i32 %pin, i32 %value)
                """;
    }

    private boolean isDigitalReadUsedIn(Map<String, Program> programs) {
        return isUsedIn(programs, isClassname(CLASS).and(isMethodname("digitalRead")));
    }

    private boolean isAnalogUsedIn(Map<String, Program> programs) {
        return isUsedIn(programs, isClassname(CLASS)
                .and(isMethodname("analogRead").or(isMethodname("analogWrite"))));
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
                // DDR bit: clear for INPUT / INPUT_PULLUP, set for OUTPUT
                String ddrPtr = writer.ptr(pm.ddr());
                String tmp1 = writer.temp();
                String tmp2 = writer.temp();
                writer.loadVolatile(tmp1, ddrPtr);
                if (mode == GPIO_OUTPUT) {
                    writer.or8(tmp2, tmp1, pm.mask());
                } else {
                    writer.and8(tmp2, tmp1, pm.mask());
                }
                writer.storeVolatile(tmp2, ddrPtr);

                // INPUT_PULLUP: also set PORT bit to enable internal pull-up resistor
                if (mode == GPIO_INPUT_PULLUP) {
                    String portPtr = writer.ptr(pm.port());
                    String tmp3 = writer.temp();
                    String tmp4 = writer.temp();
                    writer.loadVolatile(tmp3, portPtr);
                    writer.or8(tmp4, tmp3, pm.mask());
                    writer.storeVolatile(tmp4, portPtr);
                }
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

    private void emitDigitalRead(LlvmWriter writer, InvokeInstruction insn,
                                 List<? extends Variable> args,
                                 Map<Integer, String> constVars,
                                 Function<Variable, String> resolveVar) {
        Integer pin = constInt(args.get(0), constVars);
        String recv = resolveVar.apply(insn.getReceiver());

        if (pin != null) {
            PinSpec pm = pinMap(pin);
            if (pm != null) {
                // Inline: load PINx, AND mask, compare ne 0, zext i1 → i32
                String tmp1 = writer.temp();
                String tmp2 = writer.temp();
                String tmp3 = writer.temp();
                writer.loadVolatile(tmp1, pm.pinReg());
                writer.and8Raw(tmp2, tmp1, pm.mask());
                writer.icmpNe8(tmp3, tmp2);
                writer.zext1to32(recv, tmp3);
                return;
            }
        }
        writer.callI32(recv, "__espressomachine_gpio_digitalread",
                args.stream().map(resolveVar).toArray());
    }

    private void emitAnalogRead(LlvmWriter writer, InvokeInstruction insn,
                               List<? extends Variable> args,
                               Function<Variable, String> resolveVar) {
        String recv = resolveVar.apply(insn.getReceiver());
        writer.callI32(recv, "__espressomachine_gpio_analogread",
                args.stream().map(resolveVar).toArray());
    }

    private PinSpec pinMap(int pin) {
        return PIN_MAP.stream().filter(pm -> pm.pin() == pin).findFirst().orElse(null);
    }
}
