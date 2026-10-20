package br.com.waver.catalog.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.catalog.adapters.out.persistence.JdbcPriceBooks;
import br.com.waver.catalog.adapters.out.persistence.JdbcPricingPolicies;
import br.com.waver.catalog.adapters.out.persistence.JdbcProducts;
import br.com.waver.catalog.domain.PolicyTarget;
import br.com.waver.catalog.domain.PriceBook;
import br.com.waver.catalog.domain.PriceEntry;
import br.com.waver.catalog.domain.PriceScope;
import br.com.waver.catalog.domain.PricingRule;
import br.com.waver.catalog.domain.Product;
import br.com.waver.catalog.domain.Sku;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.platform.tenancy.TenantRlsDataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Tenancy kit for catalog-service (ADR-0010): WAVER staff manage the catalog; a reseller sees published products
 * and the books of its own scopes only (never another reseller's book, never drafts); customers see nothing.
 */
@Testcontainers
class CatalogIsolationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("waver_catalog")
            .withInitScript("rls-roles.sql");

    static final OrgId PLATFORM = new OrgId(Ids.newId());
    static JdbcProducts products;
    static JdbcPriceBooks books;
    static JdbcPricingPolicies policies;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "catalog_owner", "test").load().migrate();
        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser("catalog_app");
        app.setPassword("test");
        JdbcTemplate jdbc = new JdbcTemplate(new TenantRlsDataSource(app));
        products = new JdbcProducts(jdbc);
        books = new JdbcPriceBooks(jdbc);
        policies = new JdbcPricingPolicies(jdbc);
    }

    private static TenantContext staff() {
        return new TenantContext("product-ops", TenantContext.PrincipalType.USER, PLATFORM, OrgType.PLATFORM, Set.of("PLATFORM_SUPER_ADMIN"));
    }

    private static TenantContext reseller(OrgId org) {
        return new TenantContext("owner-" + org, TenantContext.PrincipalType.USER, org, OrgType.RESELLER, Set.of("RESELLER_OWNER"));
    }

    private static TenantContext customer(OrgId org) {
        return new TenantContext("admin-" + org, TenantContext.PrincipalType.USER, org, OrgType.CUSTOMER, Set.of("CUSTOMER_ADMIN"));
    }

    private static Product product(String key, boolean publish) {
        Product p = Product.create(key, "Produto " + key, Product.ItemType.PRODUCT, null, Instant.now());
        Sku sku = p.addSku(key.toUpperCase() + "-M", Sku.Unit.WORKSPACE, Sku.ChargeType.RECURRING, Sku.BillingPeriod.MONTHLY, null);
        TenantContextHolder.runAs(staff(), () -> {
            products.insert(p);
            products.insertSku(sku);
        });
        if (publish) {
            long before = p.version();
            p.publish(Instant.now());
            TenantContextHolder.runAs(staff(), () -> assertThat(products.update(p, before)).isTrue());
        }
        return p;
    }

    private static PriceBook book(PriceScope scope, String ref, Sku sku, boolean activate) {
        PriceBook b = PriceBook.draft("Tabela " + scope, "BRL", scope, ref, null, null, Instant.now());
        b.putEntry(PriceEntry.of(sku.id(), new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.PER_UNIT, new BigDecimal("100")),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.WHOLESALE_PLUS_MARGIN, null, null, null, null)));
        TenantContextHolder.runAs(staff(), () -> {
            books.insert(b);
            if (activate) {
                long before = b.version();
                b.activate("commercial", books.findActive(scope, ref, b.currency()), Instant.now());
                assertThat(books.update(b, before)).isTrue();
            }
        });
        return b;
    }

    @Test
    void resellersSeePublishedProductsOnlyAndCustomersNothing() {
        Product published = product("iso-published", true);
        Product draft = product("iso-draft", false);
        OrgId alpha = new OrgId(Ids.newId());

        TenantContextHolder.runAs(reseller(alpha), () -> {
            assertThat(products.findById(published.id())).isPresent();
            assertThat(products.findById(draft.id())).as("draft products are WAVER-internal").isEmpty();
            assertThat(products.findSkus(List.of(draft.skus().getFirst().id()))).isEmpty();
        });
        TenantContextHolder.runAs(customer(new OrgId(Ids.newId())), () -> {
            assertThat(products.findById(published.id())).as("customers never see the catalog").isEmpty();
            assertThat(products.findSkus(List.of(published.skus().getFirst().id()))).isEmpty();
        });
    }

    @Test
    void eachResellerSeesOnlyTheBooksOfItsOwnScopes() {
        Sku sku = product("iso-books", true).skus().getFirst();
        OrgId alpha = new OrgId(Ids.newId());
        OrgId norte = new OrgId(Ids.newId());
        PriceBook global = book(PriceScope.GLOBAL, null, sku, true);
        PriceBook forNorte = book(PriceScope.RESELLER, norte.value().toString(), sku, true);
        PriceBook draftForAlpha = book(PriceScope.RESELLER, alpha.value().toString(), sku, false);

        TenantContextHolder.runAs(reseller(alpha), () -> {
            assertThat(books.findActivePricing(List.of(sku.id()))).extracting(PriceBook::id).contains(global.id())
                    .doesNotContain(forNorte.id(), draftForAlpha.id());
            assertThat(books.findById(forNorte.id())).as("another reseller's prices are invisible").isEmpty();
            assertThat(books.findById(draftForAlpha.id())).as("drafts are WAVER-internal").isEmpty();
        });
        TenantContextHolder.runAs(reseller(norte), () ->
                assertThat(books.findById(forNorte.id()).orElseThrow().entries()).hasSize(1));
        TenantContextHolder.runAs(customer(new OrgId(Ids.newId())), () ->
                assertThat(books.findById(global.id())).as("wholesale prices never reach customers").isEmpty());
    }

    @Test
    void resellerPricingRulesStayWithTheirReseller() {
        OrgId alpha = new OrgId(Ids.newId());
        OrgId norte = new OrgId(Ids.newId());
        PricingRule forNorte = PricingRule.define(PolicyTarget.of(PriceScope.RESELLER, norte.value().toString(), null, null, null,
                Instant.now()), new BigDecimal("15"), null, Instant.now());
        TenantContextHolder.runAs(staff(), () -> policies.insert(forNorte));
        TenantContextHolder.runAs(reseller(alpha), () ->
                assertThat(policies.rules()).extracting(PricingRule::id).doesNotContain(forNorte.id()));
        TenantContextHolder.runAs(reseller(norte), () ->
                assertThat(policies.rules()).extracting(PricingRule::id).contains(forNorte.id()));
    }

    @Test
    void resellersCannotWriteTheCatalog() {
        Product p = Product.create("iso-forbidden", "Proibido", Product.ItemType.PRODUCT, null, Instant.now());
        assertThatThrownBy(() -> TenantContextHolder.runAs(reseller(new OrgId(Ids.newId())), () -> products.insert(p)))
                .rootCause().hasMessageContaining("row-level security");
        PriceBook b = PriceBook.draft("Tabela da revenda", "BRL", PriceScope.GLOBAL, null, null, null, Instant.now());
        assertThatThrownBy(() -> TenantContextHolder.runAs(reseller(new OrgId(Ids.newId())), () -> books.insert(b)))
                .rootCause().hasMessageContaining("row-level security");
    }
}
