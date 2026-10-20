package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Price resolution and discount evaluation (RFC-0017 §4). Pure functions over the catalog entities: every limit
 * (margin band, discount limit, floor, ceiling) comes from PricingRule, DiscountPolicy and RetailPrice.
 */
public final class Pricing {

    public enum Outcome { WITHIN_POLICY, APPROVAL_REQUIRED, FORBIDDEN }

    /** Approval policy for a margin below the band (RFC-0017 §7, decided with the contract). */
    public static final String MARGIN_APPROVAL_POLICY = "MARGIN_BELOW_POLICY";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Pricing() {
    }

    /**
     * The price of one SKU for a reseller. {@code listUnit} is the retail list price per unit: the fixed or
     * suggested amount; for wholesale + margin without a suggestion, wholesale plus the minimum margin of the rule
     * (or wholesale itself when no rule applies).
     */
    public record Quote(Sku sku, PriceBook book, PriceEntry entry, long quantity, BigDecimal wholesaleUnit, BigDecimal wholesaleTotal,
                        BigDecimal listUnit, BigDecimal listTotal, Optional<PricingRule> rule) {
    }

    public record Evaluation(Outcome outcome, String approvalPolicyKey, List<String> reasons, BigDecimal proposedUnit,
                             BigDecimal discountPct, BigDecimal marginPct, Quote quote) {
    }

    /**
     * Picks the most specific active book that prices the SKU for the reseller at {@code at} (reseller over tier
     * over global). {@code currency} may be null: then the books found must agree on one currency.
     */
    public static Optional<Quote> quote(ResellerRef reseller, Sku sku, long quantity, Instant at, String currency,
                                        List<PriceBook> books, List<PricingRule> rules) {
        if (quantity < 1) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_QUANTITY_INVALID", "Quantity must be at least 1.");
        }
        List<PriceBook> candidates = books.stream()
                .filter(b -> b.appliesTo(reseller, at) && b.entry(sku.id()).isPresent())
                .filter(b -> currency == null || b.currency().getCurrencyCode().equals(currency))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int best = candidates.stream().mapToInt(b -> b.scope().rank()).max().orElseThrow();
        List<PriceBook> top = candidates.stream().filter(b -> b.scope().rank() == best).toList();
        if (top.stream().map(PriceBook::currency).distinct().count() > 1) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_QUOTE_CURRENCY_REQUIRED",
                    "This SKU has prices in more than one currency. Inform the currency.");
        }
        PriceBook book = top.stream().max(Comparator.comparing(PriceBook::validFrom)).orElseThrow();
        PriceEntry entry = book.entry(sku.id()).orElseThrow();
        Optional<PricingRule> rule = PolicyTarget.mostSpecific(rules, PricingRule::target, reseller, sku.id(), at);

        BigDecimal wholesale = entry.wholesale().amount();
        PriceEntry.RetailPrice retail = entry.retail();
        BigDecimal list = switch (retail.pricingModel()) {
            case FIXED_RETAIL -> retail.fixedAmount();
            case SUGGESTED_RETAIL -> retail.suggestedAmount();
            case WHOLESALE_PLUS_MARGIN -> retail.suggested().orElseGet(() -> rule
                    .map(r -> wholesale.add(wholesale.multiply(r.minMarginPct()).divide(HUNDRED, 4, RoundingMode.HALF_EVEN)))
                    .orElse(wholesale));
        };
        list = clamp(list, retail);
        PriceEntry.ChargeModel model = entry.wholesale().chargeModel();
        return Optional.of(new Quote(sku, book, entry, quantity, wholesale, total(model, wholesale, quantity), list,
                total(model, list, quantity), rule));
    }

    /**
     * Evaluates a proposed retail unit price against the entry bounds, the margin band and the discount policy.
     * FORBIDDEN beats APPROVAL_REQUIRED beats WITHIN_POLICY; every reason found is returned. Without a discount
     * policy any discount needs approval (fail closed).
     */
    public static Evaluation evaluate(Quote q, BigDecimal proposedUnit, Optional<DiscountPolicy> policy) {
        Objects.requireNonNull(q);
        if (proposedUnit == null || proposedUnit.signum() < 0) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_PROPOSED_PRICE_INVALID", "Inform a proposed price of zero or more.");
        }
        BigDecimal proposed = proposedUnit.setScale(4, RoundingMode.HALF_EVEN);
        PriceEntry.RetailPrice retail = q.entry().retail();
        List<String> forbidden = new ArrayList<>();
        List<String> approval = new ArrayList<>();

        if (retail.pricingModel() == PriceEntry.PricingModel.FIXED_RETAIL && proposed.compareTo(retail.fixedAmount()) != 0) {
            forbidden.add("RETAIL_PRICE_FIXED");
        }
        if (proposed.compareTo(q.wholesaleUnit()) < 0) {
            forbidden.add("BELOW_WHOLESALE");
        }
        retail.floor().filter(f -> proposed.compareTo(f) < 0).ifPresent(f -> forbidden.add("BELOW_FLOOR"));
        retail.ceiling().filter(c -> proposed.compareTo(c) > 0).ifPresent(c -> forbidden.add("ABOVE_CEILING"));

        BigDecimal margin = q.wholesaleUnit().signum() == 0 ? null
                : proposed.subtract(q.wholesaleUnit()).multiply(HUNDRED).divide(q.wholesaleUnit(), 4, RoundingMode.HALF_EVEN);
        if (margin != null && q.rule().isPresent()) {
            PricingRule rule = q.rule().get();
            rule.max().filter(max -> margin.compareTo(max) > 0).ifPresent(max -> forbidden.add("MARGIN_ABOVE_MAXIMUM"));
            if (margin.compareTo(rule.minMarginPct()) < 0) {
                approval.add("MARGIN_BELOW_MINIMUM");
            }
        }

        BigDecimal discount = q.listUnit().signum() == 0 || proposed.compareTo(q.listUnit()) >= 0 ? BigDecimal.ZERO.setScale(4)
                : q.listUnit().subtract(proposed).multiply(HUNDRED).divide(q.listUnit(), 4, RoundingMode.HALF_EVEN);
        String policyKey = null;
        if (discount.signum() > 0) {
            if (policy.isEmpty()) {
                approval.add("NO_DISCOUNT_POLICY");
                policyKey = DiscountPolicy.DEFAULT_APPROVAL_POLICY;
            } else if (discount.compareTo(policy.get().maxDiscountWithoutApprovalPct()) > 0) {
                approval.add("DISCOUNT_ABOVE_LIMIT");
                policyKey = policy.get().approvalPolicyKey();
            }
        }
        if (policyKey == null && approval.contains("MARGIN_BELOW_MINIMUM")) {
            policyKey = MARGIN_APPROVAL_POLICY;
        }

        List<String> reasons = new ArrayList<>(forbidden);
        reasons.addAll(approval);
        Outcome outcome = !forbidden.isEmpty() ? Outcome.FORBIDDEN : !approval.isEmpty() ? Outcome.APPROVAL_REQUIRED : Outcome.WITHIN_POLICY;
        return new Evaluation(outcome, outcome == Outcome.APPROVAL_REQUIRED ? policyKey : null, List.copyOf(reasons), proposed,
                discount, margin, q);
    }

    /** FLAT: the amount whatever the quantity; PER_UNIT: amount × quantity. */
    public static BigDecimal total(PriceEntry.ChargeModel model, BigDecimal unit, long quantity) {
        return model == PriceEntry.ChargeModel.FLAT ? unit : unit.multiply(BigDecimal.valueOf(quantity)).setScale(4, RoundingMode.HALF_EVEN);
    }

    /** The list price respects the entry's own floor and ceiling. */
    private static BigDecimal clamp(BigDecimal list, PriceEntry.RetailPrice retail) {
        BigDecimal v = list;
        if (retail.floorAmount() != null && v.compareTo(retail.floorAmount()) < 0) {
            v = retail.floorAmount();
        }
        if (retail.ceilingAmount() != null && v.compareTo(retail.ceilingAmount()) > 0) {
            v = retail.ceilingAmount();
        }
        return v.setScale(4, RoundingMode.HALF_EVEN);
    }
}
