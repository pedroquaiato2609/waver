package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Product (RFC-0017 §4): something WAVER sells through resellers, with its SKUs. Created and published by
 * WAVER Product Ops. SKUs are defined while the product is a draft; once published, resellers can see it and
 * the marketplace can list it. The offer is immutable after publication (new SKUs mean a new product version
 * in a later slice).
 */
public final class Product {

    public enum ItemType { PRODUCT, TOOL, CONNECTOR, SOLUTION_PACK, CHANNEL, CAPACITY, SERVICE }

    public enum Status { DRAFT, PUBLISHED, RETIRED }

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{2,62}$");

    private final UUID id;
    private final String productKey;
    private final String name;
    private final ItemType itemType;
    private final String technicalRef;
    private final List<Sku> skus;
    private Status status;
    private Instant publishedAt;
    private long version;
    private final Instant createdAt;

    private Product(UUID id, String productKey, String name, ItemType itemType, String technicalRef, List<Sku> skus,
                    Status status, Instant publishedAt, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.productKey = Objects.requireNonNull(productKey);
        this.name = Objects.requireNonNull(name);
        this.itemType = Objects.requireNonNull(itemType);
        this.technicalRef = technicalRef;
        this.skus = new ArrayList<>(skus);
        this.status = Objects.requireNonNull(status);
        this.publishedAt = publishedAt;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Product create(String productKey, String name, ItemType itemType, String technicalRef, Instant now) {
        String key = productKey == null ? "" : productKey.strip();
        if (!KEY.matcher(key).matches()) {
            throw invalid("CATALOG_PRODUCT_KEY_INVALID", "Product key: 3 to 63 lowercase letters, digits or hyphens, e.g. waver-agents.");
        }
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 120) {
            throw invalid("CATALOG_PRODUCT_NAME_INVALID", "Product name must have between 2 and 120 characters.");
        }
        if (itemType == null) {
            throw invalid("CATALOG_PRODUCT_TYPE_REQUIRED", "Inform the item type.");
        }
        String ref = technicalRef == null || technicalRef.isBlank() ? null : technicalRef.strip();
        if (ref != null && ref.length() > 120) {
            throw invalid("CATALOG_PRODUCT_TECHNICAL_REF_INVALID", "Technical reference is too long.");
        }
        return new Product(Ids.newId(), key, n, itemType, ref, List.of(), Status.DRAFT, null, 0, now);
    }

    public static Product restore(UUID id, String productKey, String name, ItemType itemType, String technicalRef, List<Sku> skus,
                                  Status status, Instant publishedAt, long version, Instant createdAt) {
        return new Product(id, productKey, name, itemType, technicalRef, skus, status, publishedAt, version, createdAt);
    }

    public Sku addSku(String skuCode, Sku.Unit unit, Sku.ChargeType chargeType, Sku.BillingPeriod period, String meterKey) {
        if (status != Status.DRAFT) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "CATALOG_PRODUCT_NOT_DRAFT",
                    "SKUs can only be added while the product is a draft.");
        }
        Sku sku = Sku.define(id, skuCode, unit, chargeType, period, meterKey);
        if (skus.stream().anyMatch(s -> s.skuCode().equals(sku.skuCode()))) {
            throw new DomainException(DomainException.Kind.CONFLICT, "CATALOG_SKU_CODE_EXISTS", "This product already has this SKU code.");
        }
        skus.add(sku);
        version++;
        return sku;
    }

    /** DRAFT → PUBLISHED. Emits ProductPublished. Needs at least one SKU. */
    public void publish(Instant now) {
        if (status != Status.DRAFT) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "CATALOG_PRODUCT_NOT_DRAFT", "Only a draft product can be published.");
        }
        if (skus.isEmpty()) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "CATALOG_PRODUCT_WITHOUT_SKU",
                    "Add at least one SKU before publishing the product.");
        }
        status = Status.PUBLISHED;
        publishedAt = now;
        version++;
    }

    public Optional<Sku> sku(UUID skuId) {
        return skus.stream().filter(s -> s.id().equals(skuId)).findFirst();
    }

    private static DomainException invalid(String code, String message) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message);
    }

    public UUID id() { return id; }
    public String productKey() { return productKey; }
    public String name() { return name; }
    public ItemType itemType() { return itemType; }
    public Optional<String> technicalRef() { return Optional.ofNullable(technicalRef); }
    public List<Sku> skus() { return List.copyOf(skus); }
    public Status status() { return status; }
    public Optional<Instant> publishedAt() { return Optional.ofNullable(publishedAt); }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
