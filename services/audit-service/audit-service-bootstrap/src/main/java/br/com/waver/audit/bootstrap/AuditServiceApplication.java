package br.com.waver.audit.bootstrap;

import br.com.waver.audit.application.AuditService;
import br.com.waver.audit.domain.AuditLog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@SpringBootApplication(scanBasePackages = "br.com.waver.audit")
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }

    @Bean
    AuditService auditService(AuditLog log) {
        return new AuditService(log);
    }

    /** Retries with backoff (RFC-0006). The DLQ recoverer arrives with the first consumer that needs replay tooling. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxElapsedTime(30_000);
        return new DefaultErrorHandler(backOff);
    }
}
