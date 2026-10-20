package br.com.waver.platform.autoconfigure;

import br.com.waver.platform.events.AuditTrail;
import br.com.waver.platform.events.AvroEventSerializer;
import br.com.waver.platform.events.EventPublisher;
import br.com.waver.platform.events.Inbox;
import br.com.waver.platform.http.ServiceCredentials;
import br.com.waver.platform.security.TenantContextFilter;
import br.com.waver.platform.security.WaverJwt;
import br.com.waver.platform.tenancy.TenantRlsDataSource;
import br.com.waver.platform.web.CorrelationIdFilter;
import br.com.waver.platform.web.ProblemDetailsAdvice;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** Platform chassis wiring (RFC-0001 §platform starter). Each part activates only when its technology is present. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"})
public class WaverPlatformAutoConfiguration {

    /**
     * Avro (1.11.4+) only instantiates generated classes from trusted packages. WAVER event classes come from
     * contracts/events (ADR-0024); nothing else is trusted. Set before any Kafka client or serializer is created.
     */
    static {
        String key = "org.apache.avro.SERIALIZABLE_PACKAGES";
        String current = System.getProperty(key);
        if (current == null || !current.contains("br.com.waver.events")) {
            System.setProperty(key, current == null || current.isBlank() ? "br.com.waver.events" : current + ",br.com.waver.events");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class Web {

        @Bean
        FilterRegistrationBean<CorrelationIdFilter> waverCorrelationIdFilter() {
            FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }

        @Bean
        @ConditionalOnMissingBean
        ProblemDetailsAdvice waverProblemDetailsAdvice() {
            return new ProblemDetailsAdvice();
        }
    }

    /** Resource server for internal service APIs: Keycloak JWT + WAVER tenant context (ADR-0021, AR-008). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(BearerTokenAuthenticationFilter.class)
    @ConditionalOnProperty(name = "waver.security.resource-server", havingValue = "true", matchIfMissing = true)
    @EnableMethodSecurity
    static class ResourceServer {

        @Bean
        JwtAuthenticationConverter waverJwtAuthenticationConverter() {
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(WaverJwt::authorities);
            return converter;
        }

        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        SecurityFilterChain waverResourceServerChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                    .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class)
                    .httpBasic(b -> b.disable());
            return http.build();
        }
    }

    /** Row-Level Security session variables on every pooled connection (ADR-0010). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    static class Jdbc {

        @Bean
        static BeanPostProcessor waverTenantRlsDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource ds && !(bean instanceof TenantRlsDataSource)) {
                        return new TenantRlsDataSource(ds);
                    }
                    return bean;
                }
            };
        }

        @Bean
        @ConditionalOnBean(JdbcTemplate.class)
        @ConditionalOnMissingBean
        Inbox waverInbox(JdbcTemplate jdbc) {
            return new Inbox(jdbc);
        }
    }

    /**
     * Service-account credentials for calls made without a user (ADR-0021). Enabled by setting
     * waver.service-auth.client-id; the secret comes from Vault as keycloak.client-secret (ADR-0022).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.client.RestClient")
    @ConditionalOnProperty(name = "waver.service-auth.client-id")
    static class ServiceAuth {

        @Bean
        @ConditionalOnMissingBean
        ServiceCredentials waverServiceCredentials(org.springframework.web.client.RestClient.Builder builder,
                                                   @Value("${waver.service-auth.token-uri:${spring.security.oauth2.resourceserver.jwt.issuer-uri}/protocol/openid-connect/token}") String tokenUri,
                                                   @Value("${waver.service-auth.client-id}") String clientId,
                                                   @Value("${keycloak.client-secret}") String clientSecret) {
            return new ServiceCredentials(builder, tokenUri, clientId, clientSecret);
        }
    }

    /** Outbox publishing with Avro + Apicurio (ADR-0024). Enabled by setting waver.events.registry-url. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.apicurio.registry.serde.avro.AvroKafkaSerializer")
    @ConditionalOnProperty(name = "waver.events.registry-url")
    static class Events {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        AvroEventSerializer waverAvroEventSerializer(@Value("${waver.events.registry-url}") String registryUrl) {
            return new AvroEventSerializer(registryUrl);
        }

        @Bean
        @ConditionalOnMissingBean
        EventPublisher waverEventPublisher(JdbcTemplate jdbc, AvroEventSerializer serializer,
                                           @Value("${spring.application.name}") String serviceName) {
            return new EventPublisher(jdbc, serializer, serviceName);
        }

        @Bean
        @ConditionalOnMissingBean
        AuditTrail waverAuditTrail(EventPublisher publisher, @Value("${spring.application.name}") String serviceName) {
            return new AuditTrail(publisher, serviceName);
        }
    }
}
