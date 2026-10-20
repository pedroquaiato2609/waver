package br.com.waver.identityaccess.bootstrap;

import br.com.waver.identityaccess.adapters.out.keycloak.InvitationApps;
import br.com.waver.identityaccess.application.InvitationService;
import br.com.waver.identityaccess.application.OrganizationProvisioning;
import br.com.waver.identityaccess.domain.IdentityOrganizations;
import br.com.waver.identityaccess.domain.IdentityProvider;
import br.com.waver.identityaccess.domain.Invitations;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@SpringBootApplication(scanBasePackages = "br.com.waver.identityaccess")
@EnableConfigurationProperties(InvitationApps.class)
public class IdentityAccessServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityAccessServiceApplication.class, args);
    }

    @Bean
    OrganizationProvisioning organizationProvisioning(IdentityOrganizations links, IdentityProvider idp,
                                                      OrganizationProvisioning.Audit audit) {
        return new OrganizationProvisioning(links, idp, audit, Clock.systemUTC());
    }

    @Bean
    InvitationService invitationService(Invitations invitations, IdentityOrganizations links, IdentityProvider idp,
                                        InvitationService.Events events) {
        return new InvitationService(invitations, links, idp, events, Clock.systemUTC());
    }

    /** Keycloak may be briefly unavailable: retry with backoff (RFC-0005), the inbox keeps it idempotent. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxElapsedTime(120_000);
        return new DefaultErrorHandler(backOff);
    }
}
