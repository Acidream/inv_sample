package com.example.transfers;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The dependency rule, as in savings: adapters -> application -> domain, never the other way. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.transfers");

    @Test
    void layers_ProductionClasses_OnlyDependInwards() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..transfers.domain..")
                .layer("Application").definedBy("..transfers.application..")
                .layer("Adapters").definedBy("..transfers.adapter..")
                .layer("Config").definedBy("..transfers.config..")
                .whereLayer("Adapters").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters")
                .check(CLASSES);
    }

    @Test
    void domain_ProductionClasses_IsPlainJava() {
        noClasses().that().resideInAPackage("..transfers.domain..")
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", "..transfers.domain..")
                .check(CLASSES);
    }

    /** ADR 0018: the transfer service moves money; it knows nothing about savings products or code. */
    @Test
    void serviceCode_ProductionClasses_KnowsNoOtherService() {
        noClasses().should().dependOnClassesThat().resideInAPackage("com.example.savings..").check(CLASSES);
    }

    @Test
    void useCases_ProductionClasses_DoNotKnowWireFormatsOrInfrastructure() {
        noClasses().that().resideInAPackage("..transfers.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.example.events..", "com.example.platform..",
                        "org.apache.kafka..", "org.springframework.kafka..",
                        "org.springframework.jdbc..", "org.springframework.web..", "io.micrometer..")
                .check(CLASSES);
    }
}
