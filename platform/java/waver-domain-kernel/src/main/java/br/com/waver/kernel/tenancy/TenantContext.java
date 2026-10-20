package br.com.waver.kernel.tenancy;

import java.util.Objects;
import java.util.Set;

/**
 * Who is acting and in which organization. Always built from a validated token (AR-008),
 * never from request bodies or client headers.
 */
public record TenantContext(String principalId, PrincipalType principalType, OrgId orgId, OrgType orgType, Set<String> roles) {

    public enum PrincipalType { USER, SERVICE, SUPPORT }

    public TenantContext {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(principalType, "principalType");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(orgType, "orgType");
        roles = Set.copyOf(roles);
    }

    /** A WAVER service acting for the platform (event consumers, workflow activities): role PLATFORM_SERVICE. */
    public static TenantContext platformService(String serviceName, OrgId platformOrgId) {
        return new TenantContext(serviceName, PrincipalType.SERVICE, platformOrgId, OrgType.PLATFORM, Set.of("PLATFORM_SERVICE"));
    }

    public boolean isService(String serviceClientId) {
        return principalType == PrincipalType.SERVICE && principalId.equals(serviceClientId);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** WAVER staff acting on the platform organization (cross-tenant oversight, always audited). */
    public boolean isPlatformStaff() {
        return orgType == OrgType.PLATFORM && roles.stream().anyMatch(r -> r.startsWith("PLATFORM_") || r.startsWith("WAVER_"));
    }
}
