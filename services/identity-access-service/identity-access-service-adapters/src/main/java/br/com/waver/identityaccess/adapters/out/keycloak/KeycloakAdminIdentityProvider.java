package br.com.waver.identityaccess.adapters.out.keycloak;

import br.com.waver.identityaccess.domain.IdentityOrganization;
import br.com.waver.identityaccess.domain.IdentityProvider;
import br.com.waver.identityaccess.domain.Invitation;
import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Keycloak Admin REST API with the service account {@code svc-identity-access-service} (client credentials).
 * Only identity-access-service may call the Admin API (RFC-0002 forbidden dependencies).
 */
@Component
public class KeycloakAdminIdentityProvider implements IdentityProvider {

    /** Account setup actions, in the order Keycloak asks for them (RFC-0004: MFA mandatory for admin roles). */
    private static final List<String> SETUP_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP");

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final InvitationApps apps;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public KeycloakAdminIdentityProvider(RestClient.Builder builder,
                                         @Value("${waver.keycloak.base-url}") String baseUrl,
                                         @Value("${waver.keycloak.realm}") String realm,
                                         @Value("${waver.keycloak.client-id}") String clientId,
                                         @Value("${keycloak.client-secret}") String clientSecret,
                                         InvitationApps apps) {
        this.http = builder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.apps = apps;
    }

    record OrganizationRepresentation(String id, String name, String alias, Boolean enabled, String description) {
    }

    record UserRepresentation(String id, String username, String email, Map<String, List<String>> attributes) {
    }

    record RoleRepresentation(String id, String name) {
    }

    @Override
    public String ensureOrganization(OrgId orgId, String displayName) {
        String alias = IdentityOrganization.aliasFor(orgId);
        String existing = findByAlias(alias);
        if (existing != null) {
            return existing;
        }
        URI location = http.post()
                .uri("/admin/realms/{realm}/organizations", realm)
                .headers(h -> h.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", displayName + " (" + alias.substring(0, 8) + ")", "alias", alias, "enabled", true,
                        "description", "WAVER organization " + alias))
                .retrieve()
                .toBodilessEntity()
                .getHeaders().getLocation();
        if (location == null) {
            String created = findByAlias(alias);
            if (created == null) {
                throw new IllegalStateException("Keycloak did not return the created organization for alias " + alias);
            }
            return created;
        }
        return lastSegment(location);
    }

    @Override
    public String ensureUser(Invitation.Invitee invitee, OrgId orgId, OrgType orgType) {
        UserRepresentation existing = findUserByEmail(invitee.email());
        if (existing == null) {
            try {
                URI location = http.post()
                        .uri("/admin/realms/{realm}/users", realm)
                        .headers(h -> h.setBearerAuth(accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "username", invitee.email(),
                                "email", invitee.email(),
                                "firstName", invitee.firstName(),
                                "lastName", invitee.lastName(),
                                "enabled", true,
                                "emailVerified", false,
                                "attributes", Map.of("org_id", List.of(orgId.toString()), "org_type", List.of(orgType.name())),
                                "requiredActions", SETUP_ACTIONS))
                        .retrieve()
                        .toBodilessEntity()
                        .getHeaders().getLocation();
                existing = location != null ? new UserRepresentation(lastSegment(location), invitee.email(), invitee.email(), null)
                        : findUserByEmail(invitee.email());
            } catch (HttpClientErrorException.Conflict raced) {
                existing = findUserByEmail(invitee.email());
            }
            if (existing == null) {
                throw new IllegalStateException("Keycloak did not return the created user");
            }
        } else {
            List<String> userOrg = existing.attributes() == null ? null : existing.attributes().get("org_id");
            if (userOrg == null || !userOrg.contains(orgId.toString())) {
                throw new DomainException(DomainException.Kind.CONFLICT, "IDENTITY_USER_IN_OTHER_ORGANIZATION",
                        "This e-mail already has a WAVER account in another organization.");
            }
        }
        assignRealmRole(existing.id(), invitee.roleKey());
        return existing.id();
    }

    @Override
    public void addOrganizationMember(String identityOrganizationId, String userId) {
        try {
            http.post()
                    .uri("/admin/realms/{realm}/organizations/{org}/members", realm, identityOrganizationId)
                    .headers(h -> h.setBearerAuth(accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("\"" + userId + "\"")
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict alreadyMember) {
            // idempotent
        }
    }

    @Override
    public void sendAccountSetupEmail(String userId, OrgType orgType) {
        InvitationApps.App app = apps.forOrgType(orgType);
        http.put()
                .uri(b -> b.path("/admin/realms/{realm}/users/{id}/execute-actions-email")
                        .queryParam("client_id", app.clientId())
                        .queryParam("redirect_uri", app.redirectUri())
                        .queryParam("lifespan", Invitation.DEFAULT_VALIDITY.toSeconds())
                        .build(realm, userId))
                .headers(h -> h.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(SETUP_ACTIONS)
                .retrieve()
                .toBodilessEntity();
    }

    private void assignRealmRole(String userId, String roleKey) {
        RoleRepresentation role = http.get()
                .uri("/admin/realms/{realm}/roles/{role}", realm, roleKey)
                .headers(h -> h.setBearerAuth(accessToken()))
                .retrieve()
                .body(RoleRepresentation.class);
        http.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                .headers(h -> h.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(role))
                .retrieve()
                .toBodilessEntity();
    }

    private UserRepresentation findUserByEmail(String email) {
        List<UserRepresentation> found = http.get()
                .uri(b -> b.path("/admin/realms/{realm}/users").queryParam("email", email).queryParam("exact", true).build(realm))
                .headers(h -> h.setBearerAuth(accessToken()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return found == null ? null : found.stream().filter(u -> email.equalsIgnoreCase(u.email())).findFirst().orElse(null);
    }

    private String findByAlias(String alias) {
        List<OrganizationRepresentation> found = http.get()
                .uri(b -> b.path("/admin/realms/{realm}/organizations").queryParam("search", alias).queryParam("exact", true).build(realm))
                .headers(h -> h.setBearerAuth(accessToken()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return found == null ? null : found.stream()
                .filter(o -> alias.equals(o.alias()))
                .map(OrganizationRepresentation::id)
                .findFirst().orElse(null);
    }

    private static String lastSegment(URI location) {
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private synchronized String accessToken() {
        if (token == null || Instant.now().isAfter(tokenExpiresAt.minus(Duration.ofSeconds(30)))) {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            Map<?, ?> response = http.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Keycloak returned no token");
            }
            token = (String) response.get("access_token");
            tokenExpiresAt = Instant.now().plusSeconds(((Number) response.get("expires_in")).longValue());
        }
        return token;
    }
}
