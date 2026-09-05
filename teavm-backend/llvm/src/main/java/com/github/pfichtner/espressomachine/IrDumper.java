package com.github.pfichtner.espressomachine;

import static java.io.OutputStream.nullOutputStream;
import static java.nio.file.Files.writeString;
import static java.util.Collections.emptyList;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.teavm.dependency.DependencyAnalyzer;
import org.teavm.dependency.DependencyListener;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.FieldHolder;
import org.teavm.model.Instruction;
import org.teavm.model.ListableClassHolderSource;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.Phi;
import org.teavm.model.Program;
import org.teavm.model.ReferenceCache;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.AbstractInstructionVisitor;
import org.teavm.model.instructions.ArrayLengthInstruction;
import org.teavm.model.instructions.AssignInstruction;
import org.teavm.model.instructions.BinaryBranchingInstruction;
import org.teavm.model.instructions.BinaryInstruction;
import org.teavm.model.instructions.BoundCheckInstruction;
import org.teavm.model.instructions.BranchingInstruction;
import org.teavm.model.instructions.CastInstruction;
import org.teavm.model.instructions.CastIntegerInstruction;
import org.teavm.model.instructions.CastNumberInstruction;
import org.teavm.model.instructions.ClassConstantInstruction;
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
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.IsInstanceInstruction;
import org.teavm.model.instructions.JumpInstruction;
import org.teavm.model.instructions.LongConstantInstruction;
import org.teavm.model.instructions.MonitorEnterInstruction;
import org.teavm.model.instructions.MonitorExitInstruction;
import org.teavm.model.instructions.NegateInstruction;
import org.teavm.model.instructions.NullCheckInstruction;
import org.teavm.model.instructions.NullConstantInstruction;
import org.teavm.model.instructions.PutElementInstruction;
import org.teavm.model.instructions.PutFieldInstruction;
import org.teavm.model.instructions.RaiseInstruction;
import org.teavm.model.instructions.StringConstantInstruction;
import org.teavm.model.instructions.SwitchInstruction;
import org.teavm.model.util.ProgramUtils;
import org.teavm.model.util.VariableCategoryProvider;
import org.teavm.parsing.ClasspathClassHolderSource;
import org.teavm.parsing.ClasspathResourceProvider;
import org.teavm.vm.BuildTarget;
import org.teavm.vm.TeaVMBuilder;
import org.teavm.vm.TeaVMOptimizationLevel;
import org.teavm.vm.TeaVMTarget;
import org.teavm.vm.TeaVMTargetController;
import org.teavm.vm.spi.TeaVMHostExtension;

/**
 * Phase 0/1 tool: invokes TeaVM programmatically, dumps the optimized IR
 * (Program/BasicBlock/Instruction) to stdout, and optionally emits LLVM IR.
 *
 * Usage:
 *   java -jar teavm-ir-dumper.jar <classfile-dir> <ClassName>             # dump IR
 *   java -jar teavm-ir-dumper.jar <classfile-dir> <ClassName> output.ll   # + emit LLVM
 */
public class IrDumper {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: IrDumper <classpath-dir> <EntryClass> [output.ll]");
            System.exit(1);
        }

        String classpathStr = args[0];
        String entryClass   = args[1];
        String llvmOutputPath = args.length >= 3 ? args[2] : null;

        System.out.println("=== EspressoMachine Phase 0/1: TeaVM IR Dump ===");
        System.out.println("Classpath: " + classpathStr);
        System.out.println("Entry class: " + entryClass);
        if (llvmOutputPath != null) System.out.println("LLVM output: " + llvmOutputPath);
        System.out.println();

        compile(classpathStr, entryClass, llvmOutputPath, true);
    }

    /**
     * Core TeaVM compilation: loads classes from colon-separated {@code classpathStr},
     * compiles {@code entryClass}, writes LLVM IR to {@code llvmOutputPath} (may be null),
     * and optionally prints the verbose IR dump to stdout.
     *
     * @return the IrCapturingTarget after compilation (contains the captured programs)
     */
    public static IrCapturingTarget compile(String classpathStr, String entryClass,
                                             String llvmOutputPath, boolean verbose)
            throws Exception {
        String[] cpEntries = classpathStr.split(":");
        IrCapturingTarget target = new IrCapturingTarget(entryClass, llvmOutputPath, verbose);

        URL[] urls = new URL[cpEntries.length];
        for (int i = 0; i < cpEntries.length; i++) {
            urls[i] = new File(cpEntries[i]).toURI().toURL();
        }
        ClassLoader urlCL = new URLClassLoader(urls, IrDumper.class.getClassLoader());

        // TeaVMBuilder sets classSource from classLoader in its constructor, so we must
        // explicitly rebuild classSource after creating the builder with our URL classloader.
        var refCache = new ReferenceCache();
        var classSource = new ClasspathClassHolderSource(new ClasspathResourceProvider(urlCL), refCache, urlCL);

        TeaVMBuilder builder = new TeaVMBuilder(target);
        builder.setClassLoader(urlCL)
               .setClassSource(classSource)
               .setReferenceCache(refCache);

        var vm = builder.build();
        vm.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);
        vm.setEntryPoint(entryClass);
        vm.build(new NullBuildTarget(), "out");

        if (!vm.getProblemProvider().getSevereProblems().isEmpty()) {
            System.err.println("TeaVM reported severe problems:");
            vm.getProblemProvider().getSevereProblems().forEach(p ->
                    System.err.println("  " + p.getText()));
            if (verbose) System.exit(2);
            throw new RuntimeException("TeaVM compilation failed");
        }
        return target;
    }

    // -----------------------------------------------------------------------
    // Minimal TeaVMTarget that captures IR and dumps it
    // -----------------------------------------------------------------------

    static class IrCapturingTarget implements TeaVMTarget {

        // Class whose ALL methods should be force-linked (typically the entry class)
        private final String rootClassName;

        // If non-null, write LLVM IR to this file path.
        private final String llvmOutputPath;

        // Collect programs seen in beforeInlining — includes methods that may be
        // inlined away before emit() receives the final class set.
        private final LinkedHashMap<String, Program> preInliningPrograms = new LinkedHashMap<>();

        // Also collect from afterOptimizations (covers the lazy pipeline and final programs)
        private final LinkedHashMap<String, Program> postOptPrograms = new LinkedHashMap<>();

        // Map method reference string → MethodReader for afterOptimizations-captured methods.
        private final LinkedHashMap<String, org.teavm.model.MethodReader> postOptMethods = new LinkedHashMap<>();

        // Saved reference to the ListableClassHolderSource from the last emit() call.
        private ListableClassHolderSource lastClasses;

        // Additional methods to force-link (class, descriptor string)
        private final List<String[]> forceLinkMethods = new ArrayList<>();

        // When quiet=true, emit() skips the text IR dump and only writes the .ll file.
        private final boolean quiet;

        IrCapturingTarget(String rootClassName, String llvmOutputPath) {
            this(rootClassName, llvmOutputPath, true);
        }

        IrCapturingTarget(String rootClassName, String llvmOutputPath, boolean verbose) {
            this.rootClassName = rootClassName;
            this.llvmOutputPath = llvmOutputPath;
            this.quiet = !verbose;
        }

        void forceLink(String className, String methodName, String descriptor) {
            forceLinkMethods.add(new String[]{className, methodName, descriptor});
        }

        @Override
        public List<ClassHolderTransformer> getTransformers() {
            // Replace JDK/TeaVM method bodies with trivial return stubs so TeaVM
            // never encounters invokedynamic instructions (e.g. java.lang.Enum
            // uses a lambda in enumConstantDirectory which TeaVM 0.12 can't handle).
            // Using an actual (empty) program instead of null lets TeaVM inline and
            // eliminate trivial calls like Object.<init>() so they don't appear as
            // undefined symbols in the LLVM output.
            //
            // For java.util.Random: all methods are stubbed with return-0 (including
            // next(int)), which means TeaVM constant-folds all Random calls to literal
            // 0 before the emitter ever sees them.
            //
            // To intercept Random calls in user code, a SECOND transformer walks user
            // classes and replaces java.util.Random.nextInt() / nextInt(int) /
            // nextLong() invoke instructions with calls to RuntimeRandomBridge methods.
            // The bridge methods read from a volatile field so the optimizer can't fold
            // them; the emitter then replaces the bridge call with the AVR intrinsic.
            return List.of(
                // Transformer 1: JDK method stubs
                (cls, ctx) -> {
                    if (LlvmModuleEmitter.isJavaLangObject(cls.getName())) {
                        for (var method : cls.getMethods()) {
                            if (method.getProgram() == null) continue;
                            method.setProgram(makeReturnStub(method));
                        }
                    }
                },
                // Transformer 2: Replace java.util.Random invokes in user code
                (cls, ctx) -> {
                    if (LlvmModuleEmitter.isJavaLangObject(cls.getName())) return;
                    for (var method : cls.getMethods()) {
                        if (method.getProgram() == null) continue;
                        replaceRandomInvokes(method.getProgram());
                    }
                },
                // Transformer 3: Replace java.lang.Math invokes in user code
                (cls, ctx) -> {
                    if (LlvmModuleEmitter.isJavaLangObject(cls.getName())) return;
                    for (var method : cls.getMethods()) {
                        if (method.getProgram() == null) continue;
                        replaceMathInvokes(method.getProgram());
                    }
                }
            );
        }

        /** Bridge class name for intercepted java.util.Random methods. */
        private static final String BRIDGE_CLASS =
                "com.github.pfichtner.espressomachine.emit.RuntimeRandomBridge";

        /**
         * Walk a program and replace {@code java.util.Random.nextInt()},
         * {@code nextInt(int)}, and {@code nextLong()} invoke instructions
         * with calls to {@link RuntimeRandomBridge} methods.
         * The emitter intercepts those bridge calls and emits AVR intrinsics.
         */
        private static void replaceRandomInvokes(Program prog) {
            for (int bi = 0; bi < prog.basicBlockCount(); bi++) {
                BasicBlock bb = prog.basicBlockAt(bi);
                if (bb == null) continue;
                for (var it = bb.iterator(); it.hasNext(); ) {
                    Instruction insn = it.next();
                    if (!(insn instanceof InvokeInstruction inv)) continue;
                    if (!"java.util.Random".equals(inv.getMethod().getClassName())) continue;
                    String name = inv.getMethod().getName();
                    switch (name) {
                        case "nextInt" -> {
                            if (inv.getArguments().isEmpty()) {
                                inv.setMethod(new MethodReference(BRIDGE_CLASS,
                                        org.teavm.model.MethodDescriptor.parse("randomNextInt()I")));
                            } else {
                                inv.setMethod(new MethodReference(BRIDGE_CLASS,
                                        org.teavm.model.MethodDescriptor.parse("randomNextIntBound(I)I")));
                            }
                        }
                        case "nextLong" -> {
                            inv.setMethod(new MethodReference(BRIDGE_CLASS,
                                    org.teavm.model.MethodDescriptor.parse("randomNextLong()J")));
                        }
                        case "next" -> {
                            // next(int) is internal — already handled by stubs, skip
                        }
                    }
                }
            }
        }

        /** Bridge class name for intercepted java.lang.Math methods. */
        private static final String MATH_BRIDGE_CLASS =
                "com.github.pfichtner.espressomachine.emit.RuntimeMathBridge";

        /**
         * Walk a program and replace {@code java.lang.Math.min(int,int)},
         * {@code max(int,int)}, {@code abs(int)}, {@code pow(double,double)},
         * and {@code sqrt(double)} invoke instructions with calls to
         * {@link RuntimeMathBridge} methods.
         * The emitter intercepts those bridge calls and emits LLVM intrinsics.
         */
        private static void replaceMathInvokes(Program prog) {
            for (int bi = 0; bi < prog.basicBlockCount(); bi++) {
                BasicBlock bb = prog.basicBlockAt(bi);
                if (bb == null) continue;
                for (var it = bb.iterator(); it.hasNext(); ) {
                    Instruction insn = it.next();
                    if (!(insn instanceof InvokeInstruction inv)) continue;
                    if (!"java.lang.Math".equals(inv.getMethod().getClassName())) continue;
                    String name = inv.getMethod().getName();
                    switch (name) {
                        case "min" -> {
                            if (ValueType.INTEGER.equals(inv.getMethod().getDescriptor().parameterType(0))) {
                                inv.setMethod(new MethodReference(MATH_BRIDGE_CLASS,
                                        MethodDescriptor.parse("mathMinInt(II)I")));
                            }
                        }
                        case "max" -> {
                            if (ValueType.INTEGER.equals(inv.getMethod().getDescriptor().parameterType(0))) {
                                inv.setMethod(new MethodReference(MATH_BRIDGE_CLASS,
                                        MethodDescriptor.parse("mathMaxInt(II)I")));
                            }
                        }
                        case "abs" -> {
                            if (ValueType.INTEGER.equals(inv.getMethod().getDescriptor().parameterType(0))) {
                                inv.setMethod(new MethodReference(MATH_BRIDGE_CLASS,
                                        MethodDescriptor.parse("mathAbsInt(I)I")));
                            }
                        }
                        case "pow" -> {
                            inv.setMethod(new MethodReference(MATH_BRIDGE_CLASS,
                                    MethodDescriptor.parse("mathPow(DD)D")));
                        }
                        case "sqrt" -> {
                            inv.setMethod(new MethodReference(MATH_BRIDGE_CLASS,
                                    MethodDescriptor.parse("mathSqrt(D)D")));
                        }
                    }
                }
            }
        }

        private static Program makeReturnStub(MethodHolder method) {
            var prog = new Program();
            var bb = prog.createBasicBlock();
            // Create a variable for `this` (instance methods) plus one per parameter,
            // so TeaVM's optimizer doesn't crash on an empty variable table.
            boolean isStatic = method.hasModifier(org.teavm.model.ElementModifier.STATIC);
            int paramSlots = Math.max(1, (isStatic ? 0 : 1) + method.parameterCount());
            for (int i = 0; i < paramSlots; i++) prog.createVariable();
            var returnType = method.getResultType();
            var exit = new ExitInstruction();
            if (returnType != ValueType.VOID) {
                var returnVar = prog.createVariable();
                if (returnType instanceof ValueType.Primitive prim) {
                    switch (prim.getKind()) {
                        case LONG -> { var i = new LongConstantInstruction(); i.setReceiver(returnVar); bb.add(i); }
                        case FLOAT -> { var i = new FloatConstantInstruction(); i.setReceiver(returnVar); bb.add(i); }
                        case DOUBLE -> { var i = new DoubleConstantInstruction(); i.setReceiver(returnVar); bb.add(i); }
                        default -> { var i = new IntegerConstantInstruction(); i.setReceiver(returnVar); bb.add(i); }
                    }
                } else {
                    var i = new NullConstantInstruction(); i.setReceiver(returnVar); bb.add(i);
                }
                exit.setValueToReturn(returnVar);
            }
            bb.add(exit);
            return prog;
        }

        @Override
        public List<DependencyListener> getDependencyListeners() {
            return emptyList();
        }

        @Override
        public void setController(TeaVMTargetController controller) {
            // not needed for dump
        }

        @Override
        public List<TeaVMHostExtension> getHostExtensions() {
            return emptyList();
        }

        @Override
        public VariableCategoryProvider variableCategoryProvider() {
            return null;
        }

        @Override
        public void contributeDependencies(DependencyAnalyzer dependencyAnalyzer) {
            // Force all methods of the root class into the dependency graph. Without this,
            // TeaVM's optimizer may constant-fold or eliminate calls before beforeInlining fires.
            if (rootClassName != null) {
                var cls = dependencyAnalyzer.getClassSource().get(rootClassName);
                if (cls != null) {
                    dependencyAnalyzer.linkClass(rootClassName).initClass(null);
                    for (var method : cls.getMethods()) {
                        dependencyAnalyzer.linkMethod(method.getReference()).use();
                    }
                }
            }
            // Additional explicit force-links
            for (String[] spec : forceLinkMethods) {
                var ref = new MethodReference(spec[0],
                        MethodDescriptor.parse(spec[1] + spec[2]));
                dependencyAnalyzer.linkMethod(ref).use();
            }
        }

        // Called for every reachable method BEFORE inlining — this is where we see
        // all methods, including those TeaVM will inline and remove later.
        @Override
        public void beforeInlining(Program program, MethodReader method) {
            String key = method.getReference().toString();
            // Copy the program snapshot (TeaVM mutates it during optimization)
            preInliningPrograms.put(key, ProgramUtils.copy(program));
        }

        @Override
        public void beforeOptimizations(Program program, MethodReader method) {
            // nothing
        }

        @Override
        public void afterOptimizations(Program program, MethodReader method) {
            String key = method.getReference().toString();
            postOptPrograms.put(key, ProgramUtils.copy(program));
            postOptMethods.put(key, method);
        }

        @Override
        public void emit(ListableClassHolderSource classes, BuildTarget buildTarget, String outputName)
                throws IOException {
            lastClasses = classes;
            if (quiet) {
                // Quiet mode: skip text dump, only write the .ll file below.
                if (llvmOutputPath != null) emitLlvm();
                return;
            }
            // ---- Phase A: pre-inlining IR (all reachable methods) ----
            if (!preInliningPrograms.isEmpty()) {
                System.out.println("=== Pre-inlining IR (all reachable methods) ===");
                System.out.println("    (captured before TeaVM inlines small methods)");
                preInliningPrograms.forEach((methodRef, prog) -> {
                    System.out.println();
                    System.out.println("  METHOD " + methodRef);
                    dumpProgram(prog);
                });
            }

            // ---- Phase B: after-optimization IR (from afterOptimizations hook) ----
            if (!postOptPrograms.isEmpty()) {
                System.out.println();
                System.out.println("=== After-optimization IR (per-method optimizer output) ===");
                postOptPrograms.forEach((methodRef, prog) -> {
                    System.out.println();
                    System.out.println("  METHOD " + methodRef);
                    dumpProgram(prog);
                });
            }

            // ---- Phase C: final class set ----
            System.out.println();
            System.out.println("=== Final class set ===");
            List<String> classNames = new ArrayList<String>(classes.getClassNames());
            Collections.sort(classNames);
            for (String className : classNames) {
                ClassHolder cls = classes.get(className);
                if (cls == null) continue;
                dumpClass(cls);
            }

            // ---- Phase D: emit LLVM IR if requested ----
            if (llvmOutputPath != null) {
                emitLlvm();
            }
        }

        private void emitLlvm() throws IOException {
            System.out.println();
            System.out.println("=== Emitting LLVM IR → " + llvmOutputPath + " ===");
            var moduleEmitter = new LlvmModuleEmitter(lastClasses, postOptPrograms, postOptMethods,
                    preInliningPrograms, rootClassName);
            String llvm = moduleEmitter.emit();
            writeString(Path.of(llvmOutputPath), llvm);
            System.out.println("  Wrote " + llvm.length() + " chars to " + llvmOutputPath);
        }

        @Override
        public String[] getPlatformTags() {
            return new String[]{"com.github.pfichtner.espressomachine"};
        }

        @Override
        public boolean isAsyncSupported() {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // IR printer
    // -----------------------------------------------------------------------

    static void dumpClass(ClassHolder cls) {
        StringBuilder header = new StringBuilder();
        header.append("class ").append(cls.getName());
        if (cls.getParent() != null) {
            header.append(" extends ").append(cls.getParent());
        }
        System.out.println();
        System.out.println("CLASS " + header);

        for (FieldHolder field : cls.getFields()) {
            System.out.printf("  field %-30s %s%n", field.getName(), typeString(field.getType()));
        }

        for (MethodHolder method : cls.getMethods()) {
            System.out.println();
            System.out.printf("  METHOD %s.%s %s%n",
                    cls.getName(), method.getName(), method.getDescriptor());
            Program program = method.getProgram();
            if (program == null) {
                System.out.println("    (no program — native, abstract, or inlined away)");
            } else {
                dumpProgram(program);
            }
        }
    }

    static void dumpProgram(Program program) {
        System.out.printf("    variables: %d, basic blocks: %d%n",
                program.variableCount(), program.basicBlockCount());

        for (int bi = 0; bi < program.basicBlockCount(); bi++) {
            BasicBlock block = program.basicBlockAt(bi);
            if (block == null) continue;
            dumpBlock(block, bi);
        }
    }

    static void dumpBlock(BasicBlock block, int index) {
        System.out.printf("    BB%d:%n", index);

        // PHI nodes
        for (Phi phi : block.getPhis()) {
            StringBuilder sb = new StringBuilder();
            sb.append("      ").append(varName(phi.getReceiver())).append(" = phi [");
            for (int i = 0; i < phi.getIncomings().size(); i++) {
                if (i > 0) sb.append(", ");
                var inc = phi.getIncomings().get(i);
                sb.append(varName(inc.getValue())).append(" from BB").append(inc.getSource().getIndex());
            }
            sb.append("]");
            System.out.println(sb);
        }

        // Instructions
        for (Instruction insn : block) {
            System.out.print("      ");
            insn.acceptVisitor(new InstructionPrinter());
            System.out.println();
        }
    }

    static String varName(Variable v) {
        return v == null ? "null" : "%" + v.getIndex();
    }

    static String typeString(ValueType t) {
        if (t == null) return "void";
        if (t instanceof ValueType.Primitive p) {
            return switch (p.getKind()) {
                case BOOLEAN -> "boolean";
                case BYTE -> "byte";
                case SHORT -> "short";
                case CHARACTER -> "char";
                case INTEGER -> "int";
                case LONG -> "long";
                case FLOAT -> "float";
                case DOUBLE -> "double";
            };
        }
        if (t instanceof ValueType.Object o) return o.getClassName();
        if (t instanceof ValueType.Array a) return typeString(a.getItemType()) + "[]";
        if (t instanceof ValueType.Void) return "void";
        return t.toString();
    }

    // -----------------------------------------------------------------------
    // Instruction visitor: prints a human-readable line per instruction
    // -----------------------------------------------------------------------

    static class InstructionPrinter extends AbstractInstructionVisitor {

        @Override
        public void visit(IntegerConstantInstruction insn) {
            System.out.printf("%s = int_const %d", varName(insn.getReceiver()), insn.getConstant());
        }

        @Override
        public void visit(LongConstantInstruction insn) {
            System.out.printf("%s = long_const %dL", varName(insn.getReceiver()), insn.getConstant());
        }

        @Override
        public void visit(FloatConstantInstruction insn) {
            System.out.printf("%s = float_const %ff", varName(insn.getReceiver()), insn.getConstant());
        }

        @Override
        public void visit(DoubleConstantInstruction insn) {
            System.out.printf("%s = double_const %f", varName(insn.getReceiver()), insn.getConstant());
        }

        @Override
        public void visit(NullConstantInstruction insn) {
            System.out.printf("%s = null", varName(insn.getReceiver()));
        }

        @Override
        public void visit(StringConstantInstruction insn) {
            System.out.printf("%s = string_const \"%s\"", varName(insn.getReceiver()), insn.getConstant());
        }

        @Override
        public void visit(ClassConstantInstruction insn) {
            System.out.printf("%s = class_const %s", varName(insn.getReceiver()), typeString(insn.getConstant()));
        }

        @Override
        public void visit(BinaryInstruction insn) {
            System.out.printf("%s = %s_%s %s, %s",
                    varName(insn.getReceiver()),
                    insn.getOperandType().name().toLowerCase(),
                    insn.getOperation().name().toLowerCase(),
                    varName(insn.getFirstOperand()),
                    varName(insn.getSecondOperand()));
        }

        @Override
        public void visit(NegateInstruction insn) {
            System.out.printf("%s = %s_neg %s",
                    varName(insn.getReceiver()),
                    insn.getOperandType().name().toLowerCase(),
                    varName(insn.getOperand()));
        }

        @Override
        public void visit(AssignInstruction insn) {
            System.out.printf("%s = assign %s", varName(insn.getReceiver()), varName(insn.getAssignee()));
        }

        @Override
        public void visit(ExitInstruction insn) {
            if (insn.getValueToReturn() != null) {
                System.out.printf("return %s", varName(insn.getValueToReturn()));
            } else {
                System.out.print("return void");
            }
        }

        @Override
        public void visit(JumpInstruction insn) {
            System.out.printf("jump BB%d", insn.getTarget().getIndex());
        }

        @Override
        public void visit(BranchingInstruction insn) {
            System.out.printf("branch %s if %s -> BB%d else BB%d",
                    varName(insn.getOperand()),
                    insn.getCondition().name(),
                    insn.getConsequent().getIndex(),
                    insn.getAlternative().getIndex());
        }

        @Override
        public void visit(BinaryBranchingInstruction insn) {
            System.out.printf("branch2 %s %s %s if %s -> BB%d else BB%d",
                    varName(insn.getFirstOperand()),
                    insn.getCondition().name(),
                    varName(insn.getSecondOperand()),
                    insn.getCondition().name(),
                    insn.getConsequent().getIndex(),
                    insn.getAlternative().getIndex());
        }

        @Override
        public void visit(InvokeInstruction insn) {
            StringBuilder sb = new StringBuilder();
            if (insn.getReceiver() != null) {
                sb.append(varName(insn.getReceiver())).append(" = ");
            }
            sb.append("invoke_").append(insn.getType().name().toLowerCase());
            sb.append(" ").append(insn.getMethod().getClassName())
              .append(".").append(insn.getMethod().getName());
            sb.append("(");
            if (insn.getInstance() != null) {
                sb.append(varName(insn.getInstance()));
                if (!insn.getArguments().isEmpty()) sb.append(", ");
            }
            for (int i = 0; i < insn.getArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(varName(insn.getArguments().get(i)));
            }
            sb.append(")");
            System.out.print(sb);
        }

        @Override
        public void visit(ConstructInstruction insn) {
            System.out.printf("%s = new %s", varName(insn.getReceiver()), insn.getType());
        }

        @Override
        public void visit(GetFieldInstruction insn) {
            if (insn.getInstance() != null) {
                System.out.printf("%s = getfield %s.%s from %s",
                        varName(insn.getReceiver()),
                        insn.getField().getClassName(),
                        insn.getField().getFieldName(),
                        varName(insn.getInstance()));
            } else {
                System.out.printf("%s = getstatic %s.%s",
                        varName(insn.getReceiver()),
                        insn.getField().getClassName(),
                        insn.getField().getFieldName());
            }
        }

        @Override
        public void visit(PutFieldInstruction insn) {
            if (insn.getInstance() != null) {
                System.out.printf("putfield %s.%s in %s = %s",
                        insn.getField().getClassName(),
                        insn.getField().getFieldName(),
                        varName(insn.getInstance()),
                        varName(insn.getValue()));
            } else {
                System.out.printf("putstatic %s.%s = %s",
                        insn.getField().getClassName(),
                        insn.getField().getFieldName(),
                        varName(insn.getValue()));
            }
        }

        @Override
        public void visit(InitClassInstruction insn) {
            System.out.printf("init_class %s", insn.getClassName());
        }

        @Override
        public void visit(NullCheckInstruction insn) {
            System.out.printf("%s = null_check %s",
                    varName(insn.getReceiver()), varName(insn.getValue()));
        }

        @Override
        public void visit(CastInstruction insn) {
            System.out.printf("%s = cast %s to %s",
                    varName(insn.getReceiver()),
                    varName(insn.getValue()),
                    typeString(insn.getTargetType()));
        }

        @Override
        public void visit(CastNumberInstruction insn) {
            System.out.printf("%s = cast_num %s %s -> %s",
                    varName(insn.getReceiver()),
                    insn.getSourceType().name().toLowerCase(),
                    varName(insn.getValue()),
                    insn.getTargetType().name().toLowerCase());
        }

        @Override
        public void visit(CastIntegerInstruction insn) {
            System.out.printf("%s = cast_int %s %s -> %s",
                    varName(insn.getReceiver()),
                    insn.getDirection().name().toLowerCase(),
                    varName(insn.getValue()),
                    insn.getTargetType().name().toLowerCase());
        }

        @Override
        public void visit(SwitchInstruction insn) {
            System.out.printf("switch %s [", varName(insn.getCondition()));
            for (var entry : insn.getEntries()) {
                System.out.printf("%d->BB%d ", entry.getCondition(), entry.getTarget().getIndex());
            }
            System.out.printf("] default BB%d", insn.getDefaultTarget().getIndex());
        }

        @Override
        public void visit(RaiseInstruction insn) {
            System.out.printf("throw %s", varName(insn.getException()));
        }

        @Override
        public void visit(IsInstanceInstruction insn) {
            System.out.printf("%s = instanceof %s : %s",
                    varName(insn.getReceiver()), varName(insn.getValue()), typeString(insn.getType()));
        }

        @Override
        public void visit(ArrayLengthInstruction insn) {
            System.out.printf("%s = arraylength %s", varName(insn.getReceiver()), varName(insn.getArray()));
        }

        @Override
        public void visit(GetElementInstruction insn) {
            System.out.printf("%s = getelem %s[%s]",
                    varName(insn.getReceiver()), varName(insn.getArray()), varName(insn.getIndex()));
        }

        @Override
        public void visit(PutElementInstruction insn) {
            System.out.printf("putelem %s[%s] = %s",
                    varName(insn.getArray()), varName(insn.getIndex()), varName(insn.getValue()));
        }

        @Override
        public void visit(ConstructArrayInstruction insn) {
            System.out.printf("%s = new %s[%s]",
                    varName(insn.getReceiver()), typeString(insn.getItemType()), varName(insn.getSize()));
        }

        @Override
        public void visit(EmptyInstruction insn) {
            System.out.print("nop");
        }

        @Override
        public void visit(MonitorEnterInstruction insn) {
            System.out.printf("monitorenter %s", varName(insn.getObjectRef()));
        }

        @Override
        public void visit(MonitorExitInstruction insn) {
            System.out.printf("monitorexit %s", varName(insn.getObjectRef()));
        }

        @Override
        public void visit(BoundCheckInstruction insn) {
            System.out.printf("%s = boundcheck %s in %s",
                    varName(insn.getReceiver()), varName(insn.getIndex()),
                    insn.getArray() != null ? varName(insn.getArray()) : "?");
        }
    }

    // -----------------------------------------------------------------------
    // No-op BuildTarget (we don't write files)
    // -----------------------------------------------------------------------

    static class NullBuildTarget implements BuildTarget {
        @Override
        public OutputStream createResource(String fileName) {
            return nullOutputStream();
        }
    }
}
