package tinyjava;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.teavm.model.BasicBlock;
import org.teavm.model.Instruction;
import org.teavm.model.MethodReader;
import org.teavm.model.Phi;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.AbstractInstructionVisitor;
import org.teavm.model.instructions.AssignInstruction;
import org.teavm.model.instructions.BinaryBranchingInstruction;
import org.teavm.model.instructions.BinaryInstruction;
import org.teavm.model.instructions.BinaryOperation;
import org.teavm.model.instructions.BranchingCondition;
import org.teavm.model.instructions.BranchingInstruction;
import org.teavm.model.instructions.CastIntegerInstruction;
import org.teavm.model.instructions.CastNumberInstruction;
import org.teavm.model.instructions.DoubleConstantInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.FloatConstantInstruction;
import org.teavm.model.instructions.InitClassInstruction;
import org.teavm.model.instructions.IntegerConstantInstruction;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.JumpInstruction;
import org.teavm.model.instructions.LongConstantInstruction;
import org.teavm.model.instructions.NegateInstruction;
import org.teavm.model.instructions.NullCheckInstruction;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.GetFieldInstruction;
import org.teavm.model.instructions.NullConstantInstruction;
import org.teavm.model.instructions.PutFieldInstruction;

/**
 * Translates a single TeaVM method Program to LLVM IR text.
 *
 * Phase 1 coverage: arithmetic, comparisons, branches, loops, PHI nodes,
 * static/special invocations, integer constants, returns.
 */
class LlvmMethodEmitter extends AbstractInstructionVisitor {

    // When a variable holds the result of a COMPARE instruction we record the
    // two operands and the operation so we can fuse it with the following branch.
    record CompareInfo(String op, String a, String b) {}

    // Resolved text form of a variable (may be an inline constant literal).
    private final Map<Integer, String> varLiteral = new HashMap<>();

    // Variables produced by COMPARE instructions (fused into branch icmp).
    private final Map<Integer, CompareInfo> compareVars = new HashMap<>();

    // Counter for anonymous LLVM values (%tmp0, %tmp1, …) used inside branches.
    private int tmpCounter = 0;

    private final StringBuilder out;
    private final Program program;
    private final MethodReader method;
    // May be null (Phase 0/1 compatibility).
    private final LlvmModuleEmitter module;

    // Set before each instruction is visited so visitor methods can refer to it.
    private int currentBlock = -1;

    LlvmMethodEmitter(StringBuilder out, Program program, MethodReader method) {
        this(out, program, method, null);
    }

    // Escape analysis result — set once per method in emit().
    private EscapeAnalyzer escape;

    LlvmMethodEmitter(StringBuilder out, Program program, MethodReader method,
                      LlvmModuleEmitter module) {
        this.out = out;
        this.program = program;
        this.method = method;
        this.module = module;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    void emit() {
        escape = EscapeAnalyzer.analyze(program, method);
        emitFunctionHeader();
        for (int i = 0; i < program.basicBlockCount(); i++) {
            BasicBlock bb = program.basicBlockAt(i);
            if (bb == null) continue;
            currentBlock = i;
            emitBlock(bb, i);
        }
        out.append("}\n\n");
    }

    // ------------------------------------------------------------------
    // Function header
    // ------------------------------------------------------------------

    private void emitFunctionHeader() {
        String retType = llvmType(method.getResultType());
        String name = mangle(method);
        List<String> params = buildParams();
        out.append("define ").append(retType).append(" @").append(name)
           .append("(").append(String.join(", ", params)).append(") {\n");
    }

    private List<String> buildParams() {
        List<String> params = new ArrayList<>();
        boolean isStatic = method.hasModifier(org.teavm.model.ElementModifier.STATIC);
        // TeaVM reserves variable 0 for 'this'; parameters start at index 0 for
        // instance methods (%v0 = this) and at index 1 for static methods.
        int varIndex = isStatic ? 1 : 0;
        if (!isStatic) {
            params.add("ptr " + v(varIndex++));
        }
        for (ValueType paramType : method.getParameterTypes()) {
            params.add(llvmType(paramType) + " " + v(varIndex++));
        }
        return params;
    }

    // ------------------------------------------------------------------
    // Basic block
    // ------------------------------------------------------------------

    private void emitBlock(BasicBlock bb, int index) {
        out.append(bbLabel(index)).append(":\n");

        // PHI nodes at block entry
        for (Phi phi : bb.getPhis()) {
            StringBuilder phiLine = new StringBuilder();
            phiLine.append("  ").append(v(phi.getReceiver())).append(" = phi ")
                   .append(llvmIntType(phi.getReceiver())).append(" ");
            List<String> arms = new ArrayList<>();
            for (var inc : phi.getIncomings()) {
                arms.add("[ " + resolveVar(inc.getValue()) + ", %" + bbLabel(inc.getSource().getIndex()) + " ]");
            }
            phiLine.append(String.join(", ", arms));
            out.append(phiLine).append("\n");
        }

        // Instructions
        for (Instruction insn : bb) {
            insn.acceptVisitor(this);
        }
    }

    // ------------------------------------------------------------------
    // Visitor implementations
    // ------------------------------------------------------------------

    @Override
    public void visit(IntegerConstantInstruction insn) {
        // Record as inline constant; emit a materialization instruction so that
        // LLVM can see the value even if the variable is used across blocks.
        varLiteral.put(insn.getReceiver().getIndex(), String.valueOf(insn.getConstant()));
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = add i32 0, ").append(insn.getConstant()).append("\n");
    }

    @Override
    public void visit(LongConstantInstruction insn) {
        varLiteral.put(insn.getReceiver().getIndex(), insn.getConstant() + "");
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = add i64 0, ").append(insn.getConstant()).append("\n");
    }

    @Override
    public void visit(FloatConstantInstruction insn) {
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = fadd float 0.0, ").append(floatHex(insn.getConstant())).append("\n");
    }

    @Override
    public void visit(DoubleConstantInstruction insn) {
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = fadd double 0.0, ").append(doubleHex(insn.getConstant())).append("\n");
    }

    @Override
    public void visit(NullConstantInstruction insn) {
        out.append("  ").append(v(insn.getReceiver())).append(" = inttoptr i32 0 to ptr\n");
    }

    @Override
    public void visit(AssignInstruction insn) {
        // Propagate literals through assign chains.
        String lit = varLiteral.get(insn.getAssignee().getIndex());
        if (lit != null) varLiteral.put(insn.getReceiver().getIndex(), lit);
        CompareInfo ci = compareVars.get(insn.getAssignee().getIndex());
        if (ci != null) compareVars.put(insn.getReceiver().getIndex(), ci);
        // Emit a bitcast-style identity for the variable.
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = add i32 0, ").append(resolveVar(insn.getAssignee())).append("\n");
    }

    @Override
    public void visit(BinaryInstruction insn) {
        BinaryOperation op = insn.getOperation();
        String type = llvmNumericType(insn.getOperandType());

        if (op == BinaryOperation.COMPARE) {
            // COMPARE produces sign(a - b): 1 if a>b, 0 if a==b, -1 if a<b.
            // Record operands for fusion with the immediately following BranchingInstruction.
            String a = resolveVar(insn.getFirstOperand());
            String b = resolveVar(insn.getSecondOperand());
            // Use "sgt" as the base direction: compareVars stores (a, b) so that
            // compareCondToIcmp() can select the correct predicate.
            compareVars.put(insn.getReceiver().getIndex(),
                    new CompareInfo("sgt", a, b));
            // Also emit a sub so the variable is a valid integer if used standalone.
            out.append("  ").append(v(insn.getReceiver()))
               .append(" = sub ").append(type).append(" ")
               .append(a).append(", ").append(b).append("\n");
            return;
        }

        String llvmOp = binaryOp(op, insn.getOperandType());
        String a = resolveVar(insn.getFirstOperand());
        String b = resolveVar(insn.getSecondOperand());
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = ").append(llvmOp).append(" ").append(type)
           .append(" ").append(a).append(", ").append(b).append("\n");
    }

    @Override
    public void visit(NegateInstruction insn) {
        String type = llvmNumericType(insn.getOperandType());
        String zero = insn.getOperandType() == org.teavm.model.instructions.NumericOperandType.FLOAT
                || insn.getOperandType() == org.teavm.model.instructions.NumericOperandType.DOUBLE
                ? "fneg " + type + " " + resolveVar(insn.getOperand())
                : "sub " + type + " 0, " + resolveVar(insn.getOperand());
        out.append("  ").append(v(insn.getReceiver())).append(" = ").append(zero).append("\n");
    }

    @Override
    public void visit(CastNumberInstruction insn) {
        String from = llvmNumericType(insn.getSourceType());
        String to   = llvmNumericType(insn.getTargetType());
        String castOp = numericCast(insn.getSourceType(), insn.getTargetType());
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = ").append(castOp).append(" ").append(from)
           .append(" ").append(resolveVar(insn.getValue()))
           .append(" to ").append(to).append("\n");
    }

    @Override
    public void visit(CastIntegerInstruction insn) {
        // Truncation to byte/short/char.
        String targetLLVM = integerSubtypeLLVM(insn.getTargetType());
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = trunc i32 ").append(resolveVar(insn.getValue()))
           .append(" to ").append(targetLLVM).append("\n");
    }

    @Override
    public void visit(ExitInstruction insn) {
        if (insn.getValueToReturn() == null) {
            out.append("  ret void\n");
        } else {
            String type = llvmType(method.getResultType());
            out.append("  ret ").append(type).append(" ")
               .append(resolveVar(insn.getValueToReturn())).append("\n");
        }
    }

    @Override
    public void visit(JumpInstruction insn) {
        out.append("  br label %").append(bbLabel(insn.getTarget().getIndex())).append("\n");
    }

    @Override
    public void visit(BranchingInstruction insn) {
        String tmp = "%cond" + tmpCounter++;
        CompareInfo ci = compareVars.get(insn.getOperand().getIndex());
        if (ci != null) {
            // Fuse: operand was produced by COMPARE_* — map branch condition to icmp predicate.
            String icmpOp = compareCondToIcmp(ci.op, insn.getCondition());
            out.append("  ").append(tmp).append(" = icmp ").append(icmpOp)
               .append(" i32 ").append(ci.a).append(", ").append(ci.b).append("\n");
        } else {
            // Generic: compare the integer variable against 0.
            String icmpOp = conditionToIcmpVsZero(insn.getCondition());
            out.append("  ").append(tmp).append(" = icmp ").append(icmpOp)
               .append(" i32 ").append(resolveVar(insn.getOperand())).append(", 0\n");
        }
        out.append("  br i1 ").append(tmp)
           .append(", label %").append(bbLabel(insn.getConsequent().getIndex()))
           .append(", label %").append(bbLabel(insn.getAlternative().getIndex())).append("\n");
    }

    @Override
    public void visit(BinaryBranchingInstruction insn) {
        String tmp = "%cond" + tmpCounter++;
        String icmpOp = switch (insn.getCondition()) {
            case EQUAL, REFERENCE_EQUAL -> "eq";
            case NOT_EQUAL, REFERENCE_NOT_EQUAL -> "ne";
        };
        String type = "i32";  // TeaVM uses int for binary branching conditions
        out.append("  ").append(tmp).append(" = icmp ").append(icmpOp).append(" ")
           .append(type).append(" ")
           .append(resolveVar(insn.getFirstOperand())).append(", ")
           .append(resolveVar(insn.getSecondOperand())).append("\n");
        out.append("  br i1 ").append(tmp)
           .append(", label %").append(bbLabel(insn.getConsequent().getIndex()))
           .append(", label %").append(bbLabel(insn.getAlternative().getIndex())).append("\n");
    }

    @Override
    public void visit(InvokeInstruction insn) {
        StringBuilder call = new StringBuilder("  ");
        if (insn.getReceiver() != null) {
            call.append(v(insn.getReceiver())).append(" = ");
        }
        String retType = llvmType(insn.getMethod().getReturnType());
        call.append("call ").append(retType).append(" @")
            .append(mangle(insn.getMethod().getClassName(), insn.getMethod().getName()))
            .append("(");
        List<String> args = new ArrayList<>();
        if (insn.getInstance() != null) {
            args.add("ptr " + resolveVar(insn.getInstance()));
        }
        for (int i = 0; i < insn.getArguments().size(); i++) {
            var param = insn.getMethod().getDescriptor().parameterType(i);
            args.add(llvmType(param) + " " + resolveVar(insn.getArguments().get(i)));
        }
        call.append(String.join(", ", args)).append(")");
        out.append(call).append("\n");
    }

    @Override
    public void visit(InitClassInstruction insn) {
        // Class initializers are invoked at link time for embedded targets; emit a comment.
        out.append("  ; init_class ").append(insn.getClassName()).append("\n");
    }

    @Override
    public void visit(NullCheckInstruction insn) {
        // For embedded targets, null checks are omitted. Propagate the variable.
        compareVars.computeIfPresent(insn.getValue().getIndex(),
                (k, v) -> { compareVars.put(insn.getReceiver().getIndex(), v); return v; });
        varLiteral.computeIfPresent(insn.getValue().getIndex(),
                (k, v) -> { varLiteral.put(insn.getReceiver().getIndex(), v); return v; });
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = add i32 0, ").append(resolveVar(insn.getValue())).append("\n");
    }

    @Override
    public void visit(ConstructInstruction insn) {
        int idx = insn.getReceiver().getIndex();
        EscapeAnalyzer.Fate f = (escape != null)
                ? escape.fateOf(idx) : EscapeAnalyzer.Fate.STACK;
        String structType = "%" + LlvmModuleEmitter.llvmStructName(insn.getType());
        switch (f) {
            case STACK -> {
                // Non-escaping: safe to stack-allocate.
                out.append("  ").append(v(insn.getReceiver()))
                   .append(" = alloca ").append(structType).append("\n");
            }
            case STATIC -> {
                // Stored to a static field in <clinit>: the module emitter will
                // have generated a global struct; hand back a ptr to it.
                var field = escape.staticFieldOf(idx);
                String globalName = field != null
                        ? "@" + LlvmMethodEmitter.mangle(field.getClassName(), field.getFieldName())
                        : "@__unknown_static_obj";
                out.append("  ").append(v(insn.getReceiver()))
                   .append(" = getelementptr ").append(structType)
                   .append(", ptr ").append(globalName)
                   .append(", i32 0").append("\n");
            }
            case ESCAPE -> {
                // Heap allocation would be needed — not supported for embedded targets.
                out.append("  ; ERROR: allocation of ").append(insn.getType())
                   .append(" escapes stack frame — heap allocation not supported on ATmega328P\n");
                out.append("  ; This method cannot be compiled for the embedded target.\n");
                // Emit unreachable to satisfy LLVM IR well-formedness while flagging the issue.
                out.append("  ").append(v(insn.getReceiver()))
                   .append(" = inttoptr i32 0 to ptr ; UNSUPPORTED_ESCAPE\n");
            }
        }
    }

    @Override
    public void visit(GetFieldInstruction insn) {
        String fieldName = insn.getField().getFieldName();
        String className = insn.getField().getClassName();
        if (insn.getInstance() != null) {
            // Instance field: getelementptr + load
            int idx = fieldIndex(className, fieldName);
            String structType = "%" + LlvmModuleEmitter.llvmStructName(className);
            ValueType fieldType = fieldValueType(className, idx);
            String llvmType = llvmType(fieldType);
            String gepVar = "%gep" + tmpCounter++;
            out.append("  ").append(gepVar)
               .append(" = getelementptr ").append(structType)
               .append(", ptr ").append(resolveVar(insn.getInstance()))
               .append(", i32 0, i32 ").append(idx).append("\n");
            out.append("  ").append(v(insn.getReceiver()))
               .append(" = load ").append(llvmType).append(", ptr ").append(gepVar).append("\n");
        } else {
            // Static field.
            String globalName = "@" + mangle(className, fieldName);
            ValueType fieldType = staticFieldType(className, fieldName);
            if (isStaticObjectField(className, fieldName)) {
                // Static object field: the global IS the struct — return ptr to it.
                out.append("  ").append(v(insn.getReceiver()))
                   .append(" = getelementptr i8, ptr ").append(globalName)
                   .append(", i32 0\n");
            } else {
                String llvmType = llvmType(fieldType);
                out.append("  ").append(v(insn.getReceiver()))
                   .append(" = load ").append(llvmType).append(", ptr ").append(globalName).append("\n");
            }
        }
    }

    @Override
    public void visit(PutFieldInstruction insn) {
        String fieldName = insn.getField().getFieldName();
        String className = insn.getField().getClassName();
        if (insn.getInstance() != null) {
            // Instance field: getelementptr + store
            int idx = fieldIndex(className, fieldName);
            String structType = "%" + LlvmModuleEmitter.llvmStructName(className);
            ValueType fieldType = fieldValueType(className, idx);
            String llvmType = llvmType(fieldType);
            String gepVar = "%gep" + tmpCounter++;
            out.append("  ").append(gepVar)
               .append(" = getelementptr ").append(structType)
               .append(", ptr ").append(resolveVar(insn.getInstance()))
               .append(", i32 0, i32 ").append(idx).append("\n");
            out.append("  store ").append(llvmType).append(" ")
               .append(resolveVar(insn.getValue()))
               .append(", ptr ").append(gepVar).append("\n");
        } else {
            // Static field.
            String globalName = "@" + mangle(className, fieldName);
            ValueType fieldType = staticFieldType(className, fieldName);
            if (isStaticObjectField(className, fieldName)) {
                // Static object field: the global IS the struct; store is a no-op
                // because the allocation was already initialized as a global.
                out.append("  ; static object already initialized as global: ").append(globalName).append("\n");
                return;
            }
            String llvmType = llvmType(fieldType);
            out.append("  store ").append(llvmType).append(" ")
               .append(resolveVar(insn.getValue()))
               .append(", ptr ").append(globalName).append("\n");
        }
    }

    private int fieldIndex(String className, String fieldName) {
        if (module != null) {
            var map = module.fieldIndices.get(className);
            if (map != null && map.containsKey(fieldName)) return map.get(fieldName);
        }
        return 0;
    }

    private ValueType fieldValueType(String className, int index) {
        if (module != null) {
            var list = module.fieldTypes.get(className);
            if (list != null && index < list.size()) return list.get(index);
        }
        return ValueType.INTEGER;
    }

    private ValueType staticFieldType(String className, String fieldName) {
        if (module != null) {
            var cls = module.classes.get(className);
            if (cls != null) {
                for (var f : cls.getFields()) {
                    if (f.getName().equals(fieldName)) return f.getType();
                }
            }
        }
        return ValueType.INTEGER;
    }

    private boolean isStaticObjectField(String className, String fieldName) {
        return module != null
                && module.staticObjectFields.contains(className + "." + fieldName);
    }

    @Override
    public void visit(org.teavm.model.instructions.EmptyInstruction insn) {
        // no-op
    }

    @Override
    public void visit(org.teavm.model.instructions.MonitorEnterInstruction insn) {
        out.append("  ; monitorenter (unsupported — no threading)\n");
    }

    @Override
    public void visit(org.teavm.model.instructions.MonitorExitInstruction insn) {
        out.append("  ; monitorexit (unsupported — no threading)\n");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String resolveVar(Variable v) {
        String lit = varLiteral.get(v.getIndex());
        return lit != null ? lit : v(v);
    }

    private static String v(Variable v) {
        return "%v" + v.getIndex();
    }

    private static String v(int index) {
        return "%v" + index;
    }

    private static String bbLabel(int index) {
        return "BB" + index;
    }

    // LLVM integer type for a variable — we use i32 for all integers in Phase 1.
    private static String llvmIntType(Variable ignored) {
        return "i32";
    }

    static String llvmType(ValueType t) {
        if (t == null || t instanceof ValueType.Void) return "void";
        if (t instanceof ValueType.Primitive p) {
            return switch (p.getKind()) {
                case BOOLEAN, BYTE -> "i8";
                case SHORT, CHARACTER -> "i16";
                case INTEGER -> "i32";
                case LONG -> "i64";
                case FLOAT -> "float";
                case DOUBLE -> "double";
            };
        }
        if (t instanceof ValueType.Object) return "ptr";
        if (t instanceof ValueType.Array) return "ptr";
        return "i32";
    }

    private static String llvmNumericType(org.teavm.model.instructions.NumericOperandType t) {
        return switch (t) {
            case INT -> "i32";
            case LONG -> "i64";
            case FLOAT -> "float";
            case DOUBLE -> "double";
        };
    }

    private static String binaryOp(BinaryOperation op,
                                   org.teavm.model.instructions.NumericOperandType type) {
        boolean fp = type == org.teavm.model.instructions.NumericOperandType.FLOAT
                  || type == org.teavm.model.instructions.NumericOperandType.DOUBLE;
        return switch (op) {
            case ADD -> fp ? "fadd" : "add";
            case SUBTRACT -> fp ? "fsub" : "sub";
            case MULTIPLY -> fp ? "fmul" : "mul";
            case DIVIDE -> fp ? "fdiv" : "sdiv";
            case MODULO -> fp ? "frem" : "srem";
            case AND -> "and";
            case OR -> "or";
            case XOR -> "xor";
            case SHIFT_LEFT -> "shl";
            case SHIFT_RIGHT -> "ashr";
            case SHIFT_RIGHT_UNSIGNED -> "lshr";
            case COMPARE -> "sub";  // handled above; fallback
            default -> throw new IllegalStateException("Unhandled binary op: " + op);
        };
    }

    // Map COMPARE_* operation + BranchingCondition → icmp predicate string.
    // ci.op is "sgt" (COMPARE_GREATER) or "slt" (COMPARE_LESS).
    // condition is what the branch checks against the compare result (compare vs. 0).
    private static String compareCondToIcmp(String compareOp, BranchingCondition condition) {
        // COMPARE_GREATER(a, b) → result = sign(a - b).
        // branch result if LESS_OR_EQUAL → a <= b → icmp sle a, b
        // branch result if GREATER → a > b → icmp sgt a, b
        // ci.a and ci.b are already set up as (a, b) for COMPARE_GREATER or (b, a) for COMPARE_LESS.
        // So we just need to map the BranchingCondition to the right icmp predicate relative to 0.
        return switch (condition) {
            case EQUAL          -> "eq";
            case NOT_EQUAL      -> "ne";
            case LESS           -> "slt";
            case LESS_OR_EQUAL  -> "sle";
            case GREATER        -> "sgt";
            case GREATER_OR_EQUAL -> "sge";
            // NULL/NOT_NULL don't apply to integer compare results
            case NULL -> "eq";
            case NOT_NULL -> "ne";
        };
    }

    private static String conditionToIcmpVsZero(BranchingCondition condition) {
        return switch (condition) {
            case EQUAL, NULL           -> "eq";
            case NOT_EQUAL, NOT_NULL   -> "ne";
            case LESS                  -> "slt";
            case LESS_OR_EQUAL         -> "sle";
            case GREATER               -> "sgt";
            case GREATER_OR_EQUAL      -> "sge";
        };
    }

    private static String numericCast(org.teavm.model.instructions.NumericOperandType from,
                                      org.teavm.model.instructions.NumericOperandType to) {
        boolean fromFP = from == org.teavm.model.instructions.NumericOperandType.FLOAT
                      || from == org.teavm.model.instructions.NumericOperandType.DOUBLE;
        boolean toFP = to == org.teavm.model.instructions.NumericOperandType.FLOAT
                    || to == org.teavm.model.instructions.NumericOperandType.DOUBLE;
        int fromBits = typeBits(from);
        int toBits = typeBits(to);
        if (fromFP && toFP) return fromBits < toBits ? "fpext" : "fptrunc";
        if (fromFP) return "fptosi";
        if (toFP) return "sitofp";
        return fromBits < toBits ? "sext" : "trunc";
    }

    private static int typeBits(org.teavm.model.instructions.NumericOperandType t) {
        return switch (t) {
            case INT -> 32;
            case LONG -> 64;
            case FLOAT -> 32;
            case DOUBLE -> 64;
        };
    }

    private static String integerSubtypeLLVM(org.teavm.model.instructions.IntegerSubtype t) {
        return switch (t) {
            case BYTE -> "i8";
            case SHORT -> "i16";
            case CHAR -> "i16";
        };
    }

    static String mangle(MethodReader method) {
        return mangle(method.getReference().getClassName(), method.getName());
    }

    static String mangle(org.teavm.model.MethodReference ref) {
        return mangle(ref.getClassName(), ref.getName());
    }

    static String mangle(String className, String methodName) {
        // Replace dots and angle brackets with underscores.
        String cls = className.replace('.', '_').replace('/', '_');
        String name = methodName.replace('<', '_').replace('>', '_');
        return cls + "_" + name;
    }

    // LLVM requires exact float representation via hex literals.
    private static String floatHex(float v) {
        long bits = Double.doubleToRawLongBits((double) v);
        return String.format("0x%016X", bits);
    }

    private static String doubleHex(double v) {
        long bits = Double.doubleToRawLongBits(v);
        return String.format("0x%016X", bits);
    }
}
