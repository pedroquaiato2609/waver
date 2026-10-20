package br.com.waver.marketplace.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID PRODUCT = Ids.newId();
    private static final UUID SKU = Ids.newId();
    private static final ProductOffer PRICED = new ProductOffer(PRODUCT, true, Set.of(SKU), Set.of(SKU));

    private static String code(Throwable e) {
        return ((DomainException) e).code();
    }

    private static Listing draft() {
        return Listing.draft("waver-agentes", PRODUCT, "Agentes", "WAVER Agentes", "Agentes de IA para atendimento.",
                new ListingVersion.Content(List.of(SKU), null, null, null), NOW);
    }

    @Test
    void draftReviewPublish() {
        Listing l = draft();
        assertThat(l.status()).isEqualTo(Listing.Status.DRAFT);
        l.submit(PRICED, "product-ops", NOW);
        assertThat(l.status()).isEqualTo(Listing.Status.REVIEW);
        ListingVersion v = l.decide(Listing.Decision.APPROVE, PRICED, "product-ops", null, NOW);
        assertThat(l.status()).isEqualTo(Listing.Status.PUBLISHED);
        assertThat(l.currentVersion()).contains(v);
        assertThat(v.approvedBy()).contains("product-ops");
        assertThat(l.openVersion()).isEmpty();
    }

    @Test
    void publicationNeedsAPublishedPricedProduct() {
        Listing l = draft();
        assertThatThrownBy(() -> l.submit(new ProductOffer(PRODUCT, false, Set.of(SKU), Set.of(SKU)), "ops", NOW))
                .extracting(ListingTest::code).isEqualTo("MARKETPLACE_PRODUCT_NOT_PUBLISHED");
        assertThatThrownBy(() -> l.submit(new ProductOffer(PRODUCT, true, Set.of(SKU), Set.of()), "ops", NOW))
                .extracting(ListingTest::code).isEqualTo("MARKETPLACE_SKU_WITHOUT_PRICE");
        assertThatThrownBy(() -> l.submit(new ProductOffer(PRODUCT, true, Set.of(Ids.newId()), Set.of()), "ops", NOW))
                .extracting(ListingTest::code).isEqualTo("MARKETPLACE_SKU_NOT_IN_PRODUCT");
        l.submit(PRICED, "ops", NOW);
        assertThatThrownBy(() -> l.decide(Listing.Decision.APPROVE, new ProductOffer(PRODUCT, true, Set.of(SKU), Set.of()), "ops", null, NOW))
                .as("prices are checked again at approval").extracting(ListingTest::code).isEqualTo("MARKETPLACE_SKU_WITHOUT_PRICE");
    }

    @Test
    void returnedWithReasonAndNewVersionsKeepTheListingPublished() {
        Listing l = draft();
        l.submit(PRICED, "ops", NOW);
        assertThatThrownBy(() -> l.decide(Listing.Decision.RETURN, null, "ops", "", NOW))
                .extracting(ListingTest::code).isEqualTo("MARKETPLACE_RETURN_REASON_REQUIRED");
        l.decide(Listing.Decision.RETURN, null, "ops", "Descreva os requisitos.", NOW);
        assertThat(l.status()).isEqualTo(Listing.Status.DRAFT);
        assertThat(l.openVersion().orElseThrow().returnReason()).contains("Descreva os requisitos.");

        l.submit(PRICED, "ops", NOW);
        ListingVersion first = l.decide(Listing.Decision.APPROVE, PRICED, "ops", null, NOW);
        ListingVersion second = l.revise(new ListingVersion.Content(List.of(SKU), "Workspace ativo", null, null));
        assertThat(second.versionNo()).isEqualTo(2);
        l.submit(PRICED, "ops", NOW);
        assertThat(l.status()).as("a version in review does not unpublish the listing").isEqualTo(Listing.Status.PUBLISHED);
        assertThatThrownBy(() -> l.revise(new ListingVersion.Content(List.of(SKU), null, null, null)))
                .extracting(ListingTest::code).isEqualTo("MARKETPLACE_VERSION_IN_REVIEW");
        l.decide(Listing.Decision.APPROVE, PRICED, "ops", null, NOW);
        assertThat(first.status()).isEqualTo(ListingVersion.Status.SUPERSEDED);
        assertThat(l.currentVersion()).contains(second);
    }

    @Test
    void availabilityRuleModes() {
        OrgId alpha = new OrgId(Ids.newId());
        OrgId norte = new OrgId(Ids.newId());
        ResellerRef alphaGold = new ResellerRef(alpha, Optional.of("GOLD"));
        ResellerRef norteNoTier = new ResellerRef(norte, Optional.empty());

        AvailabilityRule all = AvailabilityRule.of(AvailabilityRule.Mode.ALL_RESELLERS, null, List.of(norte.value().toString()), null, null);
        assertThat(all.allows(alphaGold, NOW)).isTrue();
        assertThat(all.allows(norteNoTier, NOW)).as("excluded").isFalse();

        AvailabilityRule gold = AvailabilityRule.of(AvailabilityRule.Mode.TIERS, List.of("GOLD"), null, null, null);
        assertThat(gold.allows(alphaGold, NOW)).isTrue();
        assertThat(gold.allows(norteNoTier, NOW)).isFalse();

        AvailabilityRule named = AvailabilityRule.of(AvailabilityRule.Mode.RESELLERS, List.of(norte.value().toString()), null, null, null);
        assertThat(named.allows(norteNoTier, NOW)).isTrue();
        assertThat(named.allows(alphaGold, NOW)).isFalse();

        AvailabilityRule later = AvailabilityRule.of(AvailabilityRule.Mode.ALL_RESELLERS, null, null, NOW.plusSeconds(60), null);
        assertThat(later.allows(alphaGold, NOW)).isFalse();

        assertThatThrownBy(() -> AvailabilityRule.of(AvailabilityRule.Mode.RESELLERS, List.of("revenda-alpha"), null, null, null))
                .extracting(ListingTest::code).isEqualTo("MARKETPLACE_AVAILABILITY_RESELLER_INVALID");
    }

    @Test
    void onlyPublishedListingsAreAvailable() {
        Listing l = draft();
        l.setAvailability(AvailabilityRule.of(AvailabilityRule.Mode.ALL_RESELLERS, null, null, null, null));
        ResellerRef any = new ResellerRef(new OrgId(Ids.newId()), Optional.empty());
        assertThat(l.availableTo(any, NOW)).isFalse();
        l.submit(PRICED, "ops", NOW);
        l.decide(Listing.Decision.APPROVE, PRICED, "ops", null, NOW);
        assertThat(l.availableTo(any, NOW)).isTrue();
    }
}
