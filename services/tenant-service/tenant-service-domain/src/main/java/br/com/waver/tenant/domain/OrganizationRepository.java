package br.com.waver.tenant.domain;

import br.com.waver.kernel.tenancy.OrgId;
import java.util.List;
import java.util.Optional;

/** Port. Visibility is enforced by Row-Level Security, so queries only return rows the caller may see. */
public interface OrganizationRepository {

    /** {@code idempotencyKey} may be null; when set it is unique per parent organization. */
    void insert(Organization organization, String idempotencyKey);

    /** Optimistic update: false when the stored version is no longer {@code expectedVersion}. */
    boolean update(Organization organization, long expectedVersion);

    Optional<Organization> findById(OrgId id);

    Optional<Organization> findByIdempotencyKey(OrgId parentId, String idempotencyKey);

    /** Organizations visible to the caller, newest first, cursor = last seen id. */
    List<Organization> findVisible(OrgId afterId, int limit);
}
