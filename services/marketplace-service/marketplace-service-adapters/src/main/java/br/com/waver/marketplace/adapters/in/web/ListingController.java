package br.com.waver.marketplace.adapters.in.web;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.marketplace.application.MarketplaceService;
import br.com.waver.marketplace.domain.AvailabilityRule;
import br.com.waver.marketplace.domain.Listing;
import br.com.waver.marketplace.domain.ListingVersion;
import br.com.waver.platform.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service API (RFC-0017 §5): /v1/listings. WAVER staff get the full listing; a reseller gets an offer
 * view without the availability rule, review data or versions other than the current one.
 */
@RestController
@RequestMapping("/v1/listings")
public class ListingController {

    public record VersionInput(List<UUID> skuIds, String requirements, String docsRef, String supportPolicy) {
        ListingVersion.Content content() {
            return new ListingVersion.Content(skuIds, requirements, docsRef, supportPolicy);
        }
    }

    public record CreateRequest(String listingKey, @NotNull UUID productId, String category, String title, String summary,
                                List<UUID> skuIds, String requirements, String docsRef, String supportPolicy) {
    }

    public record DecisionRequest(@NotNull Listing.Decision decision, String reason) {
    }

    public record AvailabilityRequest(@NotNull AvailabilityRule.Mode mode, List<String> include, List<String> exclude,
                                      Instant validFrom, Instant validTo) {
    }

    public record VersionView(UUID id, int versionNo, List<UUID> skuIds, String requirements, String docsRef, String supportPolicy,
                              String status, Instant submittedAt, String submittedBy, Instant publishedAt, String approvedBy,
                              String returnReason) {
        static VersionView from(ListingVersion v) {
            ListingVersion.Content c = v.content();
            return new VersionView(v.id(), v.versionNo(), c.skuIds(), c.requirements(), c.docsRef(), c.supportPolicy(), v.status().name(),
                    v.submittedAt().orElse(null), v.submittedBy().orElse(null), v.publishedAt().orElse(null), v.approvedBy().orElse(null),
                    v.returnReason().orElse(null));
        }
    }

    public record AvailabilityView(String mode, List<String> include, List<String> exclude, Instant validFrom, Instant validTo) {
        static AvailabilityView from(AvailabilityRule a) {
            return new AvailabilityView(a.mode().name(), a.include(), a.exclude(), a.validFrom(), a.validTo());
        }
    }

    public record ListingView(UUID id, String listingKey, UUID productId, String category, String title, String summary, String status,
                              VersionView currentVersion, VersionView openVersion, List<VersionView> versions,
                              AvailabilityView availability, long version, Instant createdAt) {
        static ListingView from(Listing l) {
            return new ListingView(l.id(), l.listingKey(), l.productId(), l.category(), l.title(), l.summary().orElse(null), l.status().name(),
                    l.currentVersion().map(VersionView::from).orElse(null), l.openVersion().map(VersionView::from).orElse(null),
                    l.versions().stream().map(VersionView::from).toList(),
                    l.availability().map(AvailabilityView::from).orElse(null), l.version(), l.createdAt());
        }
    }

    /** What a reseller sees: the current published offer only. */
    public record OfferVersionView(UUID id, int versionNo, List<UUID> skuIds, String requirements, String docsRef, String supportPolicy,
                                   Instant publishedAt) {
    }

    public record ResellerListingView(UUID id, String listingKey, UUID productId, String category, String title, String summary,
                                      OfferVersionView currentVersion) {
        static ResellerListingView from(Listing l) {
            ListingVersion v = l.currentVersion().orElseThrow();
            ListingVersion.Content c = v.content();
            return new ResellerListingView(l.id(), l.listingKey(), l.productId(), l.category(), l.title(), l.summary().orElse(null),
                    new OfferVersionView(v.id(), v.versionNo(), c.skuIds(), c.requirements(), c.docsRef(), c.supportPolicy(),
                            v.publishedAt().orElse(null)));
        }
    }

    public record AvailabilityCheck(UUID listingId, UUID resellerOrgId, boolean available) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    private final MarketplaceService marketplace;

    public ListingController(MarketplaceService marketplace) {
        this.marketplace = marketplace;
    }

    @PostMapping
    public ResponseEntity<ListingView> create(@Valid @RequestBody CreateRequest r) {
        Listing l = marketplace.create(TenantContextHolder.require(), new MarketplaceService.CreateListing(r.listingKey(), r.productId(),
                r.category(), r.title(), r.summary(), new ListingVersion.Content(r.skuIds(), r.requirements(), r.docsRef(), r.supportPolicy())));
        return ResponseEntity.created(URI.create("/v1/listings/" + l.id())).body(ListingView.from(l));
    }

    /** {@code availableTo=me}: a reseller's own offer list (RFC-0017 §5). Resellers always get only that. */
    @GetMapping
    public Page<?> list(@RequestParam(required = false) Listing.Status status, @RequestParam(required = false) String availableTo,
                        @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) UUID cursor) {
        if (availableTo != null && !"me".equals(availableTo)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "MARKETPLACE_AVAILABLE_TO_INVALID", "Only availableTo=me is supported.");
        }
        TenantContext actor = TenantContextHolder.require();
        List<Listing> items = marketplace.list(actor, status, availableTo != null, cursor, limit);
        String next = items.size() == Math.clamp(limit, 1, 200) ? items.getLast().id().toString() : null;
        if (actor.orgType() == OrgType.RESELLER) {
            return new Page<>(items.stream().map(ResellerListingView::from).toList(), next);
        }
        return new Page<>(items.stream().map(ListingView::from).toList(), next);
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable UUID id) {
        TenantContext actor = TenantContextHolder.require();
        Listing l = marketplace.get(actor, id);
        return actor.orgType() == OrgType.RESELLER ? ResellerListingView.from(l) : ListingView.from(l);
    }

    @PostMapping("/{id}/versions")
    public ListingView revise(@PathVariable UUID id, @RequestBody VersionInput r) {
        return ListingView.from(marketplace.revise(TenantContextHolder.require(), id, r.content()));
    }

    @PostMapping("/{id}/submission")
    public ListingView submit(@PathVariable UUID id) {
        return ListingView.from(marketplace.submit(TenantContextHolder.require(), id));
    }

    @PostMapping("/{id}/review-decision")
    public ListingView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return ListingView.from(marketplace.decide(TenantContextHolder.require(), id, r.decision(), r.reason()));
    }

    @PutMapping("/{id}/availability-rules")
    public ListingView availability(@PathVariable UUID id, @Valid @RequestBody AvailabilityRequest r) {
        return ListingView.from(marketplace.setAvailability(TenantContextHolder.require(), id,
                new MarketplaceService.SetAvailability(r.mode(), r.include(), r.exclude(), r.validFrom(), r.validTo())));
    }

    @GetMapping("/{id}/availability")
    public AvailabilityCheck availableTo(@PathVariable UUID id, @RequestParam UUID resellerOrgId) {
        return new AvailabilityCheck(id, resellerOrgId, marketplace.availableTo(TenantContextHolder.require(), id, resellerOrgId));
    }
}
