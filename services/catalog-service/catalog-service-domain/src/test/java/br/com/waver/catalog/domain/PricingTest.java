package br.com.waver.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PricingTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final OrgId ALPHA = new OrgId(Ids.newId());
    private static final ResellerRef ALPHA_GOLD = new ResellerRef(ALPHA, Optional.of("GOLD"));
    private static final Sku AGENT = Sku.define(Ids.newId(), "WV-AGENT-MONTHLY", Sku.Unit.WORKSPACE, Sku.ChargeType.RECURRING,
            Sku.BillingPeriod.MONTHLY, null);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static PriceBook activeBook(PriceScope scope, String ref, PriceEntry entry) {
        PriceBook book = PriceBook.draft("Tabela " + scope, "BRL", scope, ref, NOW.minusSeconds(3600), null, NOW.minusSeconds(3600));
        book.putEntry(entry);
        book.activate("commercial", Optional.empty(), NOW.minusSeconds(60));
        return book;
    }

    private static PriceEntry marginEntry(String wholesale) {
        return PriceEntry.of(AGENT.id(), new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.PER_UNIT, d(wholesale)),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.WHOLESALE_PLUS_MARGIN, null, null, null, null));
    }

    private static PricingRule band(PriceScope scope, String ref, String min, String max) {
        return PricingRule.define(PolicyTarget.of(scope, ref, null, null, null, NOW.minusSeconds(3600)), d(min), max == null ? null : d(max), NOW);
    }

    @Test
    void theMostSpecificBookWins() {
        PriceBook global = activeBook(PriceScope.GLOBAL, null, marginEntry("100"));
        PriceBook tier = activeBook(PriceScope.TIER, "GOLD", marginEntry("90"));
        PriceBook own = activeBook(PriceScope.RESELLER, ALPHA.value().toString(), marginEntry("80"));

        assertThat(Pricing.quote(ALPHA_GOLD, AGENT, 1, NOW, null, List.of(global, tier, own), List.of()).orElseThrow().book()).isEqualTo(own);
        assertThat(Pricing.quote(ALPHA_GOLD, AGENT, 1, NOW, null, List.of(global, tier), List.of()).orElseThrow().book()).isEqualTo(tier);
        ResellerRef other = new ResellerRef(new OrgId(Ids.newId()), Optional.empty());
        assertThat(Pricing.quote(other, AGENT, 1, NOW, null, List.of(global, tier, own), List.of()).orElseThrow().book())
                .as("another reseller without tier gets the global book").isEqualTo(global);
    }

    @Test
    void wholesalePlusMarginListsAtTheMinimumMarginOfTheRule() {
        PriceBook global = activeBook(PriceScope.GLOBAL, null, marginEntry("100"));
        Pricing.Quote q = Pricing.quote(ALPHA_GOLD, AGENT, 3, NOW, "BRL", List.of(global),
                List.of(band(PriceScope.GLOBAL, null, "20", "60"), band(PriceScope.TIER, "GOLD", "25", "50"))).orElseThrow();
        assertThat(q.listUnit()).isEqualByComparingTo("125");
        assertThat(q.wholesaleTotal()).isEqualByComparingTo("300");
        assertThat(q.listTotal()).isEqualByComparingTo("375");
        assertThat(q.rule().orElseThrow().minMarginPct()).as("tier rule beats the global one").isEqualByComparingTo("25");
    }

    @Test
    void flatChargeIgnoresQuantity() {
        PriceEntry flat = PriceEntry.of(AGENT.id(), new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.FLAT, d("500")),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.SUGGESTED_RETAIL, d("700"), null, null, null));
        Pricing.Quote q = Pricing.quote(ALPHA_GOLD, AGENT, 10, NOW, null, List.of(activeBook(PriceScope.GLOBAL, null, flat)), List.of()).orElseThrow();
        assertThat(q.wholesaleTotal()).isEqualByComparingTo("500");
        assertThat(q.listTotal()).isEqualByComparingTo("700");
    }

    @Test
    void noBookMeansNoPrice() {
        PriceBook draft = PriceBook.draft("Rascunho", "BRL", PriceScope.GLOBAL, null, null, null, NOW);
        draft.putEntry(marginEntry("100"));
        assertThat(Pricing.quote(ALPHA_GOLD, AGENT, 1, NOW, null, List.of(draft), List.of())).isEmpty();
    }

    @Test
    void evaluationFollowsTheConfiguredBandAndDiscountLimit() {
        PriceEntry entry = PriceEntry.of(AGENT.id(), new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.PER_UNIT, d("100")),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.SUGGESTED_RETAIL, d("150"), null, d("105"), d("200")));
        Pricing.Quote q = Pricing.quote(ALPHA_GOLD, AGENT, 1, NOW, null, List.of(activeBook(PriceScope.GLOBAL, null, entry)),
                List.of(band(PriceScope.GLOBAL, null, "20", "80"))).orElseThrow();
        DiscountPolicy limit = DiscountPolicy.define(PolicyTarget.of(PriceScope.GLOBAL, null, null, null, null, NOW), d("10"), null, NOW);

        assertThat(Pricing.evaluate(q, d("140"), Optional.of(limit)).outcome()).isEqualTo(Pricing.Outcome.WITHIN_POLICY);

        Pricing.Evaluation deep = Pricing.evaluate(q, d("125"), Optional.of(limit));
        assertThat(deep.outcome()).isEqualTo(Pricing.Outcome.APPROVAL_REQUIRED);
        assertThat(deep.approvalPolicyKey()).isEqualTo("DISCOUNT_ABOVE_POLICY");
        assertThat(deep.reasons()).containsExactly("DISCOUNT_ABOVE_LIMIT");

        Pricing.Evaluation thin = Pricing.evaluate(q, d("110"), Optional.of(limit));
        assertThat(thin.outcome()).isEqualTo(Pricing.Outcome.APPROVAL_REQUIRED);
        assertThat(thin.reasons()).contains("MARGIN_BELOW_MINIMUM", "DISCOUNT_ABOVE_LIMIT");

        assertThat(Pricing.evaluate(q, d("104"), Optional.of(limit)).reasons()).contains("BELOW_FLOOR");
        assertThat(Pricing.evaluate(q, d("104"), Optional.of(limit)).outcome()).isEqualTo(Pricing.Outcome.FORBIDDEN);
        assertThat(Pricing.evaluate(q, d("190"), Optional.of(limit)).reasons()).containsExactly("MARGIN_ABOVE_MAXIMUM");
        assertThat(Pricing.evaluate(q, d("99"), Optional.of(limit)).reasons()).contains("BELOW_WHOLESALE");
    }

    @Test
    void withoutDiscountPolicyAnyDiscountNeedsApproval() {
        PriceEntry entry = PriceEntry.of(AGENT.id(), new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.PER_UNIT, d("100")),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.SUGGESTED_RETAIL, d("150"), null, null, null));
        Pricing.Quote q = Pricing.quote(ALPHA_GOLD, AGENT, 1, NOW, null, List.of(activeBook(PriceScope.GLOBAL, null, entry)), List.of()).orElseThrow();
        assertThat(Pricing.evaluate(q, d("150"), Optional.empty()).outcome()).isEqualTo(Pricing.Outcome.WITHIN_POLICY);
        Pricing.Evaluation e = Pricing.evaluate(q, d("149"), Optional.empty());
        assertThat(e.outcome()).isEqualTo(Pricing.Outcome.APPROVAL_REQUIRED);
        assertThat(e.approvalPolicyKey()).isEqualTo("DISCOUNT_ABOVE_POLICY");
    }

    @Test
    void fixedRetailCannotBeChanged() {
        PriceEntry entry = PriceEntry.of(AGENT.id(), new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.PER_UNIT, d("100")),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.FIXED_RETAIL, null, d("180"), null, null));
        Pricing.Quote q = Pricing.quote(ALPHA_GOLD, AGENT, 1, NOW, null, List.of(activeBook(PriceScope.GLOBAL, null, entry)), List.of()).orElseThrow();
        assertThat(Pricing.evaluate(q, d("180"), Optional.empty()).outcome()).isEqualTo(Pricing.Outcome.WITHIN_POLICY);
        assertThat(Pricing.evaluate(q, d("170"), Optional.empty()).outcome()).isEqualTo(Pricing.Outcome.FORBIDDEN);
    }

    @Test
    void retailNeverBelowWholesale() {
        assertThatThrownBy(() -> PriceEntry.of(AGENT.id(), new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.PER_UNIT, d("100")),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.SUGGESTED_RETAIL, d("90"), null, null, null)))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("CATALOG_RETAIL_BELOW_WHOLESALE");
    }
}
