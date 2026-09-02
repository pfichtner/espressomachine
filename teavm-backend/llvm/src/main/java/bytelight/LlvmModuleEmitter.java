package bytelight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.FieldHolder;
import org.teavm.model.FieldReference;
import org.teavm.model.Instruction;
import org.teavm.model.ListableClassHolderSource;
import org.teavm.model.MethodReader;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Emits a complete LLVM IR module from the TeaVM ListableClassHolderSource.
 *
 * Phase 2 additions over Phase 1:
 *   - LLVM struct type declarations for each user class
 *   - ConstructInstruction → alloca (stack allocation for non-escaping objects)
 *   - GetFieldInstruction → getelementptr + load
 *   - PutFieldInstruction → getelementptr + store
 *   - Static field globals
 */
class LlvmModuleEmitter {

    private static final String JAVA_LANG_ENUM = Enum.class.getName();

	final ListableClassHolderSource classes;
    // postOptPrograms/Methods come from afterOptimizations callbacks
    private final LinkedHashMap<String, Program> postOptPrograms;
    private final LinkedHashMap<String, MethodReader> postOptMethods;

    // Per-class field layout: className → (fieldName → index-in-struct)
    final Map<String, Map<String, Integer>> fieldIndices = new HashMap<>();
    // Per-class field types: className → list of ValueType in struct order
    final Map<String, List<ValueType>> fieldTypes = new HashMap<>();

    LlvmModuleEmitter(ListableClassHolderSource classes,
                      LinkedHashMap<String, Program> postOptPrograms,
                      LinkedHashMap<String, MethodReader> postOptMethods) {
        this.classes = classes;
        this.postOptPrograms = postOptPrograms;
        this.postOptMethods = postOptMethods;
        buildFieldMaps();
    }

    // ------------------------------------------------------------------
    // Pre-pass: build field index maps for all classes
    // ------------------------------------------------------------------

    private void buildFieldMaps() {
        // Detect enum classes first so field indexing is correct.
        for (String name : classes.getClassNames()) {
            ClassHolder cls = classes.get(name);
            if (cls != null && JAVA_LANG_ENUM.equals(cls.getParent())) {
                enumClasses.add(name);
            }
        }

        for (String name : classes.getClassNames()) {
            ClassHolder cls = classes.get(name);
            if (cls == null) continue;
            Map<String, Integer> indices = new LinkedHashMap<>();
            List<ValueType> types = new ArrayList<>();
            // Enum subclasses reserve slots 0 (name: ptr) and 1 (ordinal: i32)
            // for the inherited java.lang.Enum fields so GEP indices stay correct.
            int i = enumClasses.contains(name) ? 2 : 0;
            for (FieldHolder field : cls.getFields()) {
                if (field.hasModifier(org.teavm.model.ElementModifier.STATIC)) continue;
                indices.put(field.getName(), i++);
                types.add(field.getType());
            }
            fieldIndices.put(name, indices);
            fieldTypes.put(name, types);
        }
    }

    // ------------------------------------------------------------------
    // Main emit
    // ------------------------------------------------------------------

    String emit() {
        StringBuilder out = new StringBuilder();
        out.append("; ByteLight Phase 2 LLVM IR\n");
        out.append("; Generated from TeaVM 0.12.0 optimized IR\n\n");

        // 1. Struct type declarations
        emitStructTypes(out);

        // 2. Static field globals
        emitStaticFields(out);

        // 2b. AVR intrinsic runtime declarations
        out.append(AvrIntrinsics.runtimeDeclarations()).append("\n");

        // 3. External declarations (java.lang.Object methods etc.)
        Set<String> defined = collectDefinedNames();
        Set<String> called = collectCalledNames();

        // 4. Method definitions
        StringBuilder methods = new StringBuilder();
        for (var entry : postOptPrograms.entrySet()) {
            String key = entry.getKey();
            Program prog = entry.getValue();
            MethodReader method = postOptMethods.get(key);
            if (method == null || prog == null) continue;
            String mClass = method.getReference().getClassName();
            if (isJavaLangObject(mClass) || isIntrinsicClass(mClass)) continue;
            // $values() synthesized by javac requires array allocation — skip.
            if (enumClasses.contains(mClass) && "$values".equals(method.getName())) continue;

            StringBuilder methodOut = new StringBuilder();
            try {
                new LlvmMethodEmitter(methodOut, prog, method, this).emit();
            } catch (Exception e) {
                methodOut.append("; FAILED: ").append(key).append(" — ").append(e.getMessage()).append("\n\n");
            }
            methods.append(methodOut);
        }

        // Emit external declarations for callee methods not defined here.
        for (String callee : called) {
            if (!defined.contains(callee)) {
                out.append("declare void @").append(callee).append("(...)\n");
            }
        }
        if (!called.isEmpty()) out.append("\n");

        out.append(methods);
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Struct type declarations
    // ------------------------------------------------------------------

    private void emitStructTypes(StringBuilder out) {
        // Emit java.lang.Enum base struct if any enum classes exist.
        if (!enumClasses.isEmpty()) {
            // Field 0: name (String ptr, kept null on embedded — maintains GEP indices)
            // Field 1: ordinal (i32)
            out.append("%java_lang_Enum_t = type { ptr, i32 }\n");
        }

        boolean any = !enumClasses.isEmpty();
        for (String name : sortedClassNames()) {
            ClassHolder cls = classes.get(name);
            if (cls == null || isJavaLangObject(name) || isIntrinsicClass(name)) continue;

            List<String> llvmFields = new ArrayList<>();
            if (enumClasses.contains(name)) {
                // Enum subclass: start with inherited Enum fields, then own fields.
                llvmFields.add("ptr");  // name (null on embedded)
                llvmFields.add("i32");  // ordinal
                for (FieldHolder f : cls.getFields()) {
                    if (!f.hasModifier(org.teavm.model.ElementModifier.STATIC)) {
                        llvmFields.add(LlvmMethodEmitter.llvmType(f.getType()));
                    }
                }
            } else {
                List<ValueType> fts = fieldTypes.get(name);
                if (fts == null || fts.isEmpty()) continue;
                for (ValueType ft : fts) llvmFields.add(LlvmMethodEmitter.llvmType(ft));
            }

            out.append("%").append(llvmStructName(name)).append(" = type { ")
               .append(String.join(", ", llvmFields)).append(" }\n");
            any = true;
        }
        if (any) out.append("\n");
    }

    // ------------------------------------------------------------------
    // Static field globals
    // ------------------------------------------------------------------

    // Static fields whose type is a user object (set after scanning <clinit> methods).
    // Maps "ClassName.fieldName" → Java class name of the object type.
    final Set<String> staticObjectFields = new LinkedHashSet<>();

    // Classes that extend java.lang.Enum — treated as embedded enums (ordinal-only).
    final Set<String> enumClasses = new LinkedHashSet<>();

    private void emitStaticFields(StringBuilder out) {
        // Pre-scan all <clinit> methods to detect static object allocations.
        detectStaticObjectAllocations();

        boolean any = false;
        for (String name : sortedClassNames()) {
            ClassHolder cls = classes.get(name);
            if (cls == null || isJavaLangObject(name) || isIntrinsicClass(name)) continue;
            for (FieldHolder field : cls.getFields()) {
                if (!field.hasModifier(org.teavm.model.ElementModifier.STATIC)) continue;
                // $VALUES array requires heap — leave as null global and skip in <clinit>.
                if (enumClasses.contains(name) && "$VALUES".equals(field.getName())) {
                    String globalName = "@" + LlvmMethodEmitter.mangle(name, field.getName());
                    out.append(globalName).append(" = global ptr null\n");
                    any = true;
                    continue;
                }
                String key = name + "." + field.getName();
                String globalName = "@" + LlvmMethodEmitter.mangle(name, field.getName());
                String llvmType = LlvmMethodEmitter.llvmType(field.getType());
                if (staticObjectFields.contains(key) && field.getType() instanceof ValueType.Object obj) {
                    // Static object field: emit the struct directly as a global instead of a ptr.
                    String structType = "%" + llvmStructName(obj.getClassName());
                    out.append(globalName).append(" = global ").append(structType)
                       .append(" zeroinitializer\n");
                } else {
                    // ptr types must initialize to null, not 0 (integer).
                    String zeroVal = llvmType.equals("ptr") ? "null" : "0";
                    out.append(globalName).append(" = global ").append(llvmType).append(" ").append(zeroVal).append("\n");
                }
                any = true;
            }
        }
        if (any) out.append("\n");
    }

    private void detectStaticObjectAllocations() {
        for (var entry : postOptPrograms.entrySet()) {
            MethodReader method = postOptMethods.get(entry.getKey());
            if (method == null || !method.getName().equals("<clinit>")) continue;
            Program prog = entry.getValue();
            if (prog == null) continue;

            EscapeAnalyzer ea = EscapeAnalyzer.analyze(prog, method);
            for (int bi = 0; bi < prog.basicBlockCount(); bi++) {
                BasicBlock bb = prog.basicBlockAt(bi);
                if (bb == null) continue;
                for (Instruction insn : bb) {
                    if (insn instanceof ConstructInstruction ci) {
                        EscapeAnalyzer.Fate f = ea.fateOf(ci.getReceiver().getIndex());
                        if (f == EscapeAnalyzer.Fate.STATIC) {
                            FieldReference fref = ea.staticFieldOf(ci.getReceiver().getIndex());
                            if (fref != null) {
                                staticObjectFields.add(fref.getClassName() + "." + fref.getFieldName());
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static String llvmStructName(String className) {
        return className.replace('.', '_').replace('/', '_') + "_t";
    }

    static boolean isJavaLangObject(String className) {
        return className.startsWith("java.")
            || className.startsWith("javax.")
            || className.startsWith("jdk.")
            || className.startsWith("sun.")
            || className.startsWith("com.sun.")
            || className.startsWith("org.teavm.");
    }

    // Intrinsic API classes have no LLVM definitions — they are handled by AvrIntrinsics.
    static boolean isIntrinsicClass(String className) {
        return AvrIntrinsics.GPIO_CLASS.equals(className)
            || AvrIntrinsics.DELAY_CLASS.equals(className);
    }

    private List<String> sortedClassNames() {
        var names = new ArrayList<>(classes.getClassNames());
        java.util.Collections.sort(names);
        return names;
    }

    private Set<String> collectDefinedNames() {
        var defined = new LinkedHashSet<String>();
        for (var entry : postOptMethods.entrySet()) {
            MethodReader m = entry.getValue();
            if (m != null && !isJavaLangObject(m.getReference().getClassName())) {
                defined.add(LlvmMethodEmitter.mangle(m));
            }
        }
        return defined;
    }

    private Set<String> collectCalledNames() {
        var called = new LinkedHashSet<String>();
        for (var entry : postOptPrograms.entrySet()) {
            Program prog = entry.getValue();
            if (prog == null) continue;
            for (int bi = 0; bi < prog.basicBlockCount(); bi++) {
                BasicBlock bb = prog.basicBlockAt(bi);
                if (bb == null) continue;
                for (Instruction insn : bb) {
                    if (insn instanceof InvokeInstruction inv) {
                        String invClass = inv.getMethod().getClassName();
                        // Skip intrinsics and JDK classes — handled elsewhere or unsupported.
                        if (isIntrinsicClass(invClass) || isJavaLangObject(invClass)) continue;
                        called.add(LlvmMethodEmitter.mangle(inv.getMethod()));
                    }
                }
            }
        }
        return called;
    }
}
