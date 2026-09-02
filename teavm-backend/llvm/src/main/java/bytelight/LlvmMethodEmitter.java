package bytelight;

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
import org.teavm.model.instructions.BinaryBranchingCondition;
import org.teavm.model.instructions.BinaryBranchingInstruction;
import org.teavm.model.instructions.BinaryInstruction;
import org.teavm.model.instructions.BinaryOperation;
import org.teavm.model.instructions.BranchingCondition;
import org.teavm.model.instructions.BranchingInstruction;
import org.teavm.model.instructions.CastIntegerInstruction;
import org.teavm.model.instructions.CastNumberInstruction;
import org.teavm.model.instructions.ConstructArrayInstruction;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.DoubleConstantInstruction;
import org.teavm.model.instructions.EmptyInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.FloatConstantInstruction;
import org.teavm.model.instructions.GetElementInstruction;
import org.teavm.model.instructions.GetFieldInstruction;
import org.teavm.model.instructions.InitClassInstruction;
import org.teavm.model.instructions.IntegerConstantInstruction;
import org.teavm.model.instructions.IntegerSubtype;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.JumpInstruction;
import org.teavm.model.instructions.LongConstantInstruction;
import org.teavm.model.instructions.MonitorEnterInstruction;
import org.teavm.model.instructions.MonitorExitInstruction;
import org.teavm.model.instructions.NegateInstruction;
import org.teavm.model.instructions.NullCheckInstruction;
import org.teavm.model.instructions.NullConstantInstruction;
import org.teavm.model.instructions.NumericOperandType;
import org.teavm.model.instructions.PutElementInstruction;
import org.teavm.model.instructions.PutFieldInstruction;
import org.teavm.model.instructions.RaiseInstruction;
import org.teavm.model.instructions.StringConstantInstruction;
import org.teavm.model.instructions.SwitchInstruction;

/**
 * Translates a single TeaVM method Program to LLVM IR text.
 *
 * Phase 1 coverage: arithmetic, comparisons, branches, loops, PHI nodes,
 * static/special invocations, integer constants, returns.
 */
class LlvmMethodEmitter extends AbstractInstructionVisitor {

    private static final String JAVA_LANG_ENUM = Enum.class.getName();

	// When a variable holds the result of a COMPARE instruction we record the
    // two operands and the operation so we can fuse it with the following branch.
    record CompareInfo(String op, String a, String b) {}

    // Resolved text form of a variable (may be an inline constant literal).
    private final Map<Integer, String> varLiteral = new HashMap<>();

    // Variable index → LLVM global symbol for a compile-time static object reference
    // (e.g. an enum constant read such as `TimeUnit.SECONDS`). Used by intrinsic
    // lowering to identify constant arguments (Delay.time unit, GPIO pin, ...).
    private final Map<Integer, String> staticObjectRef = new HashMap<>();

    // Variables produced by COMPARE instructions (fused into branch icmp).
    private final Map<Integer, CompareInfo> compareVars = new HashMap<>();

    // Counter for anonymous LLVM values (%tmp0, %tmp1, …) used inside branches.
    private int tmpCounter = 0;

    private final StringBuilder out;
    private final Program program;
    private final MethodReader method;
    // May be null (Phase 0/1 compatibility).
    private final LlvmModuleEmitter module;

    LlvmMethodEmitter(StringBuilder out, Program program, MethodReader method) {
        this(out, program, method, null);
    }

    // Escape analysis result — set once per method in emit().
    private EscapeAnalyzer escape;

    // True when emitting an enum class's <clinit> — controls string/init/values handling.
    private final boolean inEnumClinit;
    // The enum class name, when inEnumClinit is true.
    private final String enumClassName;

    LlvmMethodEmitter(StringBuilder out, Program program, MethodReader method,
                      LlvmModuleEmitter module) {
        this.out = out;
        this.program = program;
        this.method = method;
        this.module = module;
        String cls = method.getReference().getClassName();
        boolean isEnumClass = module != null && module.enumClasses.contains(cls);
        this.inEnumClinit = isEnumClass && "<clinit>".equals(method.getName());
        this.enumClassName = isEnumClass ? cls : null;
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
        String ref = staticObjectRef.get(insn.getAssignee().getIndex());
        if (ref != null) staticObjectRef.put(insn.getReceiver().getIndex(), ref);
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

        if (op == BinaryOperation.COMPARE_GREATER || op == BinaryOperation.COMPARE_LESS) {
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
        String zero = insn.getOperandType() == NumericOperandType.FLOAT
                || insn.getOperandType() == NumericOperandType.DOUBLE
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
            String valStr = resolveVar(insn.getValueToReturn());
            // Boolean/byte/short/char are stored as i32 internally but returned as i8/i16.
            // Truncate when narrowing to avoid type mismatch.
            if ("i8".equals(type) || "i16".equals(type)) {
                String tmp = "%rettrunc" + tmpCounter++;
                out.append("  ").append(tmp).append(" = trunc i32 ")
                   .append(valStr).append(" to ").append(type).append("\n");
                valStr = tmp;
            }
            out.append("  ret ").append(type).append(" ").append(valStr).append("\n");
        }
    }

    @Override
    public void visit(JumpInstruction insn) {
        out.append("  br label %").append(bbLabel(insn.getTarget().getIndex())).append("\n");
    }

    @Override
    public void visit(BranchingInstruction insn) {
        String tmp = "%cond" + tmpCounter++;
        BranchingCondition cond = insn.getCondition();

        if (cond == BranchingCondition.NULL || cond == BranchingCondition.NOT_NULL) {
            // Operand is a reference (ptr) — compare against null.
            String icmpOp = (cond == BranchingCondition.NULL) ? "eq" : "ne";
            out.append("  ").append(tmp).append(" = icmp ").append(icmpOp)
               .append(" ptr ").append(resolveVar(insn.getOperand())).append(", null\n");
        } else {
            CompareInfo ci = compareVars.get(insn.getOperand().getIndex());
            if (ci != null) {
                String icmpOp = compareCondToIcmp(ci.op, cond);
                out.append("  ").append(tmp).append(" = icmp ").append(icmpOp)
                   .append(" i32 ").append(ci.a).append(", ").append(ci.b).append("\n");
            } else {
                String icmpOp = conditionToIcmpVsZero(cond);
                out.append("  ").append(tmp).append(" = icmp ").append(icmpOp)
                   .append(" i32 ").append(resolveVar(insn.getOperand())).append(", 0\n");
            }
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
        // REFERENCE_EQUAL/NOT_EQUAL compare object references (ptr); others compare integers.
        boolean isRef = insn.getCondition() == BinaryBranchingCondition.REFERENCE_EQUAL
                     || insn.getCondition() == BinaryBranchingCondition.REFERENCE_NOT_EQUAL;
        String type = isRef ? "ptr" : "i32";
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
        // Check for embedded intrinsics (GPIO, Delay) before regular call emission.
        if (AvrIntrinsics.isIntrinsic(insn)) {
            tmpCounter = AvrIntrinsics.emit(out, insn, varLiteral, tmpCounter,
                    this::resolveVar, staticObjectRef);
            return;
        }

        String calledClass  = insn.getMethod().getClassName();
        String calledMethod = insn.getMethod().getName();

        // ---- Enum <clinit> intercepts ----
        if (inEnumClinit) {
            // EnumClass.<init>(this, name, ordinal) or java.lang.Enum.<init>(this, name, ordinal)
            // → TeaVM may inline the enum constructor, exposing the Enum.<init> call directly.
            // In both cases: store the ordinal into the enum struct, discard the name String.
            boolean isEnumInit = "<init>".equals(calledMethod)
                    && (enumClassName.equals(calledClass)
                        || JAVA_LANG_ENUM.equals(calledClass));
            if (isEnumInit) {
                Variable ordinalVar = insn.getArguments().get(1);  // arg0=name(ptr), arg1=ordinal
                String thisPtr   = resolveVar(insn.getInstance());
                String ordinalVal = resolveVar(ordinalVar);
                String gepVar = "%gep" + tmpCounter++;
                out.append("  ").append(gepVar)
                   .append(" = getelementptr %java_lang_Enum_t, ptr ")
                   .append(thisPtr).append(", i32 0, i32 1\n");
                out.append("  store i32 ").append(ordinalVal)
                   .append(", ptr ").append(gepVar).append("\n");
                return;
            }
            // $values() — array allocation not supported; produce null.
            if ("$values".equals(calledMethod) && enumClassName.equals(calledClass)) {
                if (insn.getReceiver() != null) {
                    out.append("  ").append(v(insn.getReceiver()))
                       .append(" = inttoptr i32 0 to ptr\n");
                }
                return;
            }
        }

        // ---- java.lang.Enum.<init> from within an enum constructor ----
        // Intercept: store the ordinal (arg[1]) to the enum struct; skip the String name.
        if (JAVA_LANG_ENUM.equals(calledClass) && "<init>".equals(calledMethod)
                && enumClassName != null && "<init>".equals(method.getName())) {
            Variable ordinalVar = insn.getArguments().get(1);  // arg0=name, arg1=ordinal
            String thisPtr   = resolveVar(insn.getInstance());
            String ordinalVal = resolveVar(ordinalVar);
            String gepVar = "%gep" + tmpCounter++;
            out.append("  ").append(gepVar)
               .append(" = getelementptr %java_lang_Enum_t, ptr ")
               .append(thisPtr).append(", i32 0, i32 1\n");
            out.append("  store i32 ").append(ordinalVal)
               .append(", ptr ").append(gepVar).append("\n");
            return;
        }

        // ---- Enum.name() / Enum.toString() — requires String heap ----
        if (JAVA_LANG_ENUM.equals(calledClass)
                && ("name".equals(calledMethod) || "toString".equals(calledMethod)
                    || "ordinal".equals(calledMethod))) {
            if ("ordinal".equals(calledMethod) && insn.getReceiver() != null) {
                // ordinal() → load the ordinal field directly
                String thisPtr = resolveVar(insn.getInstance());
                String gepVar = "%gep" + tmpCounter++;
                out.append("  ").append(gepVar)
                   .append(" = getelementptr %java_lang_Enum_t, ptr ")
                   .append(thisPtr).append(", i32 0, i32 1\n");
                out.append("  ").append(v(insn.getReceiver()))
                   .append(" = load i32, ptr ").append(gepVar).append("\n");
            } else if (insn.getReceiver() != null) {
                out.append("  ; ERROR: Enum.").append(calledMethod)
                   .append("() not supported — no String heap on embedded targets\n");
                out.append("  ").append(v(insn.getReceiver()))
                   .append(" = inttoptr i32 0 to ptr\n");
            }
            return;
        }

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
        staticObjectRef.computeIfPresent(insn.getValue().getIndex(),
                (k, v) -> { staticObjectRef.put(insn.getReceiver().getIndex(), v); return v; });
        out.append("  ").append(v(insn.getReceiver()))
           .append(" = add i32 0, ").append(resolveVar(insn.getValue())).append("\n");
    }

    @Override
    public void visit(ConstructInstruction insn) {
        int idx = insn.getReceiver().getIndex();
        // JDK classes have no struct type defined — can't alloca them.
        if (LlvmModuleEmitter.isJavaLangObject(insn.getType())) {
            out.append("  ; unsupported: new ").append(insn.getType())
               .append(" (JDK class, not available on embedded)\n");
            out.append("  ").append(v(insn.getReceiver())).append(" = inttoptr i32 0 to ptr\n");
            return;
        }
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
                staticObjectRef.put(insn.getReceiver().getIndex(), globalName);
                out.append("  ").append(v(insn.getReceiver()))
                   .append(" = getelementptr i8, ptr ").append(globalName)
                   .append(", i32 0\n");
            } else if (LlvmModuleEmitter.isJavaLangObject(className)
                       && fieldType instanceof ValueType.Object) {
                // JDK class object field (e.g. java.util.concurrent.TimeUnit.SECONDS):
                // route through the getelementptr path so intrinsics can identify the
                // constant by global name. A stub global is emitted at module level.
                staticObjectRef.put(insn.getReceiver().getIndex(), globalName);
                module.jdkGlobalStubs.add(globalName);
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
    public void visit(StringConstantInstruction insn) {
        // String heap not supported on embedded targets.
        // In enum <clinit> the string is the enum name — replaced with null ptr (ignored).
        // Elsewhere, emit a diagnostic comment and null.
        if (!inEnumClinit) {
            out.append("  ; ERROR: string constant \"").append(insn.getConstant())
               .append("\" — String heap not supported on embedded targets\n");
        }
        out.append("  ").append(v(insn.getReceiver())).append(" = inttoptr i32 0 to ptr\n");
    }

    @Override
    public void visit(EmptyInstruction insn) {
        // no-op
    }

    @Override
    public void visit(MonitorEnterInstruction insn) {
        out.append("  ; monitorenter (unsupported — no threading)\n");
    }

    @Override
    public void visit(MonitorExitInstruction insn) {
        out.append("  ; monitorexit (unsupported — no threading)\n");
    }

    @Override
    public void visit(SwitchInstruction insn) {
        String cond = resolveVar(insn.getCondition());
        out.append("  switch i32 ").append(cond)
           .append(", label %").append(bbLabel(insn.getDefaultTarget().getIndex())).append(" [\n");
        for (var entry : insn.getEntries()) {
            out.append("    i32 ").append(entry.getCondition())
               .append(", label %").append(bbLabel(entry.getTarget().getIndex())).append("\n");
        }
        out.append("  ]\n");
    }

    @Override
    public void visit(RaiseInstruction insn) {
        // Exceptions not supported on embedded — terminate the block with unreachable.
        out.append("  ; throw (exceptions not supported on embedded targets)\n");
        out.append("  unreachable\n");
    }

    @Override
    public void visit(ConstructArrayInstruction insn) {
        // Array allocation is not supported on embedded (no heap).
        // In enum <clinit> the array is for $VALUES — emit null ptr so putstatic is valid.
        if (!inEnumClinit) {
            out.append("  ; ERROR: array allocation not supported on embedded targets\n");
        }
        out.append("  ").append(v(insn.getReceiver())).append(" = inttoptr i32 0 to ptr\n");
    }

    @Override
    public void visit(PutElementInstruction insn) {
        if (inEnumClinit) return;  // skip $VALUES array stores
        out.append("  ; ERROR: array element store not supported on embedded targets\n");
    }

    @Override
    public void visit(GetElementInstruction insn) {
        if (inEnumClinit) return;
        out.append("  ; array element load — arrays not supported on embedded targets\n");
        if (insn.getReceiver() != null) {
            out.append("  ").append(v(insn.getReceiver())).append(" = add i32 0, 0\n");
        }
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

    private static String llvmNumericType(NumericOperandType t) {
        return switch (t) {
            case INT -> "i32";
            case LONG -> "i64";
            case FLOAT -> "float";
            case DOUBLE -> "double";
        };
    }

    private static String binaryOp(BinaryOperation op, NumericOperandType type) {
        boolean fp = type == NumericOperandType.FLOAT
                  || type == NumericOperandType.DOUBLE;
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
            case COMPARE_GREATER, COMPARE_LESS -> "sub";  // handled above; fallback
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

    private static String numericCast(NumericOperandType from,
                                      NumericOperandType to) {
        boolean fromFP = from == NumericOperandType.FLOAT
                      || from == NumericOperandType.DOUBLE;
        boolean toFP = to == NumericOperandType.FLOAT
                    || to == NumericOperandType.DOUBLE;
        int fromBits = typeBits(from);
        int toBits = typeBits(to);
        if (fromFP && toFP) return fromBits < toBits ? "fpext" : "fptrunc";
        if (fromFP) return "fptosi";
        if (toFP) return "sitofp";
        return fromBits < toBits ? "sext" : "trunc";
    }

    private static int typeBits(NumericOperandType t) {
        return switch (t) {
            case INT -> 32;
            case LONG -> 64;
            case FLOAT -> 32;
            case DOUBLE -> 64;
        };
    }

    private static String integerSubtypeLLVM(IntegerSubtype t) {
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
