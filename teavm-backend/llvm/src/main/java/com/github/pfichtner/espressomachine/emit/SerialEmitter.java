package com.github.pfichtner.espressomachine.emit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    public SerialEmitter() {}

    public boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    /**
     * Emit a Serial intrinsic call into {@code w}.
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
            case "begin"     -> emitBegin(w, args, constVars, resolveVar);
            case "write"     -> emitWrite(w, args, resolveVar);
            case "available" -> emitAvailable(w, insn, resolveVar);
            case "read"      -> emitRead(w, insn, resolveVar);
            case "print"     -> emitPrint(w, args, insn, objectRefs, resolveVar);
            case "println"   -> emitPrintln(w, args, insn, objectRefs, resolveVar);
            default          -> emitFallback(w, insn, args, resolveVar);
        }
        return w.tmpCounter();
    }

    public String declarations() {
        return """
                declare void @__espressomachine_serial_begin(i32 %baud)
                declare void @__espressomachine_serial_write(i32 %b)
                declare void @__espressomachine_serial_print_int(i32 %n)
                declare void @__espressomachine_serial_print_str(ptr %s)
                """;
    }

    // ---- Internal helpers ----

    private void emitBegin(LlvmWriter w, List<? extends Variable> args,
                                  Map<Integer, String> constVars,
                                  Function<Variable, String> resolveVar) {
        Integer baud = constInt(args.get(0), constVars);
        if (baud != null && baud > 0) {
            int ubrr = F_CPU / (16 * baud) - 1;
            w.storeVolatile(String.valueOf((ubrr >> 8) & 0xFF), RegisterFile.UBRR0H);
            w.storeVolatile(String.valueOf(ubrr & 0xFF), RegisterFile.UBRR0L);
            w.storeVolatile(String.valueOf(RXEN_TXEN), RegisterFile.UCSR0B);
            w.storeVolatile(String.valueOf(FRAME_8N1), RegisterFile.UCSR0C);
        } else {
            w.callVoid("__espressomachine_serial_begin", resolveVar.apply(args.get(0)));
        }
    }

    private void emitWrite(LlvmWriter w, List<? extends Variable> args,
                                  Function<Variable, String> resolveVar) {
        w.callVoid("__espressomachine_serial_write", resolveVar.apply(args.get(0)));
    }

    private void emitAvailable(LlvmWriter w, InvokeInstruction insn,
                                      Function<Variable, String> resolveVar) {
        // Read RXC0 (bit 7 = 0x80) from UCSR0A; return 1 if set, 0 otherwise.
        String t0 = w.temp();
        String t1 = w.temp();
        String t2 = w.temp();
        w.loadVolatile(t0, RegisterFile.UCSR0A);
        w.and8Raw(t1, t0, -128);   // mask RXC0 (bit 7, = 0x80 as signed i8)
        w.icmpNe8(t2, t1);
        if (insn.getReceiver() != null) {
            w.zext1to32(resolveVar.apply(insn.getReceiver()), t2);
        }
    }

    private void emitRead(LlvmWriter w, InvokeInstruction insn,
                                 Function<Variable, String> resolveVar) {
        // Read UDR0 and zero-extend to i32; call available() first.
        String t0 = w.temp();
        w.loadVolatile(t0, RegisterFile.UDR0);
        if (insn.getReceiver() != null) {
            w.zext8to32(resolveVar.apply(insn.getReceiver()), t0);
        }
    }

    private void emitPrintln(LlvmWriter w, List<? extends Variable> args,
                                    InvokeInstruction insn,
                                    Map<Integer, String> objectRefs,
                                    Function<Variable, String> resolveVar) {
        if (args.isEmpty()) {
            // println() → CR + LF
            w.callVoid("__espressomachine_serial_write", CR);
            w.callVoid("__espressomachine_serial_write", LF);
            return;
        }
        emitPrint(w, args, insn, objectRefs, resolveVar);
        w.callVoid("__espressomachine_serial_write", CR);
        w.callVoid("__espressomachine_serial_write", LF);
    }

    private void emitPrint(LlvmWriter w, List<? extends Variable> args,
                                  InvokeInstruction insn,
                                  Map<Integer, String> objectRefs,
                                  Function<Variable, String> resolveVar) {
        if (args.isEmpty()) return;
        Variable arg = args.get(0);

        // Distinguish by the declared parameter type: String (object) vs char/int.
        ValueType ptype = insn.getMethod().getDescriptor().parameterType(0);
        boolean isString = (ptype instanceof ValueType.Object)
                && "java.lang.String".equals(((ValueType.Object) ptype).getClassName());

        if (isString) {
            // String: prefer a string-literal global, else the raw ptr.
            String global = objectRefs.get(arg.getIndex());
            String target = (global != null) ? global : resolveVar.apply(arg);
            w.callVoidPtr("__espressomachine_serial_print_str", target);
            return;
        }

        // Numeric (int or char) — transmit the decimal representation.
        w.callVoid("__espressomachine_serial_print_int", resolveVar.apply(arg));
    }

    private void emitFallback(LlvmWriter w, InvokeInstruction insn,
                                     List<? extends Variable> args,
                                     Function<Variable, String> resolveVar) {
        String fqn = insn.getMethod().getClassName();
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        w.callVoid("__espressomachine_" + simpleName.toLowerCase() + "_" + insn.getMethod().getName(),
                args.stream().map(resolveVar).toArray());
    }

    Integer constInt(Variable v, Map<Integer, String> constVars) {
        String s = constVars.get(v.getIndex());
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }
}
