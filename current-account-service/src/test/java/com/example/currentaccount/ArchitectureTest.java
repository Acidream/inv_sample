package com.example.currentaccount;

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
            .importPackages("com.example.currentaccount");

    @Test
    void layersOnlyDependInwards() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..currentaccount.domain..")
                .layer("Application").definedBy("..currentaccount.application..")
                .layer("Adapters").definedBy("..currentaccount.adapter..")
                .layer("Config").definedBy("..currentaccount.config..")
                .whereLayer("Adapters").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters")
                .check(CLASSES);
    }

    @Test
    void domainIsPlainJava() {
        noClasses().that().resideInAPackage("..currentaccount.domain..")
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", "..currentaccount.domain..")
                .check(CLASSES);
    }

    /** ADR 0018: services share only the contracts. */
    @Test
    void knowsNoOtherServicesCode() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("com.example.savings..", "com.example.transfers..")
                .check(CLASSES);
    }

    @Test
    void useCasesDoNotKnowAboutWireFormatsOrInfrastructure() {
        noClasses().that().resideInAPackage("..currentaccount.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.example.events..", "org.apache.kafka..", "org.springframework.kafka..",
                        "org.springframework.jdbc..", "org.springframework.web..", "io.micrometer..")
                .check(CLASSES);
    }
}
