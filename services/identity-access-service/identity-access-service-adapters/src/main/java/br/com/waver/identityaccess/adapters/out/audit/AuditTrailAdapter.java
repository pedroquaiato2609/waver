package br.com.waver.identityaccess.adapters.out.audit;

import br.com.waver.identityaccess.application.OrganizationProvisioning;
import br.com.waver.identityaccess.domain.IdentityOrganization;
import br.com.waver.platform.events.AuditTrail;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Audit records go through the outbox, in the same transaction as the link (AR-009). */
@Component
public class AuditTrailAdapter implements OrganizationProvisioning.Audit {

    private final AuditTrail auditTrail;

    public AuditTrailAdapter(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Override
    public void organizationLinked(IdentityOrganization link) {
        auditTrail.record("identity.keycloak-organization.link", "keycloak-organization", link.keycloakOrganizationId(),
                link.orgId().value(), Map.of("orgType", link.orgType().name()));
    }
}
