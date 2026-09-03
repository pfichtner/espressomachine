package com.github.pfichtner.espressomachine.emit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Variable;

import com.github.pfichtner.espressomachine.AvrIntrinsics;

/**
 * Emits the ATmega328P intrinsic lowering for {@code GPIO} API calls.
 *
 * When the pin and mode/value are compile-time constants the AVR memory-mapped
 * register is manipulated directly; otherwise the call falls back to an external
 * runtime declaration (@__espressomachine_gpio_*).
 */
public class GpioEmitter {

    private GpioEmitter() {}

    /**
     * Emit {@code GPIO.pinMode(pin, mode)}.
     *
     * @return updated tmpCounter
     */
    public static int emitPinMode(StringBuilder out, List<? extends Variable> args,
                                  Map<Integer, String> constVars, int tc,
                                  Function<Variable, String> resolveVar) {
        Integer pin  = AvrIntrinsics.constInt(args.get(0), constVars);
        Integer mode = AvrIntrinsics.constInt(args.get(1), constVars);

        if (pin != null && mode != null) {
            int[] pm = AvrIntrinsics.pinMap(pin);
            if (pm != null) {
                // Inline: DDRB |= mask  or  DDRB &= ~mask
                int ddrAddr = pm[0];
                int mask = pm[2];
                String addr = "inttoptr (i16 " + ddrAddr + " to ptr)";
                String tmp1 = "%_t" + tc++;
                String tmp2 = "%_t" + tc++;
                out.append("  ").append(tmp1)
                   .append(" = load volatile i8, ptr ").append(addr).append("\n");
                if (mode == AvrIntrinsics.GPIO_OUTPUT) {
                    out.append("  ").append(tmp2)
                       .append(" = or i8 ").append(tmp1).append(", ").append(mask).append("\n");
                } else {
                    out.append("  ").append(tmp2)
                       .append(" = and i8 ").append(tmp1).append(", ")
                       .append((~mask) & 0xFF).append("\n");
                }
                out.append("  store volatile i8 ").append(tmp2)
                   .append(", ptr ").append(addr).append("\n");
                return tc;
            }
        }
        // Fallback: runtime call
        out.append("  call void @__espressomachine_gpio_pinmode(i32 ")
           .append(resolveVar.apply(args.get(0))).append(", i32 ")
           .append(resolveVar.apply(args.get(1))).append(")\n");
        return tc;
    }

    /**
     * Emit {@code GPIO.digitalWrite(pin, value)}.
     *
     * @return updated tmpCounter
     */
    public static int emitDigitalWrite(StringBuilder out, List<? extends Variable> args,
                                       Map<Integer, String> constVars, int tc,
                                       Function<Variable, String> resolveVar) {
        Integer pin   = AvrIntrinsics.constInt(args.get(0), constVars);
        Integer value = AvrIntrinsics.constInt(args.get(1), constVars);

        if (pin != null && value != null) {
            int[] pm = AvrIntrinsics.pinMap(pin);
            if (pm != null) {
                int portAddr = pm[1];
                int mask = pm[2];
                String addr = "inttoptr (i16 " + portAddr + " to ptr)";
                String tmp1 = "%_t" + tc++;
                String tmp2 = "%_t" + tc++;
                out.append("  ").append(tmp1)
                   .append(" = load volatile i8, ptr ").append(addr).append("\n");
                if (value == AvrIntrinsics.GPIO_HIGH) {
                    out.append("  ").append(tmp2)
                       .append(" = or i8 ").append(tmp1).append(", ").append(mask).append("\n");
                } else {
                    out.append("  ").append(tmp2)
                       .append(" = and i8 ").append(tmp1).append(", ")
                       .append((~mask) & 0xFF).append("\n");
                }
                out.append("  store volatile i8 ").append(tmp2)
                   .append(", ptr ").append(addr).append("\n");
                return tc;
            }
        }
        out.append("  call void @__espressomachine_gpio_digitalwrite(i32 ")
           .append(resolveVar.apply(args.get(0))).append(", i32 ")
           .append(resolveVar.apply(args.get(1))).append(")\n");
        return tc;
    }

    /**
     * Returns LLVM declarations needed for the GPIO runtime-dispatch intrinsics
     * (those that couldn't be inlined at compile time).
     */
    public static String declarations() {
        return """
                declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
                declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
                """;
    }
}
