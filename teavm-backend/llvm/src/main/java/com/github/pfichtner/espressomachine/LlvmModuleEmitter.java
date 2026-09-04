package com.github.pfichtner.espressomachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    final AvrIntrinsics intrinsics = new AvrIntrinsics();
    final ListableClassHolderSource classes;
    // postOptPrograms/Methods come from afterOptimizations callbacks
    private final LinkedHashMap<String, Program> postOptPrograms;
    private final LinkedHashMap<String, MethodReader> postOptMethods;
    // Entry class name; used to resolve the main()/setup()/loop() entry method.
    private final String entryClass;

    // Per-class field layout: className → (fieldName → index-in-struct)
    final Map<String, Map<String, Integer>> fieldIndices = new HashMap<>();
    // Per-class field types: className → list of ValueType in struct order
    final Map<String, List<ValueType>> fieldTypes = new HashMap<>();

    // De-duplicated string literals → LLVM global byte-array symbol name.
    final Map<String, String> stringGlobals = new LinkedHashMap<>();

    LlvmModuleEmitter(ListableClassHolderSource classes,
                      LinkedHashMap<String, Program> postOptPrograms,
                      LinkedHashMap<String, MethodReader> postOptMethods,
                      String entryClass) {
        this.classes = classes;
        this.postOptPrograms = postOptPrograms;
        this.postOptMethods = postOptMethods;
        this.entryClass = entryClass;
        buildFieldMaps();
    }

    // ------------------------------------------------------------------
    // Pre-pass: build field index maps for all classes
    // ------------------------------------------------------------------

    private void buildFieldMaps() {
        // Detect enum classes first so field indexing is correct.
        classes.getClassNames().stream()
            .filter(name -> {
                ClassHolder cls = classes.get(name);
                return cls != null && JAVA_LANG_ENUM.equals(cls.getParent());
            })
            .forEach(enumClasses::add);

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
        out.append("; EspressoMachine Phase 2 LLVM IR\n");
        out.append("; Generated from TeaVM 0.12.0 optimized IR\n\n");

        // 1. Struct type declarations
        emitStructTypes(out);

        // 2. Static field globals
        emitStaticFields(out);

        // 2b. AVR intrinsic runtime declarations (serial only when the program uses it)
        out.append(intrinsics.declarations(postOptPrograms));
        out.append("\n");

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
        called.stream()
            .filter(callee -> !defined.contains(callee))
            .forEach(callee -> out.append("declare void @").append(callee).append("(...)\n"));
        if (!called.isEmpty()) out.append("\n");

        out.append(methods);

        // String literal globals — emitted after methods so the pool is populated
        // (internString() runs while methods are emitted above).
        emitStringGlobals(out);

        // Arduino-style entry: if the entry class has no static void main() but
        // defines setup()/loop(), synthesize a ClassName_main wrapper that calls
        // setup() once then loops calling loop(). Keep main() winning when present.
        appendSyntheticMain(out);
        if (!jdkGlobalStubs.isEmpty()) {
            out.append("\n");
            jdkGlobalStubs.forEach(stub -> out.append(stub).append(" = global i8 0\n"));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // String literal globals
    // ------------------------------------------------------------------

    // Returns the LLVM global symbol for a string literal, registering it in the
    // pool if not already seen. Each literal becomes a null-terminated byte array
    // so the Serial runtime can walk it directly. The Java String heap is not
    // supported, but Serial.print(String)/println(String) lower to these globals.
    String internString(String literal) {
        return stringGlobals.computeIfAbsent(literal, lit -> "@espressomachine_string_" + stringGlobals.size());
    }

    private void emitStringGlobals(StringBuilder out) {
        if (stringGlobals.isEmpty()) return;
        stringGlobals.forEach((key, value) -> {
            String lit = escapeLlvmCString(key);
            out.append(value)
               .append(" = private unnamed_addr constant [")
               .append(lit.length() + 1).append(" x i8] c\"")
               .append(lit).append("\\00\"\n");
        });
        out.append("\n");
    }

    // Escape a Java string for use inside an LLVM c-string literal.
    private static String escapeLlvmCString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\22");
                case '\n' -> sb.append("\\0A");
                case '\r' -> sb.append("\\0D");
                case '\t' -> sb.append("\\09");
                default -> {
                    if (c < 32 || c > 126) {
                        sb.append(String.format("\\%02X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
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
                cls.getFields().stream()
                    .filter(f -> !f.hasModifier(org.teavm.model.ElementModifier.STATIC))
                    .map(f -> LlvmMethodEmitter.llvmType(f.getType()))
                    .forEach(llvmFields::add);
            } else {
                List<ValueType> fts = fieldTypes.get(name);
                if (fts == null || fts.isEmpty()) continue;
                fts.stream().map(LlvmMethodEmitter::llvmType).forEach(llvmFields::add);
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

    // JDK class static object globals referenced by user code — need stub definitions
    // so the LLVM module is well-formed (the pointer value is never actually used).
    final Set<String> jdkGlobalStubs = new LinkedHashSet<>();

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
    boolean isIntrinsicClass(String className) {
        return intrinsics.isIntrinsic(className);
    }

    private List<String> sortedClassNames() {
        var names = new ArrayList<String>(classes.getClassNames());
        java.util.Collections.sort(names);
        return names;
    }

    private Set<String> collectDefinedNames() {
        return postOptMethods.values().stream()
            .filter(m -> m != null && !isJavaLangObject(m.getReference().getClassName()))
            .map(LlvmMethodEmitter::mangle)
            .collect(Collectors.toCollection(LinkedHashSet::new));
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

    // ------------------------------------------------------------------
    // Arduino-style entry synthesis (setup()/loop())
    // ------------------------------------------------------------------

    /**
     * If the entry class has no static void main(), synthesize a ClassName_main
     * wrapper that calls setup() once and then loops calling loop() forever.
     * A real main() always wins over setup()/loop().
     */
    private void appendSyntheticMain(StringBuilder out) {
        if (entryClass == null) return;
        ClassHolder cls = classes.get(entryClass);
        if (cls == null || isIntrinsicClass(entryClass) || isJavaLangObject(entryClass)) return;

        // A real main() takes precedence — no synthetic wrapper needed.
        if (hasStaticVoidMethod(cls, "main")) return;

        boolean hasSetup = hasStaticVoidMethod(cls, "setup");
        boolean hasLoop = hasStaticVoidMethod(cls, "loop");
        if (!hasSetup && !hasLoop) return;

        String mainName = LlvmMethodEmitter.mangle(entryClass, "main");
        String setupName = LlvmMethodEmitter.mangle(entryClass, "setup");
        String loopName = LlvmMethodEmitter.mangle(entryClass, "loop");

        out.append("define void @").append(mainName).append("() {\n");
        out.append("entry:\n");
        if (hasSetup) {
            out.append("  call void @").append(setupName).append("()\n");
        }
        out.append("  br label %loop\n");
        out.append("loop:\n");
        if (hasLoop) {
            out.append("  call void @").append(loopName).append("()\n");
        }
        out.append("  br label %loop\n");
        out.append("}\n\n");
    }

    /** Returns true if {@code cls} declares a static method with the given name and no-arg void signature. */
    private static boolean hasStaticVoidMethod(ClassHolder cls, String name) {
        if (cls == null) return false;
        return cls.getMethods().stream().anyMatch(m ->
            name.equals(m.getName()) &&
            m.hasModifier(org.teavm.model.ElementModifier.STATIC) &&
            m.parameterCount() == 0 &&
            ValueType.VOID.equals(m.getResultType()));
    }
}
