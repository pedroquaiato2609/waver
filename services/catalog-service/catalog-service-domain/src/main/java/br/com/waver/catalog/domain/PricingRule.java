package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PricingRule (RFC-0017 §4, D-04 provisional): the margin band a reseller must keep over the wholesale price.
 * Margin is the markup on wholesale: (retail − wholesale) ÷ wholesale × 100. Every number comes from this entity;
 * nothing is hard-coded. Without a maximum the band is open above.
 */
public record PricingRule(UUID id, PolicyTarget target, BigDecimal minMarginPct, BigDecimal maxMarginPct, Instant createdAt) {

    public PricingRule {
        Objects.requireNonNull(id);
        Objects.requireNonNull(target);
        Objects.requireNonNull(minMarginPct);
        Objects.requireNonNull(createdAt);
    }

    public static PricingRule define(PolicyTarget target, BigDecimal minMarginPct, BigDecimal maxMarginPct, Instant now) {
        if (minMarginPct == null || minMarginPct.signum() < 0) {
            throw invalid("CATALOG_MARGIN_INVALID", "The minimum margin must be zero or more.");
        }
        BigDecimal min = minMarginPct.setScale(4, RoundingMode.HALF_EVEN);
        BigDecimal max = maxMarginPct == null ? null : maxMarginPct.setScale(4, RoundingMode.HALF_EVEN);
        if (max != null && max.compareTo(min) < 0) {
            throw invalid("CATALOG_MARGIN_BAND_INVALID", "The maximum margin cannot be below the minimum.");
        }
        return new PricingRule(Ids.newId(), target, min, max, now);
    }

    public Optional<BigDecimal> max() {
        return Optional.ofNullable(maxMarginPct);
    }

    private static DomainException invalid(String code, String message) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message);
    }
}
