package br.com.waver.catalog.bootstrap;

import br.com.waver.platform.test.WaverArchitectureRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "br.com.waver.catalog")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = WaverArchitectureRules.domainIsFrameworkFree("br.com.waver.catalog");

    @ArchTest
    static final ArchRule domainDependsOnNothingOutward = WaverArchitectureRules.domainDependsOnNothingOutward("br.com.waver.catalog");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = WaverArchitectureRules.applicationDoesNotDependOnAdapters("br.com.waver.catalog");
}
