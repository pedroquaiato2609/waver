package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who a price book, pricing rule or discount policy applies to (RFC-0017 §4): every reseller, the resellers of
 * one tier (scopeRef = tier key) or one reseller (scopeRef = its organization ID). The more specific scope wins.
 */
public enum PriceScope {
    GLOBAL(1), TIER(2), RESELLER(3);

    private static final Pattern TIER_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final int rank;

    PriceScope(int rank) {
        this.rank = rank;
    }

    /** Higher is more specific: RESELLER over TIER over GLOBAL. */
    public int rank() {
        return rank;
    }

    /** Validates and normalizes scopeRef for this scope. */
    public String normalizeRef(String scopeRef) {
        String ref = scopeRef == null || scopeRef.isBlank() ? null : scopeRef.strip();
        switch (this) {
            case GLOBAL -> {
                if (ref != null) {
                    throw invalid("A global scope has no reference.");
                }
                return null;
            }
            case TIER -> {
                if (ref == null || !TIER_KEY.matcher(ref).matches()) {
                    throw invalid("Inform the tier key, e.g. GOLD.");
                }
                return ref;
            }
            default -> {
                try {
                    return UUID.fromString(ref == null ? "" : ref).toString();
                } catch (IllegalArgumentException e) {
                    throw invalid("Inform the reseller organization ID.");
                }
            }
        }
    }

    public boolean matches(String scopeRef, ResellerRef reseller) {
        return switch (this) {
            case GLOBAL -> true;
            case TIER -> reseller.tierKey().map(t -> t.equals(scopeRef)).orElse(false);
            case RESELLER -> reseller.orgId().value().toString().equals(scopeRef);
        };
    }

    private static DomainException invalid(String message) {
        return new DomainException(DomainException.Kind.VALIDATION, "CATALOG_SCOPE_INVALID", message);
    }
}
