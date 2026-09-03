package com.github.pfichtner.espressomachine;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * ATmega328P intrinsic lowering for GPIO and Delay API calls.
 *
 * When the backend encounters a call to GPIO.* or Delay.*, it delegates to this
 * class which either:
 *   a) Inlines the AVR memory-mapped I/O directly (when pin is a compile-time constant),
 *   b) Falls back to an external runtime declaration (@__espressomachine_gpio_*).
 *
 * ATmega328P register map (memory-mapped addresses):
 *   DDRB  = 0x24  (Data Direction Register B — controls input/output)
 *   PORTB = 0x25  (Port B Output Register — controls output level)
 *   PINB  = 0x23  (Port B Input Register  — reads input level)
 *   DDRC  = 0x27 / PORTC = 0x28
 *   DDRD  = 0x2A / PORTD = 0x2B
 *
 * Arduino pin → AVR port/bit mapping (relevant subset):
 *   D13 = PB5, D12 = PB4, D11 = PB3, D10 = PB2, D9 = PB1, D8 = PB0
 *   D7  = PD7, D6  = PD6, D5  = PD5, D4  = PD4, D3  = PD3, D2  = PD2
 *   D1  = PD1, D0  = PD0
 *   A0  = PC0, A1  = PC1, A2  = PC2, A3  = PC3, A4  = PC4, A5  = PC5
 */
class AvrIntrinsics {

    static final String GPIO_CLASS   = "com.github.pfichtner.espressomachine.api.GPIO";
    static final String DELAY_CLASS  = "com.github.pfichtner.espressomachine.api.Delay";
    static final String SERIAL_CLASS = "com.github.pfichtner.espressomachine.api.Serial";
    static final List<String> ALL_INTRINSICS = List.of(GPIO_CLASS, DELAY_CLASS, SERIAL_CLASS);

    // ---- ATmega328P pin table ----
    // Index = Arduino pin number; value = {DDR addr, PORT addr, bit mask}
    private static final int[][] PIN_MAP = {
        // pin  DDR    PORT   mask
        {  0, 0x2A, 0x2B, 1 },   // D0  = PD0
        {  1, 0x2A, 0x2B, 2 },   // D1  = PD1
        {  2, 0x2A, 0x2B, 4 },   // D2  = PD2
        {  3, 0x2A, 0x2B, 8 },   // D3  = PD3
        {  4, 0x2A, 0x2B, 16},   // D4  = PD4
        {  5, 0x2A, 0x2B, 32},   // D5  = PD5
        {  6, 0x2A, 0x2B, 64},   // D6  = PD6
        {  7, 0x2A, 0x2B,128},   // D7  = PD7
        {  8, 0x24, 0x25, 1 },   // D8  = PB0
        {  9, 0x24, 0x25, 2 },   // D9  = PB1
        { 10, 0x24, 0x25, 4 },   // D10 = PB2
        { 11, 0x24, 0x25, 8 },   // D11 = PB3
        { 12, 0x24, 0x25, 16},   // D12 = PB4
        { 13, 0x24, 0x25, 32},   // D13 = PB5  ← built-in LED
    };

    static final int GPIO_OUTPUT = 1;
    static final int GPIO_INPUT  = 0;
    static final int GPIO_HIGH   = 1;
    static final int GPIO_LOW    = 0;

    // ------------------------------------------------------------------
    // Check if a method call is an intrinsic
    // ------------------------------------------------------------------

    static boolean isIntrinsic(InvokeInstruction insn) {
        return ALL_INTRINSICS.contains(insn.getMethod().getClassName());
    }

    // ------------------------------------------------------------------
    // Emit the intrinsic — returns true if handled, false for unknown
    // ------------------------------------------------------------------

    /**
     * @param out        output buffer
     * @param insn       the invoke instruction
     * @param constVars  map from variable index to its compile-time integer value (if known)
     * @param tmpCounter current temporary counter (incremented as needed)
     * @param resolveVar function to resolve a variable to its LLVM string
     * @param objectRefs map from variable index to the LLVM global symbol a static
     *                   object reference (e.g. an enum constant) resolved to
     * @return updated tmpCounter
     */
    static int emit(StringBuilder out, InvokeInstruction insn,
                    Map<Integer, String> constVars, int tmpCounter,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        String cls = insn.getMethod().getClassName();
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();

        if (GPIO_CLASS.equals(cls)) {
            return switch (method) {
                case "pinMode"     -> emitPinMode(out, args, constVars, tmpCounter, resolveVar);
                case "digitalWrite" -> emitDigitalWrite(out, args, constVars, tmpCounter, resolveVar);
                default -> emitFallback(out, insn, args, tmpCounter, resolveVar);
            };
        }
        if (DELAY_CLASS.equals(cls)) {
            return switch (method) {
                case "ms"   -> emitDelayMs(out, args, tmpCounter, resolveVar);
                case "time" -> emitDelayTime(out, args, constVars, objectRefs, tmpCounter, resolveVar);
                default     -> emitFallback(out, insn, args, tmpCounter, resolveVar);
            };
        }
        if (SERIAL_CLASS.equals(cls)) {
            return switch (method) {
                case "begin"     -> emitSerialBegin(out, args, constVars, tmpCounter, resolveVar);
                case "write"     -> emitSerialWrite(out, args, tmpCounter, resolveVar);
                case "available" -> emitSerialAvailable(out, insn, tmpCounter, resolveVar);
                case "read"      -> emitSerialRead(out, insn, tmpCounter, resolveVar);
                case "print"     -> emitSerialPrint(out, args, insn, objectRefs, tmpCounter, resolveVar);
                case "println"   -> emitSerialPrintln(out, args, insn, objectRefs, tmpCounter, resolveVar);
                default          -> emitFallback(out, insn, args, tmpCounter, resolveVar);
            };
        }
        return tmpCounter;
    }

    // ------------------------------------------------------------------
    // GPIO.pinMode(pin, mode)
    // ------------------------------------------------------------------

    private static int emitPinMode(StringBuilder out, List<? extends Variable> args,
                                   Map<Integer, String> constVars, int tc,
                                   Function<Variable, String> resolveVar) {
        Integer pin  = constInt(args.get(0), constVars);
        Integer mode = constInt(args.get(1), constVars);

        if (pin != null && mode != null) {
            int[] pm = pinMap(pin);
            if (pm != null) {
                // Inline: DDRB |= mask  or  DDRB &= ~mask
                int ddrAddr = pm[0];
                int mask = pm[2];
                String addr = "inttoptr (i16 " + ddrAddr + " to ptr)";
                String tmp1 = "%_t" + tc++;
                String tmp2 = "%_t" + tc++;
                out.append("  ").append(tmp1)
                   .append(" = load volatile i8, ptr ").append(addr).append("\n");
                if (mode == GPIO_OUTPUT) {
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

    // ------------------------------------------------------------------
    // GPIO.digitalWrite(pin, value)
    // ------------------------------------------------------------------

    private static int emitDigitalWrite(StringBuilder out, List<? extends Variable> args,
                                        Map<Integer, String> constVars, int tc,
                                        Function<Variable, String> resolveVar) {
        Integer pin   = constInt(args.get(0), constVars);
        Integer value = constInt(args.get(1), constVars);

        if (pin != null && value != null) {
            int[] pm = pinMap(pin);
            if (pm != null) {
                int portAddr = pm[1];
                int mask = pm[2];
                String addr = "inttoptr (i16 " + portAddr + " to ptr)";
                String tmp1 = "%_t" + tc++;
                String tmp2 = "%_t" + tc++;
                out.append("  ").append(tmp1)
                   .append(" = load volatile i8, ptr ").append(addr).append("\n");
                if (value == GPIO_HIGH) {
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

    // ------------------------------------------------------------------
    // Delay.ms(ms)
    // ------------------------------------------------------------------

    private static int emitDelayMs(StringBuilder out, List<? extends Variable> args,
                                   int tc,
                                   Function<Variable, String> resolveVar) {
        out.append("  call void @__espressomachine_delay_ms(i32 ")
           .append(resolveVar.apply(args.get(0))).append(")\n");
        return tc;
    }

    // ------------------------------------------------------------------
    // Delay.time(amount, unit)
    // ------------------------------------------------------------------
    //
    // Statically computes the millisecond equivalent and lowers straight to
    // __espressomachine_delay_ms when the TimeUnit argument is a compile-time enum
    // constant (e.g. Delay.time(1, TimeUnit.SECONDS) → __espressomachine_delay_ms(1000)).
    // Non-constant units fall back to a runtime __espressomachine_delay_time call.

    private static int emitDelayTime(StringBuilder out, List<? extends Variable> args,
                                     Map<Integer, String> constVars,
                                     Map<Integer, String> objectRefs, int tc,
                                     Function<Variable, String> resolveVar) {
        String unitGlobal = (args.size() > 1) ? objectRefs.get(args.get(1).getIndex()) : null;
        Integer denominator = null;   // unit < 1 ms (nanos/micros)
        long multiplier = 1;          // unit >= 1 ms
        if (unitGlobal != null) {
            String unit = unitGlobal.substring(unitGlobal.lastIndexOf('_') + 1);
            switch (unit) {
                case "NANOSECONDS"  -> denominator = 1_000_000;
                case "MICROSECONDS" -> denominator = 1_000;
                case "MILLISECONDS" -> { }
                case "SECONDS"      -> multiplier = 1_000;
                case "MINUTES"      -> multiplier = 60_000;
                case "HOURS"        -> multiplier = 3_600_000;
                case "DAYS"         -> multiplier = 86_400_000;
                default -> unitGlobal = null;
            }
        }

        if (unitGlobal == null) {
            // Unknown (non-constant) unit — generic runtime fallback.
            out.append("  call void @__espressomachine_delay_time(i32 ")
               .append(resolveVar.apply(args.get(0))).append(", i32 ")
               .append(resolveVar.apply(args.get(1))).append(")\n");
            return tc;
        }

        Integer amount = constInt(args.get(0), constVars);
        if (amount != null) {
            // Constant amount + constant unit → statically calculated millis.
            long millis = (denominator != null) ? amount / denominator : amount * multiplier;
            out.append("  call void @__espressomachine_delay_ms(i32 ").append(millis).append(")\n");
            return tc;
        }

        // Runtime amount (i64) with a known unit — scale inline to milliseconds.
        String tmp = "%_t" + tc++;
        out.append("  ").append(tmp)
           .append(" = trunc i64 ").append(resolveVar.apply(args.get(0))).append(" to i32\n");
        if (denominator != null) {
            String tmp2 = "%_t" + tc++;
            out.append("  ").append(tmp2)
               .append(" = sdiv i32 ").append(tmp).append(", ").append(denominator).append("\n");
            tmp = tmp2;
        } else if (multiplier != 1) {
            String tmp2 = "%_t" + tc++;
            out.append("  ").append(tmp2)
               .append(" = mul i32 ").append(tmp).append(", ").append(multiplier).append("\n");
            tmp = tmp2;
        }
        out.append("  call void @__espressomachine_delay_ms(i32 ").append(tmp).append(")\n");
        return tc;
    }

    // ------------------------------------------------------------------
    // Serial intrinsics
    // ------------------------------------------------------------------

    private static final int F_CPU = 16_000_000; // ATmega328P @ 16 MHz

    private static int emitSerialBegin(StringBuilder out, List<? extends Variable> args,
                                       Map<Integer, String> constVars, int tc,
                                       Function<Variable, String> resolveVar) {
        Integer baud = constInt(args.get(0), constVars);
        if (baud != null && baud > 0) {
            int ubrr = F_CPU / (16 * baud) - 1;
            out.append("  store volatile i8 ").append((ubrr >> 8) & 0xFF)
               .append(", ptr inttoptr (i16 197 to ptr)\n");  // UBRR0H
            out.append("  store volatile i8 ").append(ubrr & 0xFF)
               .append(", ptr inttoptr (i16 196 to ptr)\n");  // UBRR0L
            out.append("  store volatile i8 24, ptr inttoptr (i16 193 to ptr)\n"); // UCSR0B = RXEN|TXEN
            out.append("  store volatile i8 6,  ptr inttoptr (i16 194 to ptr)\n"); // UCSR0C = 8N1
        } else {
            out.append("  call void @__espressomachine_serial_begin(i32 ")
               .append(resolveVar.apply(args.get(0))).append(")\n");
        }
        return tc;
    }

    private static int emitSerialWrite(StringBuilder out, List<? extends Variable> args,
                                       int tc, Function<Variable, String> resolveVar) {
        out.append("  call void @__espressomachine_serial_write(i32 ")
           .append(resolveVar.apply(args.get(0))).append(")\n");
        return tc;
    }

    private static int emitSerialAvailable(StringBuilder out, InvokeInstruction insn,
                                           int tc, Function<Variable, String> resolveVar) {
        // Read RXC0 (bit 7 = 0x80) from UCSR0A (0xC0 = 192); return 1 if set, 0 otherwise.
        out.append("  %_t").append(tc)
           .append(" = load volatile i8, ptr inttoptr (i16 192 to ptr)\n");
        out.append("  %_t").append(tc + 1)
           .append(" = and i8 %_t").append(tc).append(", -128\n");
        out.append("  %_t").append(tc + 2)
           .append(" = icmp ne i8 %_t").append(tc + 1).append(", 0\n");
        if (insn.getReceiver() != null) {
            out.append("  ").append(resolveVar.apply(insn.getReceiver()))
               .append(" = zext i1 %_t").append(tc + 2).append(" to i32\n");
        }
        return tc + 3;
    }

    private static int emitSerialRead(StringBuilder out, InvokeInstruction insn,
                                      int tc, Function<Variable, String> resolveVar) {
        // Read UDR0 (0xC6 = 198) and zero-extend to i32; call available() first.
        out.append("  %_t").append(tc)
           .append(" = load volatile i8, ptr inttoptr (i16 198 to ptr)\n");
        if (insn.getReceiver() != null) {
            out.append("  ").append(resolveVar.apply(insn.getReceiver()))
               .append(" = zext i8 %_t").append(tc).append(" to i32\n");
        }
        return tc + 1;
    }

    // ------------------------------------------------------------------
    // Serial.print / Serial.println (String / char / int overloads)
    // ------------------------------------------------------------------
    //
    // TeaVM inlines print(char) and println(char)/println() into write() calls,
    // so the backend only needs to handle the String and int overloads that survive
    // optimisation, plus whatever residual calls remain.

    private static int emitSerialPrintln(StringBuilder out, List<? extends Variable> args,
                                         InvokeInstruction insn,
                                         Map<Integer, String> objectRefs, int tc,
                                         Function<Variable, String> resolveVar) {
        if (args.isEmpty()) {
            // println() → CR + LF
            out.append("  call void @__espressomachine_serial_write(i32 13)\n");
            out.append("  call void @__espressomachine_serial_write(i32 10)\n");
            return tc;
        }
        tc = emitSerialPrint(out, args, insn, objectRefs, tc, resolveVar);
        out.append("  call void @__espressomachine_serial_write(i32 13)\n");
        out.append("  call void @__espressomachine_serial_write(i32 10)\n");
        return tc;
    }

    private static int emitSerialPrint(StringBuilder out, List<? extends Variable> args,
                                       InvokeInstruction insn,
                                       Map<Integer, String> objectRefs, int tc,
                                       Function<Variable, String> resolveVar) {
        if (args.isEmpty()) return tc;
        Variable arg = args.get(0);

        // Distinguish by the declared parameter type: String (object) vs char/int.
        ValueType ptype = insn.getMethod().getDescriptor().parameterType(0);
        boolean isString = (ptype instanceof ValueType.Object)
                && "java.lang.String".equals(((ValueType.Object) ptype).getClassName());

        if (isString) {
            // String: prefer a string-literal global, else the raw ptr.
            String global = objectRefs.get(arg.getIndex());
            String target = (global != null) ? global : resolveVar.apply(arg);
            out.append("  call void @__espressomachine_serial_print_str(ptr ")
               .append(target).append(")\n");
            return tc;
        }

        // Numeric (int or char) — transmit the decimal representation.
        out.append("  call void @__espressomachine_serial_print_int(i32 ")
           .append(resolveVar.apply(arg)).append(")\n");
        return tc;
    }

    // ------------------------------------------------------------------
    // Fallback: generic external call
    // ------------------------------------------------------------------

    private static int emitFallback(StringBuilder out, InvokeInstruction insn,
                                    List<? extends Variable> args, int tc,
                                    Function<Variable, String> resolveVar) {
        String fqn = insn.getMethod().getClassName();
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        out.append("  call void @__espressomachine_")
           .append(simpleName.toLowerCase()).append("_")
           .append(insn.getMethod().getName()).append("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) out.append(", ");
            out.append("i32 ").append(resolveVar.apply(args.get(i)));
        }
        out.append(")\n");
        return tc;
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    private static Integer constInt(Variable v, Map<Integer, String> constVars) {
        String s = constVars.get(v.getIndex());
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static int[] pinMap(int pin) {
        for (int[] row : PIN_MAP) {
            if (row[0] == pin) return new int[]{row[1], row[2], row[3]};
        }
        return null;
    }

    /**
     * Returns LLVM declarations needed for all runtime-dispatch intrinsics
     * (those that couldn't be inlined at compile time).
     */
    static String runtimeDeclarations() {
        return """
                declare void @__espressomachine_gpio_pinmode(i32 %pin, i32 %mode)
                declare void @__espressomachine_gpio_digitalwrite(i32 %pin, i32 %value)
                declare void @__espressomachine_delay_ms(i32 %ms)
                """;
    }

    static String serialDeclarations() {
        return """
                declare void @__espressomachine_serial_begin(i32 %baud)
                declare void @__espressomachine_serial_write(i32 %b)
                declare void @__espressomachine_serial_print_int(i32 %n)
                declare void @__espressomachine_serial_print_str(ptr %s)
                """;
    }
}
