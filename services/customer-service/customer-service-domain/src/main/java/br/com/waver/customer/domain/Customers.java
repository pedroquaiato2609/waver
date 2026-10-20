package br.com.waver.customer.domain;

import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port. Row-Level Security limits rows to the caller's portfolio (reseller), the customer itself, or WAVER
 * oversight. Registering also opens the first CustomerOwnership row (one open row per customer).
 */
public interface Customers {

    void register(CustomerProfile profile, String idempotencyKey);

    Optional<CustomerProfile> findById(UUID id);

    Optional<CustomerProfile> findByIdempotencyKey(OrgId resellerOrgId, String idempotencyKey);

    /** Within one reseller portfolio only: another reseller's customers stay invisible (RLS). */
    boolean existsInPortfolio(OrgId resellerOrgId, TaxId taxId);

    /** Newest first, cursor = last seen id. */
    List<CustomerProfile> list(UUID afterId, int limit);
}
