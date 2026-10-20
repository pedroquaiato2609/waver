package br.com.waver.catalog.adapters.out.events;

import br.com.waver.catalog.application.CatalogService;
import br.com.waver.catalog.domain.DiscountPolicy;
import br.com.waver.catalog.domain.PriceBook;
import br.com.waver.catalog.domain.PriceEntry;
import br.com.waver.catalog.domain.PricingRule;
import br.com.waver.catalog.domain.Product;
import br.com.waver.catalog.domain.Sku;
import br.com.waver.events.catalog.v1.PriceBookActivated;
import br.com.waver.events.catalog.v1.ProductPublished;
import br.com.waver.platform.events.AuditTrail;
import br.com.waver.platform.events.EventPublisher;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ProductPublished on waver.catalog.product.v1 and PriceBookActivated on waver.catalog.price-book.v1 (RFC-0017,
 * EVENT_CATALOG), plus the audit record of every catalog write. IDs and keys only, never amounts.
 */
@Component
public class OutboxCatalogEvents implements CatalogService.Events {

    static final String PRODUCT_TOPIC = "waver.catalog.product.v1";
    static final String PRICE_BOOK_TOPIC = "waver.catalog.price-book.v1";

    private final EventPublisher publisher;
    private final AuditTrail audit;

    public OutboxCatalogEvents(EventPublisher publisher, AuditTrail audit) {
        this.publisher = publisher;
        this.audit = audit;
    }

    @Override
    public void productCreated(Product p) {
        audit.record("catalog.product.create", "product", p.id().toString(), null,
                Map.of("productKey", p.productKey(), "itemType", p.itemType().name()));
    }

    @Override
    public void skuAdded(Product p, Sku s) {
        audit.record("catalog.sku.create", "sku", s.id().toString(), null,
                Map.of("productId", p.id().toString(), "skuCode", s.skuCode(), "chargeType", s.chargeType().name()));
    }

    @Override
    public void productPublished(Product p) {
        ProductPublished data = ProductPublished.newBuilder()
                .setProductId(p.id())
                .setProductKey(p.productKey())
                .setItemType(p.itemType().name())
                .setTechnicalRef(p.technicalRef().orElse(null))
                .setSkuIds(p.skus().stream().map(s -> s.id().toString()).toList())
                .setPublishedAt(p.publishedAt().orElseThrow())
                .build();
        publisher.publish(new EventPublisher.Event(PRODUCT_TOPIC, "waver.catalog.ProductPublished.v1", "product", p.id(), null,
                p.version(), data));
        audit.record("catalog.product.publish", "product", p.id().toString(), null, Map.of("productKey", p.productKey()));
    }

    @Override
    public void priceBookDrafted(PriceBook b) {
        audit.record("catalog.price-book.create", "price-book", b.id().toString(), null, scopeDetails(b));
    }

    @Override
    public void priceEntryChanged(PriceBook b, UUID skuId, boolean removed) {
        audit.record(removed ? "catalog.price.remove" : "catalog.price.set", "price-book", b.id().toString(), null,
                Map.of("skuId", skuId.toString()));
    }

    /** BASE_PRICE_CHANGE: decided directly by WAVER Commercial until approval-service exists, so the audit says so. */
    @Override
    public void priceBookActivated(PriceBook b, Optional<UUID> superseded) {
        PriceBookActivated data = PriceBookActivated.newBuilder()
                .setPriceBookId(b.id())
                .setScope(b.scope().name())
                .setScopeRef(b.scopeRef().orElse(null))
                .setCurrency(b.currency().getCurrencyCode())
                .setValidFrom(b.validFrom())
                .setValidTo(b.validTo().orElse(null))
                .setSkuIds(b.entries().stream().map(PriceEntry::skuId).map(UUID::toString).toList())
                .setSupersededPriceBookId(superseded.orElse(null))
                .setActivatedAt(b.activatedAt().orElseThrow())
                .build();
        publisher.publish(new EventPublisher.Event(PRICE_BOOK_TOPIC, "waver.catalog.PriceBookActivated.v1", "price-book", b.id(), null,
                b.version(), data));
        Map<String, String> details = new HashMap<>(scopeDetails(b));
        details.put("approval", "DIRECT_BY_COMMERCIAL");
        superseded.ifPresent(id -> details.put("supersededPriceBookId", id.toString()));
        audit.record("catalog.price-book.activate", "price-book", b.id().toString(), null, details);
    }

    @Override
    public void pricingRuleDefined(PricingRule r) {
        audit.record("catalog.pricing-rule.create", "pricing-rule", r.id().toString(), null, Map.of("scope", r.target().scope().name()));
    }

    @Override
    public void discountPolicyDefined(DiscountPolicy p) {
        audit.record("catalog.discount-policy.create", "discount-policy", p.id().toString(), null,
                Map.of("scope", p.target().scope().name(), "approvalPolicyKey", p.approvalPolicyKey()));
    }

    private static Map<String, String> scopeDetails(PriceBook b) {
        Map<String, String> details = new HashMap<>();
        details.put("scope", b.scope().name());
        details.put("currency", b.currency().getCurrencyCode());
        b.scopeRef().ifPresent(ref -> details.put("scopeRef", ref));
        return details;
    }
}
