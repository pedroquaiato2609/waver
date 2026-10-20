package br.com.waver.identityaccess.domain;

import br.com.waver.kernel.tenancy.OrgId;
import java.util.Optional;

/** Port: persisted WAVER Organization ↔ Keycloak organization links. */
public interface IdentityOrganizations {

    Optional<IdentityOrganization> find(OrgId orgId);

    void save(IdentityOrganization link);
}
