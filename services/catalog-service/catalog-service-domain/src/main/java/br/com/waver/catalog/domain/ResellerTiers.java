package br.com.waver.catalog.domain;

import br.com.waver.kernel.tenancy.OrgId;
import java.util.Optional;

/**
 * Port. Tier of a reseller, a local projection of reseller-service (ResellerTierChanged). Tiers are not modelled
 * in reseller-service yet, so the projection is empty and TIER scopes match no one until they are.
 */
public interface ResellerTiers {

    Optional<String> tierOf(OrgId resellerOrgId);
}
