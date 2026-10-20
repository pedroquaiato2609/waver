package br.com.waver.marketplace.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.marketplace.adapters.out.persistence.JdbcListings;
import br.com.waver.marketplace.domain.AvailabilityRule;
import br.com.waver.marketplace.domain.Listing;
import br.com.waver.marketplace.domain.ListingVersion;
import br.com.waver.marketplace.domain.ProductOffer;
import br.com.waver.marketplace.domain.ResellerRef;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.platform.tenancy.TenantRlsDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Tenancy kit for marketplace-service (ADR-0010): a reseller sees only published listings whose availability rule
 * matches it, never the rule itself nor other versions; customers see nothing. The Row-Level Security answer is
 * checked against the domain rule (AvailabilityRule.allows) for every case, so both stay in agreement.
 */
@Testcontainers
class ListingAvailabilityIsolationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("waver_marketplace")
            .withInitScript("rls-roles.sql");

    static final OrgId PLATFORM = new OrgId(Ids.newId());
    static JdbcListings listings;
    static JdbcTemplate staffJdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "marketplace_owner", "test").load().migrate();
        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser("marketplace_app");
        app.setPassword("test");
        staffJdbc = new JdbcTemplate(new TenantRlsDataSource(app));
        listings = new JdbcListings(staffJdbc);
    }

    private static TenantContext staff() {
        return new TenantContext("product-ops", TenantContext.PrincipalType.USER, PLATFORM, OrgType.PLATFORM, Set.of("WAVER_PRODUCT_OPS"));
    }

    private static TenantContext reseller(OrgId org) {
        return new TenantContext("owner-" + org, TenantContext.PrincipalType.USER, org, OrgType.RESELLER, Set.of("RESELLER_OWNER"));
    }

    /** A listing stored as WAVER Product Ops, optionally published, with an optional availability rule. */
    private static Listing listing(String key, boolean publish, AvailabilityRule rule) {
        UUID sku = Ids.newId();
        ProductOffer offer = new ProductOffer(Ids.newId(), true, Set.of(sku), Set.of(sku));
        Listing l = Listing.draft(key, offer.productId(), "Agentes", "Oferta " + key, null,
                new ListingVersion.Content(List.of(sku), null, null, null), Instant.now());
        TenantContextHolder.runAs(staff(), () -> listings.insert(l));
        long before = l.version();
        if (publish) {
            l.submit(offer, "ops", Instant.now());
            l.decide(Listing.Decision.APPROVE, offer, "ops", null, Instant.now());
        }
        if (rule != null) {
            l.setAvailability(rule);
        }
        TenantContextHolder.runAs(staff(), () -> assertThat(listings.update(l, before)).isTrue());
        return l;
    }

    private static boolean visibleTo(OrgId org, Listing l) {
        return TenantContextHolder.callAs(reseller(org), () -> listings.findById(l.id()).isPresent());
    }

    /** The database answer must be the domain answer. */
    private static void assertAgrees(OrgId org, Optional<String> tier, Listing l, boolean expected) {
        assertThat(l.availableTo(new ResellerRef(org, tier), Instant.now())).as("domain rule").isEqualTo(expected);
        assertThat(visibleTo(org, l)).as("row-level security").isEqualTo(expected);
    }

    @Test
    void availabilityModesDecideWhoSeesAListing() {
        OrgId alpha = new OrgId(Ids.newId());
        OrgId norte = new OrgId(Ids.newId());
        OrgId gold = new OrgId(Ids.newId());
        TenantContextHolder.runAs(staff(), () -> staffJdbc.update("insert into reseller_tier (org_id, tier_key) values (?, 'GOLD')", gold.value()));

        Listing all = listing("iso-all", true, AvailabilityRule.of(AvailabilityRule.Mode.ALL_RESELLERS, null,
                List.of(norte.value().toString()), null, null));
        Listing onlyNorte = listing("iso-norte", true, AvailabilityRule.of(AvailabilityRule.Mode.RESELLERS,
                List.of(norte.value().toString()), null, null, null));
        Listing goldTier = listing("iso-gold", true, AvailabilityRule.of(AvailabilityRule.Mode.TIERS, List.of("GOLD"), null, null, null));
        Listing noRule = listing("iso-no-rule", true, null);
        Listing draft = listing("iso-draft", false, AvailabilityRule.of(AvailabilityRule.Mode.ALL_RESELLERS, null, null, null, null));

        assertAgrees(alpha, Optional.empty(), all, true);
        assertAgrees(norte, Optional.empty(), all, false);
        assertAgrees(alpha, Optional.empty(), onlyNorte, false);
        assertAgrees(norte, Optional.empty(), onlyNorte, true);
        assertAgrees(gold, Optional.of("GOLD"), goldTier, true);
        assertAgrees(alpha, Optional.empty(), goldTier, false);
        assertAgrees(alpha, Optional.empty(), noRule, false);
        assertAgrees(alpha, Optional.empty(), draft, false);

        TenantContextHolder.runAs(reseller(alpha), () -> assertThat(listings.list(null, null, 200)).extracting(Listing::id)
                .contains(all.id()).doesNotContain(onlyNorte.id(), goldTier.id(), noRule.id(), draft.id()));
    }

    @Test
    void resellersNeverReadTheRulesNorOtherVersions() {
        OrgId alpha = new OrgId(Ids.newId());
        Listing l = listing("iso-versions", true, AvailabilityRule.of(AvailabilityRule.Mode.ALL_RESELLERS, null, null, null, null));
        UUID sku = l.currentVersion().orElseThrow().content().skuIds().getFirst();
        long before = l.version();
        l.revise(new ListingVersion.Content(List.of(sku), "Rascunho interno", null, null));
        TenantContextHolder.runAs(staff(), () -> assertThat(listings.update(l, before)).isTrue());

        TenantContextHolder.runAs(reseller(alpha), () -> {
            Listing seen = listings.findById(l.id()).orElseThrow();
            assertThat(seen.availability()).as("the rule names other resellers").isEmpty();
            assertThat(seen.versions()).as("only the current version").extracting(ListingVersion::versionNo).containsExactly(1);
            assertThat(staffJdbc.queryForObject("select count(*) from availability_rule", Long.class)).isZero();
        });
    }

    @Test
    void customersSeeNoMarketplaceAndResellersCannotWrite() {
        Listing l = listing("iso-customer", true, AvailabilityRule.of(AvailabilityRule.Mode.ALL_RESELLERS, null, null, null, null));
        TenantContext customer = new TenantContext("admin", TenantContext.PrincipalType.USER, new OrgId(Ids.newId()), OrgType.CUSTOMER,
                Set.of("CUSTOMER_ADMIN"));
        TenantContextHolder.runAs(customer, () -> assertThat(listings.findById(l.id())).isEmpty());

        Listing mine = Listing.draft("iso-reseller-own", Ids.newId(), "Agentes", "Oferta própria", null,
                new ListingVersion.Content(List.of(), null, null, null), Instant.now());
        assertThatThrownBy(() -> TenantContextHolder.runAs(reseller(new OrgId(Ids.newId())), () -> listings.insert(mine)))
                .rootCause().hasMessageContaining("row-level security");
    }
}
