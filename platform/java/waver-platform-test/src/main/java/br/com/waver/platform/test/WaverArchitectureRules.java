package br.com.waver.platform.test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture requirements as executable rules. Every service runs them over its own classes:
 *
 * <pre>
 * &#64;AnalyzeClasses(packages = "br.com.waver.tenant")
 * class ArchitectureTest {
 *     &#64;ArchTest static final ArchRule domain = WaverArchitectureRules.domainIsFrameworkFree("br.com.waver.tenant");
 * }
 * </pre>
 */
public final class WaverArchitectureRules {

    private WaverArchitectureRules() {
    }

    /** AR-007: the domain depends only on the JDK and the WAVER domain kernel. */
    public static ArchRule domainIsFrameworkFree(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "javax.persistence..", "org.hibernate..",
                        "org.apache.kafka..", "org.apache.avro..", "io.apicurio..", "com.fasterxml..", "tools.jackson..",
                        "io.temporal..", "org.keycloak..", "io.qdrant..")
                .because("AR-007: the domain layer is independent from infrastructure and frameworks");
    }

    /** Dependency rule: application never depends on adapters or bootstrap. */
    public static ArchRule applicationDoesNotDependOnAdapters(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(basePackage + ".adapters..", basePackage + ".bootstrap..")
                .because("Dependencies point inwards: Infrastructure -> Adapters -> Application -> Domain");
    }

    /** Domain never depends on application or adapters. */
    public static ArchRule domainDependsOnNothingOutward(String basePackage) {
        return noClasses().that().resideInAPackage(basePackage + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        basePackage + ".application..", basePackage + ".adapters..", basePackage + ".bootstrap..")
                .because("Dependencies point inwards");
    }
}
