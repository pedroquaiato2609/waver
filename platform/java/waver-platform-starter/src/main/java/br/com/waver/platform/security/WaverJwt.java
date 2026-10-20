package br.com.waver.platform.security;

import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the WAVER claims of a Keycloak access token (CONVENTIONS: org_id, org_type, roles, act, support_grant). */
public final class WaverJwt {

    private WaverJwt() {
    }

    public static Set<String> roles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        return roles(jwt).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Empty when the token carries no WAVER tenant claims (e.g. a bare service-account token). */
    public static Optional<TenantContext> tenantContext(Jwt jwt) {
        String orgId = jwt.getClaimAsString("org_id");
        String orgType = jwt.getClaimAsString("org_type");
        if (orgId == null || orgType == null) {
            return Optional.empty();
        }
        // Keycloak puts client_id only in service-account tokens (client credentials): the principal is the service
        // client (svc-<service>, CONVENTIONS), which is what authorization rules and audit records name.
        String serviceClient = jwt.getClaimAsString("client_id");
        if (serviceClient != null) {
            return Optional.of(new TenantContext(serviceClient, TenantContext.PrincipalType.SERVICE, OrgId.of(orgId),
                    OrgType.valueOf(orgType), roles(jwt)));
        }
        TenantContext.PrincipalType type = jwt.hasClaim("support_grant")
                ? TenantContext.PrincipalType.SUPPORT
                : TenantContext.PrincipalType.USER;
        return Optional.of(new TenantContext(jwt.getSubject(), type, OrgId.of(orgId), OrgType.valueOf(orgType), roles(jwt)));
    }
}
