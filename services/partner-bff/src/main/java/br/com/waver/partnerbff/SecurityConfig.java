package br.com.waver.partnerbff;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * BFF security (ADR-0009, RFC-0004): Authorization Code + PKCE with Keycloak, tokens kept server-side in the
 * Redis-backed session, HttpOnly session cookie, CSRF token cookie for the SPA. Partner is for reseller users only:
 * WAVER staff and customer users are rejected even if the login itself succeeds (ADR-0009 audiences).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain bffChain(HttpSecurity http, ClientRegistrationRepository registrations) throws Exception {
        OidcClientInitiatedLogoutSuccessHandler logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/");

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/session").permitAll()
                        .requestMatchers("/api/**").access(resellerUsersOnly())
                        .anyRequest().permitAll())
                .oauth2Login(login -> login.defaultSuccessUrl("/", true))
                .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(logout))
                // SPA mode (token cookie on every response, X-XSRF-TOKEN header) with plain token comparison, so the
                // RP-initiated logout can post the same cookie value as a form field (_csrf). The token is never
                // rendered into a page, so the BREACH masking of spa() for form fields adds nothing here.
                .csrf(csrf -> csrf.spa().csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), PathPatternRequestMatcher.pathPattern("/api/**")));
        return http.build();
    }

    /**
     * Access/refresh tokens live in the HTTP session, which is stored in Redis (ADR-0009, ADR-0020): they survive
     * BFF restarts and scale-out, and never reach the browser. Spring's default keeps them in JVM memory.
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** Only users of a RESELLER organization may use WAVER Partner. */
    private static AuthorizationManager<RequestAuthorizationContext> resellerUsersOnly() {
        return (authentication, context) -> {
            var auth = authentication.get();
            boolean ok = auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof OidcUser user
                    && "RESELLER".equals(user.getClaimAsString("org_type"));
            return new AuthorizationDecision(ok);
        };
    }

    /** Maps the Keycloak "roles" claim to ROLE_* authorities (CONVENTIONS token claims). */
    @Bean
    GrantedAuthoritiesMapper waverRolesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>(authorities);
            for (GrantedAuthority authority : authorities) {
                if (authority instanceof OidcUserAuthority oidc) {
                    Collection<?> roles = oidc.getIdToken().getClaimAsStringList("roles");
                    if (roles != null) {
                        roles.forEach(r -> mapped.add(new SimpleGrantedAuthority("ROLE_" + r)));
                    }
                }
            }
            return mapped;
        };
    }

    static List<String> roles(OidcUser user) {
        List<String> roles = user.getClaimAsStringList("roles");
        return roles == null ? List.of() : roles.stream().filter(r -> !r.startsWith("default-roles") && !r.equals("offline_access") && !r.equals("uma_authorization")).sorted().toList();
    }
}
