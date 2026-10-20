package br.com.waver.marketplace.application;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.marketplace.domain.AvailabilityRule;
import br.com.waver.marketplace.domain.Listing;
import br.com.waver.marketplace.domain.ListingVersion;
import br.com.waver.marketplace.domain.Listings;
import br.com.waver.marketplace.domain.ProductOffer;
import br.com.waver.marketplace.domain.ResellerRef;
import br.com.waver.marketplace.domain.ResellerTiers;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Marketplace use cases (RFC-0017 §5). WAVER Product Ops (marketplace.*, availability.*) drafts listings,
 * submits them, approves their publication (directly until approval-service has LISTING_PUBLICATION) and sets
 * who may sell them. Resellers read the listings available to them. Each change stores the listing, its event
 * and its audit record in one transaction.
 */
public class MarketplaceService {

    private static final Set<String> PRODUCT_OPS = Set.of("PLATFORM_SUPER_ADMIN", "WAVER_PRODUCT_OPS");

    public record CreateListing(String listingKey, UUID productId, String category, String title, String summary,
                                ListingVersion.Content content) {
    }

    public record SetAvailability(AvailabilityRule.Mode mode, List<String> include, List<String> exclude, Instant validFrom, Instant validTo) {
    }

    public interface Events {
        void drafted(Listing listing);

        void revised(Listing listing, ListingVersion version);

        void submitted(Listing listing, ListingVersion version);

        void returned(Listing listing, ListingVersion version);

        void published(Listing listing, ListingVersion version);

        void availabilityChanged(Listing listing, AvailabilityRule rule);
    }

    /** catalog-service, called with the Product Ops user's own token. */
    public interface CatalogDirectory {
        ProductOffer offer(UUID productId);
    }

    private final Listings listings;
    private final ResellerTiers tiers;
    private final CatalogDirectory catalog;
    private final Events events;
    private final TransactionOperations tx;
    private final Clock clock;

    public MarketplaceService(Listings listings, ResellerTiers tiers, CatalogDirectory catalog, Events events,
                              TransactionOperations tx, Clock clock) {
        this.listings = listings;
        this.tiers = tiers;
        this.catalog = catalog;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public Listing create(TenantContext actor, CreateListing c) {
        requireProductOps(actor);
        Listing listing = Listing.draft(c.listingKey(), c.productId(), c.category(), c.title(), c.summary(), c.content(), clock.instant());
        catalog.offer(listing.productId());   // the product must exist; publication checks the rest
        return tx.execute(s -> {
            if (listings.existsByKey(listing.listingKey())) {
                throw new DomainException(DomainException.Kind.CONFLICT, "MARKETPLACE_LISTING_KEY_EXISTS", "A listing with this key already exists.");
            }
            listings.insert(listing);
            events.drafted(listing);
            return listing;
        });
    }

    public Listing revise(TenantContext actor, UUID id, ListingVersion.Content content) {
        requireProductOps(actor);
        return change(id, l -> {
            ListingVersion v = l.revise(content);
            events.revised(l, v);
        });
    }

    public Listing submit(TenantContext actor, UUID id) {
        requireProductOps(actor);
        ProductOffer offer = catalog.offer(productOf(id));
        return change(id, l -> events.submitted(l, l.submit(offer, actor.principalId(), clock.instant())));
    }

    /** Direct decision by WAVER Product Ops until approval-service (LISTING_PUBLICATION) is in place. */
    public Listing decide(TenantContext actor, UUID id, Listing.Decision decision, String reason) {
        requireProductOps(actor);
        ProductOffer offer = decision == Listing.Decision.APPROVE ? catalog.offer(productOf(id)) : null;
        return change(id, l -> {
            ListingVersion v = l.decide(decision, offer, actor.principalId(), reason, clock.instant());
            if (decision == Listing.Decision.APPROVE) {
                events.published(l, v);
            } else {
                events.returned(l, v);
            }
        });
    }

    public Listing setAvailability(TenantContext actor, UUID id, SetAvailability c) {
        requireProductOps(actor);
        AvailabilityRule rule = AvailabilityRule.of(c.mode(), c.include(), c.exclude(), c.validFrom(), c.validTo());
        return change(id, l -> {
            l.setAvailability(rule);
            events.availabilityChanged(l, rule);
        });
    }

    public Listing get(TenantContext actor, UUID id) {
        requireReader(actor);
        return tx.execute(s -> load(id));
    }

    /**
     * WAVER staff see every listing (optionally by status); a reseller sees what it may sell now. For a reseller the
     * filter is Row-Level Security itself (listing_available_to_caller), so it cannot be bypassed by a query; the
     * reseller cannot read the rules, only their result.
     */
    public List<Listing> list(TenantContext actor, Listing.Status status, boolean availableToMe, UUID afterId, int limit) {
        requireReader(actor);
        int size = Math.clamp(limit, 1, 200);
        if (actor.orgType() != OrgType.RESELLER) {
            if (availableToMe) {
                throw new DomainException(DomainException.Kind.VALIDATION, "MARKETPLACE_AVAILABLE_TO_RESELLERS_ONLY",
                        "availableTo=me is for resellers. Use the availability check for a given reseller.");
            }
            return tx.execute(s -> listings.list(status, afterId, size));
        }
        return tx.execute(s -> listings.list(Listing.Status.PUBLISHED, afterId, size));
    }

    /** WAVER staff: would this reseller see the listing now? Uses the same rule as the reseller's own reads. */
    public boolean availableTo(TenantContext actor, UUID id, UUID resellerOrgId) {
        if (!actor.isPlatformStaff()) {
            throw forbidden();
        }
        if (resellerOrgId == null) {
            throw new DomainException(DomainException.Kind.VALIDATION, "MARKETPLACE_RESELLER_REQUIRED", "Inform the reseller organization.");
        }
        OrgId org = new OrgId(resellerOrgId);
        return tx.execute(s -> load(id).availableTo(new ResellerRef(org, tiers.tierOf(org)), clock.instant()));
    }

    // ---- helpers ----

    private UUID productOf(UUID id) {
        return tx.execute(s -> load(id).productId());
    }

    private Listing change(UUID id, java.util.function.Consumer<Listing> step) {
        return tx.execute(s -> {
            Listing listing = load(id);
            long before = listing.version();
            step.accept(listing);
            if (!listings.update(listing, before)) {
                throw new DomainException(DomainException.Kind.CONFLICT, "MARKETPLACE_CONCURRENT_UPDATE",
                        "The listing changed meanwhile. Reload and try again.");
            }
            return listing;
        });
    }

    private Listing load(UUID id) {
        return listings.findById(id).orElseThrow(() ->
                new DomainException(DomainException.Kind.NOT_FOUND, "MARKETPLACE_LISTING_NOT_FOUND", "Listing not found."));
    }

    private static void requireProductOps(TenantContext actor) {
        if (actor.orgType() != OrgType.PLATFORM || PRODUCT_OPS.stream().noneMatch(actor::hasRole)) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "MARKETPLACE_COMMAND_FORBIDDEN",
                    "Only WAVER Product Ops can manage the marketplace.");
        }
    }

    /** WAVER staff and resellers; never customers (RFC-0017 §1: the customer never sees a marketplace). */
    private static void requireReader(TenantContext actor) {
        if (actor.orgType() != OrgType.RESELLER && !actor.isPlatformStaff()) {
            throw forbidden();
        }
    }

    private static DomainException forbidden() {
        return new DomainException(DomainException.Kind.FORBIDDEN, "MARKETPLACE_READ_FORBIDDEN", "You cannot see the marketplace.");
    }
}
