package br.com.waver.identityaccess.application;

import br.com.waver.identityaccess.domain.IdentityOrganization;
import br.com.waver.identityaccess.domain.IdentityOrganizations;
import br.com.waver.identityaccess.domain.IdentityProvider;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mirrors new WAVER Organizations into Keycloak (RFC-0003: Keycloak Organizations mirror, driven by tenant events).
 * Idempotent: redelivered events and retries never create a second Keycloak organization.
 */
public class OrganizationProvisioning {

    public interface Audit {
        void organizationLinked(IdentityOrganization link);
    }

    private final IdentityOrganizations links;
    private final IdentityProvider idp;
    private final Audit audit;
    private final Clock clock;

    public OrganizationProvisioning(IdentityOrganizations links, IdentityProvider idp, Audit audit, Clock clock) {
        this.links = links;
        this.idp = idp;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IdentityOrganization onOrganizationCreated(OrgId orgId, OrgType orgType, String legalName) {
        return links.find(orgId).orElseGet(() -> {
            String keycloakId = idp.ensureOrganization(orgId, legalName);
            IdentityOrganization link = new IdentityOrganization(orgId, orgType, keycloakId, legalName, clock.instant());
            links.save(link);
            audit.organizationLinked(link);
            return link;
        });
    }
}
