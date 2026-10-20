package br.com.waver.tenant.bootstrap;

import br.com.waver.tenant.application.OrganizationEvents;
import br.com.waver.tenant.application.OrganizationService;
import br.com.waver.tenant.domain.OrganizationRepository;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@SpringBootApplication(scanBasePackages = "br.com.waver.tenant")
public class TenantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OrganizationService organizationService(OrganizationRepository organizations, OrganizationEvents events, Clock clock) {
        return new OrganizationService(organizations, events, clock);
    }

    /** Transient failures are retried with backoff (RFC-0005); the inbox keeps consumers idempotent. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxElapsedTime(120_000);
        return new DefaultErrorHandler(backOff);
    }
}
