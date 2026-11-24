package com.example.fixgateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The dependency rule, as in the other services: adapters -> application -> domain, never the other way. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.fixgateway");

    @Test
    void layers_ProductionClasses_OnlyDependInwards() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..fixgateway.domain..")
                .layer("Application").definedBy("..fixgateway.application..")
                .layer("Adapters").definedBy("..fixgateway.adapter..")
                .layer("Config").definedBy("..fixgateway.config..")
                .whereLayer("Adapters").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters")
                .check(CLASSES);
    }

    @Test
    void domain_ProductionClasses_IsPlainJava() {
        noClasses().that().resideInAPackage("..fixgateway.domain..")
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", "..fixgateway.domain..")
                .check(CLASSES);
    }

    /** ADR 0018: services share only the contracts. */
    @Test
    void serviceCode_ProductionClasses_KnowsNoOtherService() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.example.savings..", "com.example.transfers..", "com.example.currentaccount..",
                        "com.example.investments..")
                .check(CLASSES);
    }

    @Test
    void useCases_ProductionClasses_DoNotKnowWireFormatsOrInfrastructure() {
        noClasses().that().resideInAPackage("..fixgateway.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.example.events..", "com.example.platform..",
                        "org.apache.kafka..", "org.springframework.kafka..",
                        "org.springframework.jdbc..", "org.springframework.web..", "io.micrometer..")
                .check(CLASSES);
    }

    /** ADR 0027: FIX is an adapter; nothing else knows QuickFIX/J. */
    @Test
    void fix_ProductionClasses_OnlyInTheFixAdapter() {
        noClasses().that().resideOutsideOfPackage("..fixgateway.adapter.fix..")
                .should().dependOnClassesThat().resideInAPackage("quickfix..")
                .check(CLASSES);
    }
}
