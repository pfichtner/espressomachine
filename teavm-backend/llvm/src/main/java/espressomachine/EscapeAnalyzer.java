package espressomachine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.teavm.model.BasicBlock;
import org.teavm.model.FieldReference;
import org.teavm.model.Instruction;
import org.teavm.model.MethodReader;
import org.teavm.model.Program;
import org.teavm.model.Variable;
import org.teavm.model.instructions.AbstractInstructionVisitor;
import org.teavm.model.instructions.AssignInstruction;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.NullCheckInstruction;
import org.teavm.model.instructions.PutFieldInstruction;

/**
 * Intra-procedural escape analyzer for ConstructInstruction receivers.
 *
 * Classification:
 *   STACK   — allocated object stays within this stack frame (safe → alloca)
 *   STATIC  — stored exactly once into a static field in <clinit> (→ global struct)
 *   ESCAPE  — returned from method or passed to an external call (→ compile error)
 */
class EscapeAnalyzer {

    enum Fate { STACK, STATIC, ESCAPE }

    // Result for each ConstructInstruction, keyed by receiver variable index.
    private final Map<Integer, Fate> fate = new LinkedHashMap<>();
    // For STATIC allocations: the static field they are stored into.
    private final Map<Integer, FieldReference> staticField = new LinkedHashMap<>();

    // Set of variable indices that were produced by ConstructInstruction.
    private final Set<Integer> constructedVars = new HashSet<>();

    // Alias map: for AssignInstruction / NullCheckInstruction, tracks that
    // one variable is a copy of another (so escaping the copy = escaping the original).
    private final Map<Integer, Integer> aliases = new HashMap<>();

    private EscapeAnalyzer() {}

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    static EscapeAnalyzer analyze(Program program, MethodReader method) {
        EscapeAnalyzer ea = new EscapeAnalyzer();
        ea.run(program);
        return ea;
    }

    Fate fateOf(int varIndex) {
        return fate.getOrDefault(resolve(varIndex), Fate.STACK);
    }

    FieldReference staticFieldOf(int varIndex) {
        return staticField.get(resolve(varIndex));
    }

    // ------------------------------------------------------------------
    // Analysis
    // ------------------------------------------------------------------

    private void run(Program program) {
        // Pass 1: collect ConstructInstruction receivers and aliases.
        for (int bi = 0; bi < program.basicBlockCount(); bi++) {
            BasicBlock bb = program.basicBlockAt(bi);
            if (bb == null) continue;
            for (Instruction insn : bb) {
                if (insn instanceof ConstructInstruction ci) {
                    constructedVars.add(ci.getReceiver().getIndex());
                    fate.put(ci.getReceiver().getIndex(), Fate.STACK);
                } else if (insn instanceof AssignInstruction ai) {
                    int src = ai.getAssignee().getIndex();
                    int dst = ai.getReceiver().getIndex();
                    if (constructedVars.contains(src)) {
                        aliases.put(dst, src);
                        constructedVars.add(dst);
                        fate.put(dst, Fate.STACK);
                    }
                } else if (insn instanceof NullCheckInstruction nci) {
                    int src = nci.getValue().getIndex();
                    int dst = nci.getReceiver().getIndex();
                    if (constructedVars.contains(src)) {
                        aliases.put(dst, src);
                        constructedVars.add(dst);
                    }
                }
            }
        }

        if (constructedVars.isEmpty()) return;

        // Pass 2: determine escape fate by inspecting uses.
        for (int bi = 0; bi < program.basicBlockCount(); bi++) {
            BasicBlock bb = program.basicBlockAt(bi);
            if (bb == null) continue;
            for (Instruction insn : bb) {
                insn.acceptVisitor(new UsageScanner());
            }
        }
    }

    // Resolve alias chain to canonical variable index.
    private int resolve(int varIndex) {
        while (aliases.containsKey(varIndex)) {
            varIndex = aliases.get(varIndex);
        }
        return varIndex;
    }

    private void markEscape(Variable v) {
        if (v == null) return;
        int canonical = resolve(v.getIndex());
        if (constructedVars.contains(canonical)) {
            fate.put(canonical, Fate.ESCAPE);
        }
    }

    private void markStatic(Variable v, FieldReference field) {
        if (v == null) return;
        int canonical = resolve(v.getIndex());
        if (!constructedVars.contains(canonical)) return;
        Fate current = fate.getOrDefault(canonical, Fate.STACK);
        // ESCAPE takes priority; once escaped, static doesn't help.
        if (current != Fate.ESCAPE) {
            fate.put(canonical, Fate.STATIC);
            staticField.put(canonical, field);
        }
    }

    // ------------------------------------------------------------------
    // Usage scanner
    // ------------------------------------------------------------------

    private class UsageScanner extends AbstractInstructionVisitor {

        @Override
        public void visit(ExitInstruction insn) {
            // Returning a constructed object → it escapes the frame.
            markEscape(insn.getValueToReturn());
        }

        @Override
        public void visit(InvokeInstruction insn) {
            // Passing a constructed object as a non-this argument → conservative escape.
            // Passing as 'this' (insn.getInstance()) is safe — we're calling a method on it.
            for (Variable arg : insn.getArguments()) {
                markEscape(arg);
            }
            // Note: getInstance() is the 'this' receiver — NOT considered an escape.
        }

        @Override
        public void visit(PutFieldInstruction insn) {
            if (insn.getInstance() == null) {
                // Static field store.
                markStatic(insn.getValue(), insn.getField());
            } else {
                // Storing into an instance field: conservative — treat as escape
                // (the field owner could outlive the current frame).
                markEscape(insn.getValue());
            }
        }

        @Override
        public void visit(AssignInstruction insn) {
            // Already handled in pass 1 alias building.
        }
    }
}
