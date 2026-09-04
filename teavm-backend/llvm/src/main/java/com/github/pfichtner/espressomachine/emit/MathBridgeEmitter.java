package com.github.pfichtner.espressomachine.emit;

import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isClassname;
import static com.github.pfichtner.espressomachine.emit.InvokeInstructions.isUsedIn;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits LLVM IR for intercepted {@link RuntimeMathBridge} calls.
 *
 * The class transformer in {@code IrDumper} replaces
 * {@code java.lang.Math.min(int,int)} / {@code max(int,int)} /
 * {@code abs(int)} / {@code pow(double,double)} / {@code sqrt(double)}
 * invokes with calls to {@link RuntimeMathBridge}.
 * This emitter intercepts those bridge calls and emits:
 * <ul>
 *   <li>{@code @llvm.smin.i32} / {@code @llvm.smax.i32} / {@code @llvm.abs.i32}
 *       for integer operations (built-in LLVM intrinsics, no link dependency)</li>
 *   <li>{@code @pow} / {@code @sqrt} for floating-point operations</li>
 * </ul>
 */
public class MathBridgeEmitter implements IntrinsicEmitter {

    public static final String CLASS = "com.github.pfichtner.espressomachine.emit.RuntimeMathBridge";

    public boolean canHandle(String className) {
        return CLASS.equals(className);
    }

    public int emit(LlvmWriter writer, InvokeInstruction insn,
                    Map<Integer, String> constVars,
                    Function<Variable, String> resolveVar,
                    Map<Integer, String> objectRefs) {
        if (insn.getReceiver() == null) return writer.tmpCounter();
        String recv = resolveVar.apply(insn.getReceiver());
        List<? extends Variable> args = insn.getArguments();

        switch (insn.getMethod().getName()) {
            case "mathMinInt" -> {
                String a = resolveVar.apply(args.get(0));
                String b = resolveVar.apply(args.get(1));
                writer.callI32(recv, "llvm.smin.i32", a, b);
            }
            case "mathMaxInt" -> {
                String a = resolveVar.apply(args.get(0));
                String b = resolveVar.apply(args.get(1));
                writer.callI32(recv, "llvm.smax.i32", a, b);
            }
            case "mathAbsInt" -> {
                String a = resolveVar.apply(args.get(0));
                writer.line(recv + " = call i32 @llvm.abs.i32(i32 " + a + ", i1 false)");
            }
            case "mathPow" -> {
                String a = resolveVar.apply(args.get(0));
                String b = resolveVar.apply(args.get(1));
                writer.line(recv + " = call double @pow(double " + a + ", double " + b + ")");
            }
            case "mathSqrt" -> {
                String a = resolveVar.apply(args.get(0));
                writer.line(recv + " = call double @sqrt(double " + a + ")");
            }
        }
        return writer.tmpCounter();
    }

    public String declarations(Map<String, Program> programs) {
        if (!isUsedIn(programs, isClassname(CLASS))) return "";
        return """
            declare double @pow(double, double)
            declare double @sqrt(double)
            """;
    }
}
