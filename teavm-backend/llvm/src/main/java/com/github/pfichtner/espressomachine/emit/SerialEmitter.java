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
        String method = insn.getMethod().getName();
        List<? extends Variable> args = insn.getArguments();
        switch (method) {
            case "begin"     -> emitBegin(writer, args, constVars, resolveVar);
            case "write"     -> emitWrite(writer, args, resolveVar);
            case "available" -> emitAvailable(writer, insn, resolveVar);
            case "read"      -> emitRead(writer, insn, resolveVar);
            case "print"     -> emitPrint(writer, args, insn, objectRefs, resolveVar);
            case "println"   -> emitPrintln(writer, args, insn, objectRefs, resolveVar);
            default          -> emitFallback(writer, insn, args, resolveVar);
        }
        return writer.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        if (!isUsedIn(programs, InvokeInstructions.isClassname(CLASS))) return "";
        return """
                declare void @__espressomachine_serial_begin(i32 %baud)
                declare void @__espressomachine_serial_write(i32 %b)
                declare void @__espressomachine_serial_print_int(i32 %n)
                declare void @__espressomachine_serial_print_str(ptr %s)
                """;
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
            writer.callVoidPtr("__espressomachine_serial_print_str", target);
        } else {
        	// Numeric (int or char) — transmit the decimal representation.
        	writer.callVoid("__espressomachine_serial_print_int", resolveVar.apply(arg));
		}

    }

}
