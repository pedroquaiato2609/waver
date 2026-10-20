package br.com.waver.identityaccess.domain;

import br.com.waver.kernel.tenancy.OrgId;
import java.util.List;
import java.util.Optional;

/** Port: persisted invitations. */
public interface Invitations {

    Optional<Invitation> findLatest(OrgId orgId, String email);

    List<Invitation> findByOrg(OrgId orgId);

    void save(Invitation invitation);
}
