package br.com.waver.identityaccess.domain;

import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.time.Instant;
import java.util.Objects;

/**
 * Link between a WAVER Organization and its Keycloak organization (ADR-0021: one Keycloak organization per WAVER
 * Organization, alias = orgId). identity-access-service is the only writer of this mapping.
 */
public record IdentityOrganization(OrgId orgId, OrgType orgType, String keycloakOrganizationId, String displayName, Instant linkedAt) {

    public IdentityOrganization {
        Objects.requireNonNull(orgId);
        Objects.requireNonNull(orgType);
        Objects.requireNonNull(keycloakOrganizationId);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(linkedAt);
    }

    /** Keycloak organization alias: the WAVER orgId, so tokens and admin tooling can be correlated. */
    public static String aliasFor(OrgId orgId) {
        return orgId.toString();
    }
}
