package br.com.waver.reseller.bootstrap;

import br.com.waver.reseller.application.ResellerOnboardingService;
import br.com.waver.reseller.domain.ChecklistItem;
import br.com.waver.reseller.domain.ResellerProfiles;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication(scanBasePackages = "br.com.waver.reseller")
public class ResellerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResellerServiceApplication.class, args);
    }

    /**
     * Activation policy (RFC-0017 §2 guard): waver.reseller.activation.required-items. With catalog-service and
     * marketplace-service in place, CATALOG_ASSIGNED and PRICING_RULES_SET are required too.
     */
    @Bean
    ResellerOnboardingService resellerOnboardingService(ResellerProfiles profiles, ResellerOnboardingService.Events events,
                                                        ResellerOnboardingService.TenantDirectory tenants,
                                                        ResellerOnboardingService.IdentityDirectory identity,
                                                        ResellerOnboardingService.OnboardingOrchestrator orchestrator,
                                                        PlatformTransactionManager transactions,
                                                        @Value("${waver.reseller.activation.required-items}") Set<ChecklistItem> required) {
        return new ResellerOnboardingService(profiles, events, tenants, identity, orchestrator,
                new TransactionTemplate(transactions), required, Clock.systemUTC());
    }
}
