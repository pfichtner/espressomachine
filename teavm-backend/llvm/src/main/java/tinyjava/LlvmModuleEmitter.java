package tinyjava;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.FieldHolder;
import org.teavm.model.Instruction;
import org.teavm.model.ListableClassHolderSource;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
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
        for (String name : classes.getClassNames()) {
            ClassHolder cls = classes.get(name);
            if (cls == null) continue;
            Map<String, Integer> indices = new LinkedHashMap<>();
            List<ValueType> types = new ArrayList<>();
            int i = 0;
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
        out.append("; TinyJava Phase 2 LLVM IR\n");
        out.append("; Generated from TeaVM 0.12.0 optimized IR\n\n");

        // 1. Struct type declarations
        emitStructTypes(out);

        // 2. Static field globals
        emitStaticFields(out);

        // 3. External declarations (java.lang.Object methods etc.)
        java.util.Set<String> defined = collectDefinedNames();
        java.util.Set<String> called = collectCalledNames();

        // 4. Method definitions
        StringBuilder methods = new StringBuilder();
        for (var entry : postOptPrograms.entrySet()) {
            String key = entry.getKey();
            Program prog = entry.getValue();
            MethodReader method = postOptMethods.get(key);
            if (method == null || prog == null) continue;
            if (isJavaLangObject(method.getReference().getClassName())) continue;

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
        boolean any = false;
        for (String name : sortedClassNames()) {
            ClassHolder cls = classes.get(name);
            if (cls == null || isJavaLangObject(name)) continue;
            List<ValueType> fts = fieldTypes.get(name);
            if (fts == null || fts.isEmpty()) continue;

            out.append("%").append(llvmStructName(name)).append(" = type { ");
            List<String> llvmFields = new ArrayList<>();
            for (ValueType ft : fts) {
                llvmFields.add(LlvmMethodEmitter.llvmType(ft));
            }
            out.append(String.join(", ", llvmFields)).append(" }\n");
            any = true;
        }
        if (any) out.append("\n");
    }

    // ------------------------------------------------------------------
    // Static field globals
    // ------------------------------------------------------------------

    private void emitStaticFields(StringBuilder out) {
        boolean any = false;
        for (String name : sortedClassNames()) {
            ClassHolder cls = classes.get(name);
            if (cls == null || isJavaLangObject(name)) continue;
            for (FieldHolder field : cls.getFields()) {
                if (!field.hasModifier(org.teavm.model.ElementModifier.STATIC)) continue;
                String globalName = "@" + LlvmMethodEmitter.mangle(name, field.getName());
                String llvmType = LlvmMethodEmitter.llvmType(field.getType());
                out.append(globalName).append(" = global ").append(llvmType).append(" 0\n");
                any = true;
            }
        }
        if (any) out.append("\n");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static String llvmStructName(String className) {
        return className.replace('.', '_').replace('/', '_') + "_t";
    }

    static boolean isJavaLangObject(String className) {
        return className.startsWith("java.") || className.startsWith("javax.")
            || className.startsWith("sun.") || className.startsWith("com.sun.");
    }

    private List<String> sortedClassNames() {
        var names = new ArrayList<>(classes.getClassNames());
        java.util.Collections.sort(names);
        return names;
    }

    private java.util.Set<String> collectDefinedNames() {
        var defined = new java.util.LinkedHashSet<String>();
        for (var entry : postOptMethods.entrySet()) {
            MethodReader m = entry.getValue();
            if (m != null && !isJavaLangObject(m.getReference().getClassName())) {
                defined.add(LlvmMethodEmitter.mangle(m));
            }
        }
        return defined;
    }

    private java.util.Set<String> collectCalledNames() {
        var called = new java.util.LinkedHashSet<String>();
        for (var entry : postOptPrograms.entrySet()) {
            Program prog = entry.getValue();
            if (prog == null) continue;
            for (int bi = 0; bi < prog.basicBlockCount(); bi++) {
                BasicBlock bb = prog.basicBlockAt(bi);
                if (bb == null) continue;
                for (Instruction insn : bb) {
                    if (insn instanceof InvokeInstruction inv) {
                        called.add(LlvmMethodEmitter.mangle(inv.getMethod()));
                    }
                }
            }
        }
        return called;
    }
}
