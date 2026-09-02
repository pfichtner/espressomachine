# Phase 0 Findings: TeaVM IR Feasibility

## Summary

**Verdict: TeaVM is a viable frontend for ByteLight.**

TeaVM can be embedded as a library, its optimized IR can be enumerated programmatically,
and a direct mapping to LLVM IR is straightforward for the instructions relevant to embedded code.

---

## 1. Which TeaVM classes represent the IR

TeaVM's primary IR is in the `org.teavm.model` package.

| Class | Role |
|---|---|
| `Program` | Container for a method's IR: list of `Variable`s and `BasicBlock`s |
| `BasicBlock` | A linear sequence of `Instruction`s plus `Phi` nodes, ends with a terminator |
| `Instruction` | Base class for every IR node (visitor pattern via `InstructionVisitor`) |
| `Variable` | An SSA value; identified by index (`%0`, `%1`, ...) |
| `Phi` | PHI node: selects a value based on which predecessor block was taken |
| `ValueType` | Type system: `Primitive` (INT/LONG/FLOAT/DOUBLE/BOOLEAN/BYTE/SHORT/CHAR), `Object`, `Array`, `Void` |
| `ClassHolder` | Mutable class representation with fields and methods |
| `MethodHolder` | Holds descriptor, access flags, and a `Program` |
| `FieldHolder` | Field with type and access flags |

### Instruction taxonomy (`org.teavm.model.instructions`)

**Constants**
- `IntegerConstantInstruction`, `LongConstantInstruction`, `FloatConstantInstruction`, `DoubleConstantInstruction`
- `NullConstantInstruction`, `StringConstantInstruction`, `ClassConstantInstruction`

**Arithmetic / logic**
- `BinaryInstruction` — ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, AND, OR, XOR, SHIFT_LEFT, SHIFT_RIGHT, SHIFT_RIGHT_UNSIGNED, COMPARE_GREATER, COMPARE_LESS
- `NegateInstruction`
- `CastNumberInstruction` — numeric widening/narrowing conversions
- `CastIntegerInstruction` — truncation to byte/short/char

**Moves / identity**
- `AssignInstruction` — `%dst = %src`
- `CastInstruction` — reference type check-cast

**Control flow** (terminators)
- `JumpInstruction` — unconditional branch
- `BranchingInstruction` — unary conditional (NULL, NOT_NULL, EQUAL_ZERO, NOT_EQUAL_ZERO, LESS, GREATER, LESS_OR_EQUAL, GREATER_OR_EQUAL)
- `BinaryBranchingInstruction` — binary conditional (EQUAL, NOT_EQUAL, REFERENCE_EQUAL, REFERENCE_NOT_EQUAL)
- `ExitInstruction` — return (with optional value)
- `SwitchInstruction` — jump table / table switch
- `RaiseInstruction` — throw exception

**Invocations**
- `InvokeInstruction` — SPECIAL (direct), VIRTUAL, INTERFACE, STATIC

**Object model**
- `ConstructInstruction` — `new ClassName` (allocate, does NOT call constructor)
- `GetFieldInstruction` / `PutFieldInstruction` — instance and static field access
- `ArrayLengthInstruction`, `GetElementInstruction`, `PutElementInstruction`
- `ConstructArrayInstruction`, `ConstructMultiArrayInstruction`, `CloneArrayInstruction`
- `IsInstanceInstruction` — `instanceof`

**JVM housekeeping**
- `InitClassInstruction` — trigger static initializer
- `NullCheckInstruction` — explicit null guard (inserted by optimizer)
- `BoundCheckInstruction` — array bounds guard
- `MonitorEnterInstruction` / `MonitorExitInstruction` — `synchronized`
- `EmptyInstruction` — no-op

---

## 2. How the compilation pipeline reaches the IR

```
javac → .class → URLClassLoader (user classpath)
                       │
               ClasspathClassHolderSource      ← reads bytecode, builds ClassHolder/Program
                       │
               TeaVMBuilder.build()
                       │
               TeaVM.build(BuildTarget, name)
                       │
          ┌────────────┴──────────────┐
          │    eagerPipeline()        │  (when optimization ≥ ADVANCED)
          │                           │
          │  link(dependencyAnalyzer) │  ← reachability analysis, produces ListableClassHolderSource
          │  devirtualize(classSet)   │  ← devirtualize virtual calls to static
          │  classInitializerAnalysis │
          │  insertClassInit          │
          │  for each method:         │
          │    target.beforeInlining  │  ← first useful hook: all programs visible
          │  inline(classSet)         │  ← small methods inlined, programs may be set to null
          │  optimize(classSet):      │
          │    target.beforeOpt       │
          │    optimizers run         │
          │    target.afterOpt        │  ← second useful hook: fully optimized programs
          │  target.emit(classes, …)  │  ← final hook: emit output
          └───────────────────────────┘
```

Key API entry points:

```java
var refCache = new ReferenceCache();
var classSource = new ClasspathClassHolderSource(urlClassLoader, refCache);
var builder = new TeaVMBuilder(myTarget)
    .setClassLoader(urlCL)
    .setClassSource(classSource)
    .setReferenceCache(refCache);
var vm = builder.build();
vm.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);  // enables eager pipeline
vm.setEntryPoint("MyClass");
vm.build(buildTarget, "out");
```

---

## 3. How a backend accesses the IR

Implement `org.teavm.vm.TeaVMTarget`. The relevant methods:

```java
// Called for every reachable method BEFORE inlining (eager pipeline only).
// Programs still contain all code.
@Override
public void beforeInlining(Program program, MethodReader method) { ... }

// Called per-method AFTER optimization, program is fully optimized SSA.
@Override
public void afterOptimizations(Program program, MethodReader method) { ... }

// Final emission phase. `classes` contains all surviving classes and methods.
// Methods inlined away have program == null.
@Override
public void emit(ListableClassHolderSource classes, BuildTarget target, String outputName) {
    for (String name : classes.getClassNames()) {
        ClassHolder cls = classes.get(name);
        for (MethodHolder method : cls.getMethods()) {
            Program p = method.getProgram();
            if (p == null) continue;  // inlined or native
            for (int i = 0; i < p.basicBlockCount(); i++) {
                BasicBlock bb = p.basicBlockAt(i);
                for (Instruction insn : bb) {
                    insn.acceptVisitor(myLLVMEmitter);  // visitor pattern
                }
            }
        }
    }
}
```

**Visitor pattern**: Every `Instruction` subclass has `acceptVisitor(InstructionVisitor)`.
Implement `InstructionVisitor` (or extend `AbstractInstructionVisitor`) to handle each instruction type.

---

## 4. Required runtime/dependency-analysis components

Minimum required TeaVM Maven artifacts:

```xml
<dependency>
    <groupId>org.teavm</groupId>
    <artifactId>teavm-core</artifactId>
    <version>0.12.0</version>
</dependency>
```

This includes: parser, model, dependency analyzer, optimizer, VM orchestration.

The `teavm-tooling` artifact adds the higher-level `TeaVMTool` wrapper but is not strictly needed.

**No TeaVM classlib is required** for the IR dumper itself. The classlib is needed when the
compiled program references Java standard library classes (e.g., `System.exit`, `String`, etc.).
For ByteLight's embedded target, standard library support will be replaced by intrinsics.

---

## 5. Whether implementing an LLVM backend is practical

**Yes, it is practical.**

Observed from the `Add.add(int, int)` example:

```
TeaVM pre-inlining IR:              → LLVM IR:
  BB0: jump BB1                     entry:
  BB1:                                br label %BB1
    %3 = assign %1                  BB1:
    %4 = assign %2                    %3 = add i32 %1, %2
    %5 = int_add %3, %4               ret i32 %3
    return %5
```

After optimization (redundant assigns eliminated):

```
  BB1:
    %3 = int_add %1, %2             BB1:
    return %3                         %3 = add i32 %1, %2
                                      ret i32 %3
```

Observed from the `ControlFlow.test(int)` example (if/else):

```
TeaVM:                              LLVM IR:
  BB0: jump BB1                     entry:  br label %BB1
  BB1:                              BB1:
    %2 = int_const 10                 %cmp = icmp sle i32 %1, 10
    %3 = int_compare %1, %2           br i1 %cmp, label %BB2, label %BB3
    branch %3 if LESS_OR_EQUAL        
      -> BB2 else BB3               BB2:
  BB2:                                ret i32 0
    %4 = int_const 0                BB3:
    return %4                         ret i32 1
  BB3:
    %5 = int_const 1
    return %5
```

Observed from `ControlFlow.count()` (while loop with PHI):

```
TeaVM:                              LLVM IR:
  BB4:                              BB4:
    %3 = phi [%1 from BB1,            %3 = phi i32 [%1, %BB1], [%6, %BB3]
              %6 from BB3]            %cmp = icmp sge i32 %3, 10
    %4 = int_compare %3, 10           br i1 %cmp, label %BB2, label %BB3
    branch %4 if GEQ -> BB2 BB3
```

**PHI nodes** are already in SSA form — they translate directly to LLVM `phi` instructions.

**Variables** are indexed integers — they map directly to LLVM virtual registers `%0`, `%1`, etc.

### Instructions NOT needed for Phase 0–2 (embedded target, no GC)

For an AVR target without a heap allocator or full Java runtime, the following can be
rejected with an error at compile time:
- `MonitorEnterInstruction` / `MonitorExitInstruction` — no threading
- `InvokeDynamicInstruction` — no lambdas/invokedynamic initially
- `CloneArrayInstruction`, `ConstructMultiArrayInstruction` — not needed initially
- `StringConstantInstruction` — no managed String heap

---

## 6. Proposed mapping: TeaVM IR → LLVM IR

### Types

| TeaVM `ValueType` | LLVM type |
|---|---|
| `INT` | `i32` |
| `LONG` | `i64` |
| `FLOAT` | `float` |
| `DOUBLE` | `double` |
| `BOOLEAN`, `BYTE` | `i8` |
| `SHORT`, `CHAR` | `i16` |
| `VOID` | `void` |
| Object reference | `ptr` (or `i8*`) |

### Instructions

| TeaVM instruction | LLVM |
|---|---|
| `IntegerConstantInstruction` | `%r = add i32 0, <val>` or just use constant inline |
| `BinaryInstruction ADD INT` | `%r = add i32 %a, %b` |
| `BinaryInstruction SUBTRACT INT` | `%r = sub i32 %a, %b` |
| `BinaryInstruction MULTIPLY INT` | `%r = mul i32 %a, %b` |
| `BinaryInstruction DIVIDE INT` | `%r = sdiv i32 %a, %b` |
| `BinaryInstruction MODULO INT` | `%r = srem i32 %a, %b` |
| `BinaryInstruction AND INT` | `%r = and i32 %a, %b` |
| `BinaryInstruction OR INT` | `%r = or i32 %a, %b` |
| `BinaryInstruction XOR INT` | `%r = xor i32 %a, %b` |
| `BinaryInstruction SHIFT_LEFT INT` | `%r = shl i32 %a, %b` |
| `BinaryInstruction SHIFT_RIGHT INT` | `%r = ashr i32 %a, %b` |
| `BinaryInstruction SHIFT_RIGHT_UNSIGNED INT` | `%r = lshr i32 %a, %b` |
| `BinaryInstruction COMPARE_GREATER INT` | `%r = icmp sgt i32 %a, %b` then `zext i1 to i32` |
| `NegateInstruction INT` | `%r = sub i32 0, %a` |
| `AssignInstruction` | identity (reuse variable name) |
| `CastNumberInstruction INT→LONG` | `%r = sext i32 %a to i64` |
| `CastNumberInstruction LONG→INT` | `%r = trunc i64 %a to i32` |
| `CastNumberInstruction INT→FLOAT` | `%r = sitofp i32 %a to float` |
| `CastNumberInstruction FLOAT→INT` | `%r = fptosi float %a to i32` |
| `ExitInstruction` (void) | `ret void` |
| `ExitInstruction` (value) | `ret i32 %v` |
| `JumpInstruction` | `br label %BBn` |
| `BranchingInstruction` (cond `EQUAL_ZERO`) | `%c = icmp eq i32 %v, 0; br i1 %c, label %T, label %F` |
| `BranchingInstruction` (cond `LESS`) | `%c = icmp slt i32 %v, 0; br i1 %c, ...` |
| `BinaryBranchingInstruction EQUAL` | `%c = icmp eq i32 %a, %b; br i1 %c, ...` |
| `InvokeInstruction STATIC` | `%r = call i32 @ClassName_methodName(i32 %a, ...)` |
| `InvokeInstruction SPECIAL` | direct call (same as STATIC for non-virtual) |
| `InvokeInstruction VIRTUAL` | function pointer via vtable (Phase 2+) |
| `Phi` | `%r = phi i32 [%a, %BB1], [%b, %BB2]` |
| `ConstructInstruction` | `%r = alloca %ClassName` (stack) or `call @malloc` (heap — Phase 3+) |
| `GetFieldInstruction` (instance) | `%p = getelementptr %T, ptr %obj, i32 0, i32 <field_idx>; %r = load i32, ptr %p` |
| `PutFieldInstruction` (instance) | `%p = getelementptr ...; store i32 %v, ptr %p` |
| `GetFieldInstruction` (static) | `%r = load i32, ptr @ClassName_fieldName` |
| `PutFieldInstruction` (static) | `store i32 %v, ptr @ClassName_fieldName` |
| `InitClassInstruction` | call `@ClassName__clinit()` if not already initialized |
| `NullCheckInstruction` | (omit or insert trap) |
| `BoundCheckInstruction` | (omit or insert trap) |

### Method naming convention

TeaVM flattens the Java class hierarchy. A good naming convention for LLVM:

```
Add_add_II_I        → Add.add(int, int) : int
ControlFlow_test_I_I
Counter_increment_V
```

(class name, underscore, method name, descriptor chars or abbreviated)

### Example: Full LLVM IR for Add.add

```llvm
define i32 @Add_add(i32 %1, i32 %2) {
entry:
  br label %BB1
BB1:
  %3 = add i32 %1, %2
  ret i32 %3
}
```

### Example: Full LLVM IR for ControlFlow.test

```llvm
define i32 @ControlFlow_test(i32 %1) {
entry:
  br label %BB1
BB1:
  %2 = icmp sle i32 %1, 10
  br i1 %2, label %BB2, label %BB3
BB2:
  ret i32 0
BB3:
  ret i32 1
}
```

### Example: Full LLVM IR for ControlFlow.count (while loop)

```llvm
define i32 @ControlFlow_count() {
entry:
  br label %BB1
BB1:
  %1 = add i32 0, 0
  br label %BB4
BB2:
  ret i32 %3
BB3:
  %5 = add i32 0, 1
  %6 = add i32 %3, %5
  br label %BB4
BB4:
  %3 = phi i32 [%1, %BB1], [%6, %BB3]
  %2 = add i32 0, 10
  %4 = icmp sge i32 %3, %2
  br i1 %4, label %BB2, label %BB3
}
```

---

## 7. Conclusions

1. **TeaVM embeds cleanly as a library.** The `TeaVMBuilder` / `TeaVMTarget` API is well-defined
   and stable. No source modification is required.

2. **The IR is enumerable.** All reachable methods and their basic blocks can be traversed via
   `ListableClassHolderSource`. Each instruction can be visited with `InstructionVisitor`.

3. **The IR is already in SSA form with explicit PHI nodes.** This maps directly to LLVM IR
   without a separate SSA construction pass.

4. **Dependency analysis removes unreachable code.** Only methods actually reachable from the
   entry point appear in the final class set. This is ideal for embedded targets.

5. **Inlining and devirtualization are done for us.** With `ADVANCED` optimization level, TeaVM
   devirtualizes virtual calls and inlines small methods — reducing the work the LLVM backend
   needs to do.

6. **The `BinaryInstruction.COMPARE_GREATER/LESS` instructions** return integer comparison results
   used immediately by `BranchingInstruction` — the backend should recognize this pattern and
   lower it to an LLVM `icmp` feeding a `br i1`.

7. **No C intermediate representation.** The entire pipeline from TeaVM IR to LLVM IR is direct.

### Open questions for Phase 1

- Should the ByteLight backend implement `BranchingInstruction` by pattern-matching the immediately
  preceding compare, or treat the compare result as a boolean `i1`?
  - Recommendation: treat the compare result as a separate SSA variable of type `i1` for generality.
- How should `InitClassInstruction` be handled? For a static-only embedded target, class
  initialization can be driven at link time, eliminating these guards entirely.

### Suggested next step (Phase 1)

Implement a minimal LLVM IR emitter in the `emit()` method that:
1. Defines LLVM function signatures for each method
2. Walks basic blocks in order, emitting one LLVM instruction per TeaVM instruction
3. Emits PHI nodes at block entry
4. Writes valid `.ll` text to a file
5. Validates with `llvm-as` or `opt --verify`
