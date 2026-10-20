package br.com.waver.reseller.domain;

import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port. Row-Level Security limits what each caller sees: WAVER staff see all, a reseller only its own profile. */
public interface ResellerProfiles {

    void insert(ResellerProfile profile);

    /** Optimistic update of the profile and its checklist; false when the stored version is not {@code expectedVersion}. */
    boolean update(ResellerProfile profile, long expectedVersion);

    Optional<ResellerProfile> findById(UUID id);

    Optional<ResellerProfile> findByResellerOrg(OrgId resellerOrgId);

    boolean existsByTaxId(TaxId taxId);

    /** Newest first; cursor = last seen id; status null = all. */
    List<ResellerProfile> list(OnboardingStatus status, UUID afterId, int limit);

    /** Approved profiles whose workflow still has steps to run (organization or owner invitation). */
    List<UUID> findWithPendingOnboardingSteps(int limit);
}
