package com.github.pfichtner.espressomachine;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.github.pfichtner.espressomachine..")
class ArchitectureTest {

    private static final String ROOT_PACKAGE = "com.github.pfichtner.espressomachine";
    private static final String EMIT_PACKAGE = "com.github.pfichtner.espressomachine.emit";

    /**
     * Emitters are self-contained infrastructure. They only depend on the TeaVM
     * model and java.util — never on the dispatcher or CLI layer above them.
     */
    @ArchTest
    static final ArchRule emit_package_has_no_upward_dependencies =
        noClasses()
            .that().resideInAPackage(EMIT_PACKAGE)
            .should().dependOnClassesThat()
            .resideInAPackage(ROOT_PACKAGE)
            .because("the emit package must not depend on the dispatcher layer above it");

    /**
     * AvrIntrinsics is the sole gateway into the emit package. No other class
     * in the outer package or CLI layer may import emitter types directly.
     */
    @ArchTest
    static final ArchRule only_AvrIntrinsics_may_access_emit_package =
        noClasses()
            .that().resideOutsideOfPackage(EMIT_PACKAGE)
            .and().doNotHaveSimpleName("AvrIntrinsics")
            .should().dependOnClassesThat()
            .resideInAPackage(EMIT_PACKAGE)
            .because("AvrIntrinsics is the only entry point into the emit package");

    /**
     * GpioEmitter, DelayEmitter and SerialEmitter are fully independent. Each
     * emitter owns exactly one API; they must never import a peer emitter.
     * LlvmWriter and RegisterFile (shared infrastructure) are allowed.
     */
    @ArchTest
    static final ArchRule concrete_emitters_do_not_know_each_other =
        noClasses()
            .that().resideInAPackage(EMIT_PACKAGE)
            .and().haveSimpleNameEndingWith("Emitter")
            .and().areNotInterfaces()
            .should().dependOnClassesThat(describe("are concrete emitters in the emit package",
                (JavaClass c) -> c.getPackageName().equals(EMIT_PACKAGE)
                              && c.getSimpleName().endsWith("Emitter")
                              && !c.isInterface()))
            .because("coupling sibling emitters would undermine the IntrinsicEmitter abstraction");

    /**
     * LlvmWriter and RegisterFile are low-level LLVM generation utilities.
     * They must remain unaware of any emitter so that emitters stay composable
     * over a shared writer without circular knowledge.
     */
    @ArchTest
    static final ArchRule llvm_infrastructure_does_not_depend_on_emitters =
        noClasses()
            .that(describe("are LlvmWriter or RegisterFile",
                (JavaClass c) -> c.getSimpleName().equals("LlvmWriter")
                              || c.getSimpleName().equals("RegisterFile")))
            .should().dependOnClassesThat()
            .haveSimpleNameEndingWith("Emitter")
            .because("LlvmWriter and RegisterFile are infrastructure; emitters depend on them, not the other way round");
}
