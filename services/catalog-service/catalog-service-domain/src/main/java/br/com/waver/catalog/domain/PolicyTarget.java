package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a pricing rule or discount policy applies: scope (global, tier, reseller), optionally one SKU (D-04:
 * band per SKU), in a validity window (a promotion is a policy with an end date).
 */
public record PolicyTarget(PriceScope scope, String scopeRef, UUID skuId, Instant validFrom, Instant validTo) {

    public PolicyTarget {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(validFrom);
    }

    public static PolicyTarget of(PriceScope scope, String scopeRef, UUID skuId, Instant validFrom, Instant validTo, Instant now) {
        if (scope == null) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_SCOPE_INVALID", "Inform the scope.");
        }
        Instant from = validFrom == null ? now : validFrom;
        if (validTo != null && !validTo.isAfter(from)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_VALIDITY_INVALID", "The end of validity must be after its start.");
        }
        return new PolicyTarget(scope, scope.normalizeRef(scopeRef), skuId, from, validTo);
    }

    public boolean appliesTo(ResellerRef reseller, UUID sku, Instant at) {
        return (skuId == null || skuId.equals(sku)) && !validFrom.isAfter(at) && (validTo == null || at.isBefore(validTo))
                && scope.matches(scopeRef, reseller);
    }

    /** Scope first (reseller over tier over global), then a SKU-specific entry over one for every SKU. */
    public int specificity() {
        return scope.rank() * 2 + (skuId == null ? 0 : 1);
    }

    /** The most specific of the candidates that apply; the newest wins a tie. */
    public static <T> Optional<T> mostSpecific(List<T> candidates, java.util.function.Function<T, PolicyTarget> target,
                                               ResellerRef reseller, UUID sku, Instant at) {
        return candidates.stream()
                .filter(c -> target.apply(c).appliesTo(reseller, sku, at))
                .max(Comparator.<T>comparingInt(c -> target.apply(c).specificity())
                        .thenComparing(c -> target.apply(c).validFrom()));
    }

    public Optional<String> ref() { return Optional.ofNullable(scopeRef); }
    public Optional<UUID> sku() { return Optional.ofNullable(skuId); }
    public Optional<Instant> until() { return Optional.ofNullable(validTo); }
}
