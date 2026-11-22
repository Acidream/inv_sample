package com.example.savings;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The dependency rule, enforced: adapters -> application -> domain, never the other way. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.savings");

    @Test
    void layersOnlyDependInwards() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..savings.domain..")
                .layer("Application").definedBy("..savings.application..")
                .layer("Adapters").definedBy("..savings.adapter..")
                .layer("Config").definedBy("..savings.config..")
                .whereLayer("Adapters").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters")
                .check(CLASSES);
    }

    @Test
    void domainIsPlainJavaPlusJpaMapping() {
        noClasses().that().resideInAPackage("..savings.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.hibernate..", "org.apache.kafka..", "com.google.protobuf..",
                        "io.micrometer..")
                .check(CLASSES);
    }

    @Test
    void useCasesDoNotKnowAboutWireFormatsOrInfrastructure() {
        noClasses().that().resideInAPackage("..savings.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.example.events..", "org.apache.kafka..", "org.springframework.kafka..",
                        "org.springframework.jdbc..", "org.springframework.web..", "io.micrometer..")
                .check(CLASSES);
    }
}
