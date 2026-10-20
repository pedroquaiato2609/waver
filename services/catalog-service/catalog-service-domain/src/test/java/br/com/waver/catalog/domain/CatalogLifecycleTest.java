package br.com.waver.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    private static String code(Throwable e) {
        return ((DomainException) e).code();
    }

    @Test
    void productIsPublishedOnlyWithSkus() {
        Product p = Product.create("waver-agents", "WAVER Agentes", Product.ItemType.PRODUCT, null, NOW);
        assertThatThrownBy(() -> p.publish(NOW)).extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_PRODUCT_WITHOUT_SKU");
        p.addSku("wv-agent-monthly", Sku.Unit.WORKSPACE, Sku.ChargeType.RECURRING, Sku.BillingPeriod.MONTHLY, null);
        p.publish(NOW);
        assertThat(p.status()).isEqualTo(Product.Status.PUBLISHED);
        assertThat(p.skus().getFirst().skuCode()).isEqualTo("WV-AGENT-MONTHLY");
        assertThatThrownBy(() -> p.addSku("WV-EXTRA", Sku.Unit.SEAT, Sku.ChargeType.ONE_TIME, null, null))
                .extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_PRODUCT_NOT_DRAFT");
    }

    @Test
    void skuShapeFollowsItsChargeType() {
        assertThatThrownBy(() -> Sku.define(Ids.newId(), "WV-RUNS", Sku.Unit.RUN, Sku.ChargeType.USAGE, null, null))
                .extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_SKU_METER_REQUIRED");
        assertThatThrownBy(() -> Sku.define(Ids.newId(), "WV-SEAT", Sku.Unit.SEAT, Sku.ChargeType.RECURRING, null, null))
                .extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_SKU_PERIOD_REQUIRED");
        assertThat(Sku.define(Ids.newId(), "WV-RUNS", Sku.Unit.RUN, Sku.ChargeType.USAGE, null, "agent.runs").meter()).contains("agent.runs");
    }

    @Test
    void activationRetiresTheBookOfTheSameScopeAndFreezesPrices() {
        Sku sku = Sku.define(Ids.newId(), "WV-AGENT-MONTHLY", Sku.Unit.WORKSPACE, Sku.ChargeType.RECURRING, Sku.BillingPeriod.MONTHLY, null);
        PriceEntry entry = PriceEntry.of(sku.id(), new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.PER_UNIT, new BigDecimal("100")),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.WHOLESALE_PLUS_MARGIN, null, null, null, null));

        PriceBook first = PriceBook.draft("Tabela 2026", "BRL", PriceScope.GLOBAL, null, null, null, NOW);
        assertThatThrownBy(() -> first.activate("c", Optional.empty(), NOW)).extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_PRICE_BOOK_EMPTY");
        first.putEntry(entry);
        assertThat(first.activate("c", Optional.empty(), NOW)).isEmpty();
        assertThatThrownBy(() -> first.putEntry(entry)).extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_PRICE_BOOK_NOT_DRAFT");

        PriceBook second = PriceBook.draft("Tabela 2027", "BRL", PriceScope.GLOBAL, null, null, null, NOW);
        second.putEntry(entry);
        assertThat(second.activate("c", Optional.of(first), NOW.plusSeconds(1))).contains(first.id());
        assertThat(first.status()).isEqualTo(PriceBook.Status.RETIRED);
        assertThat(second.status()).isEqualTo(PriceBook.Status.ACTIVE);
    }

    @Test
    void scopeReferenceMustFitTheScope() {
        assertThatThrownBy(() -> PriceBook.draft("Tabela", "BRL", PriceScope.RESELLER, "not-a-uuid", null, null, NOW))
                .extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_SCOPE_INVALID");
        assertThatThrownBy(() -> PriceBook.draft("Tabela", "BRL", PriceScope.GLOBAL, "GOLD", null, null, NOW))
                .extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_SCOPE_INVALID");
        assertThatThrownBy(() -> PricingRule.define(PolicyTarget.of(PriceScope.GLOBAL, null, null, null, null, NOW),
                new BigDecimal("30"), new BigDecimal("20"), NOW)).extracting(CatalogLifecycleTest::code).isEqualTo("CATALOG_MARGIN_BAND_INVALID");
    }
}
