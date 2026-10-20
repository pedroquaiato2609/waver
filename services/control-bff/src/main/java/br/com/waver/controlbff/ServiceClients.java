package br.com.waver.controlbff;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * Clients for the services the Control audience may use (ADR-0009: the allow-list is the set of clients that exist).
 * Each call carries the signed-in user's Keycloak access token (refreshed transparently). Token exchange to a
 * down-scoped token per service (RFC-0004) replaces this relay when the exchange policy is configured.
 */
@Configuration
public class ServiceClients {

    @Bean
    RestClient tenantService(RestClient.Builder builder, OAuth2AuthorizedClientManager clients,
                             @Value("${waver.services.tenant-service}") String baseUrl) {
        return client(builder, clients, baseUrl);
    }

    @Bean
    RestClient auditService(RestClient.Builder builder, OAuth2AuthorizedClientManager clients,
                            @Value("${waver.services.audit-service}") String baseUrl) {
        return client(builder, clients, baseUrl);
    }

    @Bean
    RestClient resellerService(RestClient.Builder builder, OAuth2AuthorizedClientManager clients,
                               @Value("${waver.services.reseller-service}") String baseUrl) {
        return client(builder, clients, baseUrl);
    }

    @Bean
    RestClient catalogService(RestClient.Builder builder, OAuth2AuthorizedClientManager clients,
                              @Value("${waver.services.catalog-service}") String baseUrl) {
        return client(builder, clients, baseUrl);
    }

    @Bean
    RestClient marketplaceService(RestClient.Builder builder, OAuth2AuthorizedClientManager clients,
                                  @Value("${waver.services.marketplace-service}") String baseUrl) {
        return client(builder, clients, baseUrl);
    }

    private static RestClient client(RestClient.Builder builder, OAuth2AuthorizedClientManager clients, String baseUrl) {
        OAuth2ClientHttpRequestInterceptor oauth = new OAuth2ClientHttpRequestInterceptor(clients);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestInterceptor(oauth)
                .defaultRequest(r -> r.attributes(clientRegistrationId("keycloak")))
                .build();
    }
}
