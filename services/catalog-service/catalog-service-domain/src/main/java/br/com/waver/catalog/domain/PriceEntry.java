package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One SKU in a price book: what the reseller pays WAVER (WholesalePrice) and how the price to the customer is
 * set (RetailPrice). Amounts are decimals with 4 places in the currency of the book (CONVENTIONS).
 */
public record PriceEntry(UUID skuId, WholesalePrice wholesale, RetailPrice retail) {

    /** FLAT: one amount whatever the quantity; PER_UNIT: amount × quantity. */
    public enum ChargeModel { FLAT, PER_UNIT }

    /**
     * Who sets the final price (Commercial §10): the reseller adds its margin to the wholesale price (with an
     * optional suggestion), WAVER suggests a retail price the reseller may change, or WAVER fixes it.
     */
    public enum PricingModel { WHOLESALE_PLUS_MARGIN, SUGGESTED_RETAIL, FIXED_RETAIL }

    public record WholesalePrice(ChargeModel chargeModel, BigDecimal amount) {
        public WholesalePrice {
            Objects.requireNonNull(chargeModel);
            amount = checked(amount, "wholesale");
        }
    }

    /** Floor and ceiling bound what the reseller may charge per unit; null means no bound. */
    public record RetailPrice(PricingModel pricingModel, BigDecimal suggestedAmount, BigDecimal fixedAmount,
                              BigDecimal floorAmount, BigDecimal ceilingAmount) {
        public RetailPrice {
            Objects.requireNonNull(pricingModel);
            suggestedAmount = optionalAmount(suggestedAmount, "suggested");
            fixedAmount = optionalAmount(fixedAmount, "fixed");
            floorAmount = optionalAmount(floorAmount, "floor");
            ceilingAmount = optionalAmount(ceilingAmount, "ceiling");
        }

        public Optional<BigDecimal> suggested() { return Optional.ofNullable(suggestedAmount); }
        public Optional<BigDecimal> fixed() { return Optional.ofNullable(fixedAmount); }
        public Optional<BigDecimal> floor() { return Optional.ofNullable(floorAmount); }
        public Optional<BigDecimal> ceiling() { return Optional.ofNullable(ceilingAmount); }
    }

    public PriceEntry {
        Objects.requireNonNull(skuId);
        Objects.requireNonNull(wholesale, "wholesale");
        Objects.requireNonNull(retail, "retail");
    }

    /** Checks the retail settings against the model and the wholesale price (retail never below wholesale). */
    public static PriceEntry of(UUID skuId, WholesalePrice wholesale, RetailPrice retail) {
        if (wholesale == null || retail == null) {
            throw invalid("CATALOG_PRICE_INCOMPLETE", "Inform the wholesale and the retail price.");
        }
        BigDecimal w = wholesale.amount();
        switch (retail.pricingModel()) {
            case FIXED_RETAIL -> {
                if (retail.fixedAmount() == null) {
                    throw invalid("CATALOG_RETAIL_FIXED_REQUIRED", "A fixed retail price needs its amount.");
                }
                if (retail.suggestedAmount() != null || retail.floorAmount() != null || retail.ceilingAmount() != null) {
                    throw invalid("CATALOG_RETAIL_FIXED_EXCLUSIVE", "A fixed retail price has no suggestion, floor or ceiling.");
                }
            }
            case SUGGESTED_RETAIL -> {
                if (retail.suggestedAmount() == null) {
                    throw invalid("CATALOG_RETAIL_SUGGESTED_REQUIRED", "A suggested retail price needs its amount.");
                }
                requireNoFixed(retail);
            }
            case WHOLESALE_PLUS_MARGIN -> requireNoFixed(retail);
        }
        for (BigDecimal a : new BigDecimal[] {retail.suggestedAmount(), retail.fixedAmount(), retail.floorAmount(), retail.ceilingAmount()}) {
            if (a != null && a.compareTo(w) < 0) {
                throw invalid("CATALOG_RETAIL_BELOW_WHOLESALE", "Retail amounts cannot be below the wholesale price.");
            }
        }
        if (retail.floorAmount() != null && retail.ceilingAmount() != null && retail.floorAmount().compareTo(retail.ceilingAmount()) > 0) {
            throw invalid("CATALOG_RETAIL_BOUNDS_INVALID", "The floor cannot be above the ceiling.");
        }
        if (retail.suggestedAmount() != null && (retail.floor().map(f -> retail.suggestedAmount().compareTo(f) < 0).orElse(false)
                || retail.ceiling().map(c -> retail.suggestedAmount().compareTo(c) > 0).orElse(false))) {
            throw invalid("CATALOG_RETAIL_SUGGESTION_OUT_OF_BOUNDS", "The suggested price must be between the floor and the ceiling.");
        }
        return new PriceEntry(skuId, wholesale, retail);
    }

    private static void requireNoFixed(RetailPrice retail) {
        if (retail.fixedAmount() != null) {
            throw invalid("CATALOG_RETAIL_FIXED_UNEXPECTED", "Only a fixed retail price has a fixed amount.");
        }
    }

    private static BigDecimal checked(BigDecimal value, String what) {
        if (value == null) {
            throw invalid("CATALOG_PRICE_INCOMPLETE", "Inform the " + what + " amount.");
        }
        if (value.signum() < 0) {
            throw invalid("CATALOG_PRICE_NEGATIVE", "Prices cannot be negative.");
        }
        return value.setScale(4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal optionalAmount(BigDecimal value, String what) {
        return value == null ? null : checked(value, what);
    }

    private static DomainException invalid(String code, String message) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message);
    }
}
