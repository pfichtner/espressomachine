package com.github.pfichtner.espressomachine.emit;

import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isUsedIn;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits the ATmega328P intrinsic lowering for {@code Serial} API calls.
 *
 * {@code begin}, {@code write}, {@code available}, {@code read} and the
 * String overloads of {@code print}/{@code println} are lowered here; the
 * {@code char}/{@code int} overloads are typically inlined by TeaVM into
 * {@code write()} calls.
 */
public class SerialEmitter implements IntrinsicEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.api.Serial";

    private static final int F_CPU = 16_000_000; // ATmega328P @ 16 MHz

    private static final int RXEN_TXEN = 24;   // UCSR0B = RXEN | TXEN
    private static final int FRAME_8N1  = 6;   // UCSR0C = 8 data bits, no parity, 1 stop
    private static final int CR = 13;          // carriage return
    private static final int LF = 10;          // line feed

    public boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    /**
     * Emit a Serial intrinsic call into {@code w}.
     *
     * @return updated tmpCounter
     */
    public int emit(LlvmWriter writer, InvokeInstruction insn,
                    Map<Integer, String> constVars,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        return emit(writer, insn, constVars, resolveVar, objectRefs, Map.of(), s -> s);
    }

    @Override
    public int emit(LlvmWriter writer, InvokeInstruction insn,
                    Map<Integer, String> constVars,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs,
                    Map<Integer, String> stringLiterals,
                    Function<String, String> internString) {
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();
        switch (method) {
            case "begin"     -> emitBegin(writer, args, constVars, resolveVar);
            case "write"     -> emitWrite(writer, args, resolveVar);
            case "available" -> emitAvailable(writer, insn, resolveVar);
            case "read"      -> emitRead(writer, insn, resolveVar);
            case "print"     -> emitPrint(writer, args, insn, objectRefs, resolveVar);
            case "println"   -> emitPrintln(writer, args, insn, objectRefs, resolveVar);
            case "printf"    -> emitPrintf(writer, insn, objectRefs, stringLiterals,
                                           internString, resolveVar);
            default          -> emitFallback(writer, insn, args, resolveVar);
        }
        return writer.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        if (!isUsedIn(programs, InvokeInstructions.isClassname(CLASS))) return "";
        StringBuilder out = new StringBuilder("""
                declare void @__espressomachine_serial_begin(i32 %baud)
                declare void @__espressomachine_serial_write(i32 %b)
                declare void @__espressomachine_serial_print_int(i32 %n)
                declare void @__espressomachine_serial_print_str(ptr addrspace(1) %s)
                """);
        if (isPrintfUsed(programs)) {
            out.append("""
                    declare void @__espressomachine_serial_print_uint(i32 %n)
                    declare void @__espressomachine_serial_print_radix(i32 %n, i32 %radix, i32 %upper)
                    """);
        }
        return out.toString();
    }

    private static boolean isPrintfUsed(Map<String, Program> programs) {
        for (Program prog : programs.values()) {
            if (prog == null) continue;
            for (int bi = 0; bi < prog.basicBlockCount(); bi++) {
                var bb = prog.basicBlockAt(bi);
                if (bb == null) continue;
                for (var insn : bb) {
                    if (insn instanceof InvokeInstruction inv
                            && CLASS.equals(inv.getMethod().getClassName())
                            && "printf".equals(inv.getMethod().getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- Internal helpers ----

    private void emitBegin(LlvmWriter writer, List<? extends Variable> args,
                                  Map<Integer, String> constVars,
                                  Function<Variable, String> resolveVar) {
        Integer baud = constInt(args.get(0), constVars);
        if (baud != null && baud > 0) {
            int ubrr = F_CPU / (16 * baud) - 1;
            writer.storeVolatile(String.valueOf((ubrr >> 8) & 0xFF), RegisterFile.UBRR0H);
            writer.storeVolatile(String.valueOf(ubrr & 0xFF), RegisterFile.UBRR0L);
            writer.storeVolatile(String.valueOf(RXEN_TXEN), RegisterFile.UCSR0B);
            writer.storeVolatile(String.valueOf(FRAME_8N1), RegisterFile.UCSR0C);
        } else {
            writer.callVoid("__espressomachine_serial_begin", resolveVar.apply(args.get(0)));
        }
    }

    private void emitWrite(LlvmWriter writer, List<? extends Variable> args,
                                  Function<Variable, String> resolveVar) {
        writer.callVoid("__espressomachine_serial_write", resolveVar.apply(args.get(0)));
    }

    private void emitAvailable(LlvmWriter writer, InvokeInstruction insn,
                                      Function<Variable, String> resolveVar) {
        // Read RXC0 (bit 7 = 0x80) from UCSR0A; return 1 if set, 0 otherwise.
        String t0 = writer.temp();
        String t1 = writer.temp();
        String t2 = writer.temp();
        writer.loadVolatile(t0, RegisterFile.UCSR0A);
        writer.and8Raw(t1, t0, -128);   // mask RXC0 (bit 7, = 0x80 as signed i8)
        writer.icmpNe8(t2, t1);
        if (insn.getReceiver() != null) {
            writer.zext1to32(resolveVar.apply(insn.getReceiver()), t2);
        }
    }

    private void emitRead(LlvmWriter writer, InvokeInstruction insn,
                                 Function<Variable, String> resolveVar) {
        // Read UDR0 and zero-extend to i32; call available() first.
        String t0 = writer.temp();
        writer.loadVolatile(t0, RegisterFile.UDR0);
        if (insn.getReceiver() != null) {
            writer.zext8to32(resolveVar.apply(insn.getReceiver()), t0);
        }
    }

    private void emitPrintln(LlvmWriter writer, List<? extends Variable> args,
                                    InvokeInstruction insn,
                                    Map<Integer, String> objectRefs,
                                    Function<Variable, String> resolveVar) {
        if (!args.isEmpty()) {
        	emitPrint(writer, args, insn, objectRefs, resolveVar);
        }
        writer.callVoid("__espressomachine_serial_write", CR);
        writer.callVoid("__espressomachine_serial_write", LF);
    }

    private void emitPrint(LlvmWriter writer, List<? extends Variable> args,
                                  InvokeInstruction insn,
                                  Map<Integer, String> objectRefs,
                                  Function<Variable, String> resolveVar) {
        if (args.isEmpty()) return;
        Variable arg = args.get(0);

        // Distinguish by the declared parameter type: String (object) vs char/int.
        ValueType ptype = insn.getMethod().getDescriptor().parameterType(0);
        boolean isString = (ptype instanceof ValueType.Object o)
                && "java.lang.String".equals(o.getClassName());

        if (isString) {
            // String: prefer a string-literal global, else the raw ptr.
            String global = objectRefs.get(arg.getIndex());
            String target = (global != null) ? global : resolveVar.apply(arg);
            writer.callVoidPtrAS1("__espressomachine_serial_print_str", target);
        } else {
        	// Numeric (int or char) — transmit the decimal representation.
        	writer.callVoid("__espressomachine_serial_print_int", resolveVar.apply(arg));
		}

    }

    // ---- printf: compile-time format expansion ----

    /**
     * Lower a {@code Serial.printf} call by expanding its (string-literal) format
     * into a flat sequence of print/write calls. No runtime formatter or String
     * heap is involved; each specifier consumes the next argument, whose declared
     * type is validated against the specifier.
     */
    private void emitPrintf(LlvmWriter writer, InvokeInstruction insn,
                            Map<Integer, String> objectRefs,
                            Map<Integer, String> stringLiterals,
                            Function<String, String> internString,
                            Function<Variable, String> resolveVar) {
        List<? extends Variable> args = insn.getArguments();
        if (args.isEmpty()) return;

        String fmt = stringLiterals.get(args.get(0).getIndex());
        if (fmt == null) {
            writer.line("; ERROR: Serial.printf requires a compile-time string-literal format");
            String target = objectRefs.getOrDefault(args.get(0).getIndex(),
                    resolveVar.apply(args.get(0)));
            writer.callVoidPtrAS1("__espressomachine_serial_print_str", target);
            return;
        }

        int nextArg = 1; // args.get(0) is the format string
        int i = 0;
        while (i < fmt.length()) {
            int pct = fmt.indexOf('%', i);
            if (pct < 0) {
                emitChunk(writer, fmt.substring(i), internString);
                break;
            }
            if (pct > i) {
                emitChunk(writer, fmt.substring(i, pct), internString);
            }
            if (pct + 1 >= fmt.length()) {
                writer.line("; ERROR: Serial.printf: dangling '%' in format");
                writer.callVoid("__espressomachine_serial_write", "37");
                break;
            }
            char spec = fmt.charAt(pct + 1);
            i = pct + 2;
            switch (spec) {
                case '%' -> writer.callVoid("__espressomachine_serial_write", "37");
                case 'd' -> {
                    if (requireArg(writer, spec, nextArg, insn, false)) {
                        writer.callVoid("__espressomachine_serial_print_int",
                                resolveVar.apply(args.get(nextArg)));
                        nextArg++;
                    }
                }
                case 'u' -> {
                    if (requireArg(writer, spec, nextArg, insn, false)) {
                        writer.callVoid("__espressomachine_serial_print_uint",
                                resolveVar.apply(args.get(nextArg)));
                        nextArg++;
                    }
                }
                case 'x' -> {
                    if (emitRadix(writer, spec, 16, 0, nextArg, args, insn, resolveVar)) nextArg++;
                }
                case 'X' -> {
                    if (emitRadix(writer, spec, 16, 1, nextArg, args, insn, resolveVar)) nextArg++;
                }
                case 'b' -> {
                    if (emitRadix(writer, spec, 2, 0, nextArg, args, insn, resolveVar)) nextArg++;
                }
                case 'c' -> {
                    if (requireArg(writer, spec, nextArg, insn, false)) {
                        writer.callVoid("__espressomachine_serial_write",
                                resolveVar.apply(args.get(nextArg)));
                        nextArg++;
                    }
                }
                case 's' -> {
                    if (requireArg(writer, spec, nextArg, insn, true)) {
                        Variable arg = args.get(nextArg);
                        String global = objectRefs.get(arg.getIndex());
                        String target = (global != null) ? global : resolveVar.apply(arg);
                        writer.callVoidPtrAS1("__espressomachine_serial_print_str", target);
                        nextArg++;
                    }
                }
                default -> {
                    writer.line("; ERROR: Serial.printf: unsupported format specifier '%"
                            + spec + "'");
                    writer.callVoid("__espressomachine_serial_write", "37");
                    writer.callVoid("__espressomachine_serial_write", String.valueOf((int) spec));
                }
            }
        }
        if (nextArg < args.size()) {
            writer.line("; ERROR: Serial.printf: " + (args.size() - nextArg)
                    + " argument(s) not consumed by the format");
        }
    }

    /** Transmit a non-empty literal chunk of the format string. */
    private void emitChunk(LlvmWriter writer, String chunk,
                           Function<String, String> internString) {
        if (chunk.isEmpty()) return;
        writer.callVoidPtrAS1("__espressomachine_serial_print_str", internString.apply(chunk));
    }

    /** Emit a hex/binary specifier, returning whether an argument was consumed. */
    private boolean emitRadix(LlvmWriter writer, char spec, int radix, int upper,
                              int argIdx, List<? extends Variable> args,
                              InvokeInstruction insn,
                              Function<Variable, String> resolveVar) {
        if (!requireArg(writer, spec, argIdx, insn, false)) return false;
        writer.callVoid("__espressomachine_serial_print_radix",
                resolveVar.apply(args.get(argIdx)),
                String.valueOf(radix), String.valueOf(upper));
        return true;
    }

    /**
     * Validate that the argument at {@code argIdx} exists and its declared type
     * matches the specifier ({@code String} for {@code %s}, otherwise numeric).
     */
    private boolean requireArg(LlvmWriter writer, char spec, int argIdx,
                               InvokeInstruction insn, boolean wantString) {
        if (argIdx >= insn.getArguments().size()) {
            writer.line("; ERROR: Serial.printf: format specifier '%" + spec
                    + "' has no matching argument");
            return false;
        }
        ValueType p = insn.getMethod().getDescriptor().parameterType(argIdx);
        boolean isString = (p instanceof ValueType.Object o)
                && "java.lang.String".equals(o.getClassName());
        if (wantString != isString) {
            writer.line("; ERROR: Serial.printf: specifier '%" + spec + "' expects a "
                    + (wantString ? "String" : "numeric") + " argument");
            return false;
        }
        return true;
    }

}
