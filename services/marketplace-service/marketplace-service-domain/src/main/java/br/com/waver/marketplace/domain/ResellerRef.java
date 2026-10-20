package br.com.waver.marketplace.domain;

import br.com.waver.kernel.tenancy.OrgId;
import java.util.Objects;
import java.util.Optional;

/** The reseller asking what it may sell: its organization and, when it has one, its tier key. */
public record ResellerRef(OrgId orgId, Optional<String> tierKey) {

    public ResellerRef {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(tierKey, "tierKey");
    }
}
