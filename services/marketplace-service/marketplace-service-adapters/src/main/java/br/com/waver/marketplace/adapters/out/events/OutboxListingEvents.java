package br.com.waver.marketplace.adapters.out.events;

import br.com.waver.events.marketplace.v1.AvailabilityChanged;
import br.com.waver.events.marketplace.v1.ListingPublished;
import br.com.waver.marketplace.application.MarketplaceService;
import br.com.waver.marketplace.domain.AvailabilityRule;
import br.com.waver.marketplace.domain.Listing;
import br.com.waver.marketplace.domain.ListingVersion;
import br.com.waver.platform.events.AuditTrail;
import br.com.waver.platform.events.EventPublisher;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ListingPublished and AvailabilityChanged on waver.marketplace.listing.v1 (RFC-0017, EVENT_CATALOG), plus the
 * audit record of every marketplace command. The other lifecycle steps are audited only for now.
 */
@Component
public class OutboxListingEvents implements MarketplaceService.Events {

    static final String TOPIC = "waver.marketplace.listing.v1";

    private final EventPublisher publisher;
    private final AuditTrail audit;

    public OutboxListingEvents(EventPublisher publisher, AuditTrail audit) {
        this.publisher = publisher;
        this.audit = audit;
    }

    @Override
    public void drafted(Listing l) {
        audit.record("marketplace.listing.create", "listing", l.id().toString(), null,
                Map.of("listingKey", l.listingKey(), "productId", l.productId().toString()));
    }

    @Override
    public void revised(Listing l, ListingVersion v) {
        audit.record("marketplace.listing.revise", "listing", l.id().toString(), null, Map.of("versionNo", Integer.toString(v.versionNo())));
    }

    @Override
    public void submitted(Listing l, ListingVersion v) {
        audit.record("marketplace.listing.submit", "listing", l.id().toString(), null, Map.of("versionNo", Integer.toString(v.versionNo())));
    }

    @Override
    public void returned(Listing l, ListingVersion v) {
        audit.record("marketplace.listing.return", "listing", l.id().toString(), null, Map.of("versionNo", Integer.toString(v.versionNo())));
    }

    /** LISTING_PUBLICATION: approved directly by WAVER Product Ops until approval-service exists, so the audit says so. */
    @Override
    public void published(Listing l, ListingVersion v) {
        ListingPublished data = ListingPublished.newBuilder()
                .setListingId(l.id())
                .setListingKey(l.listingKey())
                .setProductId(l.productId())
                .setListingVersionId(v.id())
                .setVersionNo(v.versionNo())
                .setSkuIds(v.content().skuIds().stream().map(UUID::toString).toList())
                .setApprovedBy(v.approvedBy().orElseThrow())
                .setPublishedAt(v.publishedAt().orElseThrow())
                .build();
        publisher.publish(new EventPublisher.Event(TOPIC, "waver.marketplace.ListingPublished.v1", "listing", l.id(), null, l.version(), data));
        audit.record("marketplace.listing.publish", "listing", l.id().toString(), null,
                Map.of("versionNo", Integer.toString(v.versionNo()), "approval", "DIRECT_BY_PRODUCT_OPS"));
    }

    @Override
    public void availabilityChanged(Listing l, AvailabilityRule rule) {
        AvailabilityChanged data = AvailabilityChanged.newBuilder()
                .setListingId(l.id())
                .setMode(rule.mode().name())
                .setInclude(rule.include())
                .setExclude(rule.exclude())
                .setValidFrom(rule.from().orElse(null))
                .setValidTo(rule.until().orElse(null))
                .setChangedAt(Instant.now())
                .build();
        publisher.publish(new EventPublisher.Event(TOPIC, "waver.marketplace.AvailabilityChanged.v1", "listing", l.id(), null, l.version(), data));
        audit.record("marketplace.availability.change", "listing", l.id().toString(), null,
                Map.of("mode", rule.mode().name(), "included", Integer.toString(rule.include().size()),
                        "excluded", Integer.toString(rule.exclude().size())));
    }
}
