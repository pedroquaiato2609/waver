package br.com.waver.catalog.application;

import br.com.waver.catalog.domain.DiscountPolicy;
import br.com.waver.catalog.domain.PolicyTarget;
import br.com.waver.catalog.domain.PriceBook;
import br.com.waver.catalog.domain.PriceBooks;
import br.com.waver.catalog.domain.PriceEntry;
import br.com.waver.catalog.domain.PriceScope;
import br.com.waver.catalog.domain.Pricing;
import br.com.waver.catalog.domain.PricingPolicies;
import br.com.waver.catalog.domain.PricingRule;
import br.com.waver.catalog.domain.Product;
import br.com.waver.catalog.domain.Products;
import br.com.waver.catalog.domain.ResellerRef;
import br.com.waver.catalog.domain.ResellerTiers;
import br.com.waver.catalog.domain.Sku;
import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Catalog use cases (RFC-0017 §4). WAVER Product Ops owns products and SKUs (catalog.product.*); WAVER Commercial
 * owns price books, pricing rules and discount policies (catalog.price.*). Resellers read what they may sell and
 * ask for quotes and discount evaluations for themselves. Every write stores its change, event and audit record
 * in one transaction.
 */
public class CatalogService {

    private static final Set<String> PRODUCT_ROLES = Set.of("PLATFORM_SUPER_ADMIN", "WAVER_PRODUCT_OPS");
    private static final Set<String> PRICE_ROLES = Set.of("PLATFORM_SUPER_ADMIN", "WAVER_COMMERCIAL");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record CreateProduct(String productKey, String name, Product.ItemType itemType, String technicalRef) {
    }

    public record AddSku(String skuCode, Sku.Unit unit, Sku.ChargeType chargeType, Sku.BillingPeriod billingPeriod, String meterKey) {
    }

    /** A product with the SKUs that have a price in an active, valid book (the marketplace needs priced SKUs). */
    public record ProductDetails(Product product, Set<UUID> pricedSkuIds) {
    }

    public record CreatePriceBook(String name, String currency, PriceScope scope, String scopeRef, Instant validFrom, Instant validTo) {
    }

    public record DefineTarget(PriceScope scope, String scopeRef, UUID skuId, Instant validFrom, Instant validTo) {
    }

    public record QuoteLine(UUID skuId, long quantity) {
    }

    /** {@code resellerOrgId}: WAVER staff quote for a reseller; a reseller always quotes for itself. */
    public record QuoteRequest(UUID resellerOrgId, String currency, Instant at, List<QuoteLine> lines) {
    }

    /** Either a quote, or why the SKU has no price for this reseller (SKU_NOT_FOUND, PRICE_NOT_AVAILABLE). */
    public record QuoteResult(UUID skuId, long quantity, Optional<Pricing.Quote> quote, String unavailableReason) {
    }

    /** Proposed retail unit price, or a discount percentage off the list price. Exactly one of them. */
    public record EvaluateDiscount(UUID resellerOrgId, String currency, Instant at, UUID skuId, long quantity,
                                   BigDecimal proposedUnitPrice, BigDecimal discountPct) {
    }

    public interface Events {
        void productCreated(Product product);

        void skuAdded(Product product, Sku sku);

        void productPublished(Product product);

        void priceBookDrafted(PriceBook book);

        void priceEntryChanged(PriceBook book, UUID skuId, boolean removed);

        void priceBookActivated(PriceBook book, Optional<UUID> superseded);

        void pricingRuleDefined(PricingRule rule);

        void discountPolicyDefined(DiscountPolicy policy);
    }

    private final Products products;
    private final PriceBooks books;
    private final PricingPolicies policies;
    private final ResellerTiers tiers;
    private final Events events;
    private final TransactionOperations tx;
    private final Clock clock;

    public CatalogService(Products products, PriceBooks books, PricingPolicies policies, ResellerTiers tiers, Events events,
                          TransactionOperations tx, Clock clock) {
        this.products = products;
        this.books = books;
        this.policies = policies;
        this.tiers = tiers;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- Products and SKUs (WAVER Product Ops) ----

    public Product createProduct(TenantContext actor, CreateProduct c) {
        require(actor, PRODUCT_ROLES, "CATALOG_PRODUCT_FORBIDDEN", "Only WAVER Product Ops can manage products.");
        Product product = Product.create(c.productKey(), c.name(), c.itemType(), c.technicalRef(), clock.instant());
        return tx.execute(s -> {
            if (products.existsByKey(product.productKey())) {
                throw new DomainException(DomainException.Kind.CONFLICT, "CATALOG_PRODUCT_KEY_EXISTS", "A product with this key already exists.");
            }
            products.insert(product);
            events.productCreated(product);
            return product;
        });
    }

    public Product addSku(TenantContext actor, UUID productId, AddSku c) {
        require(actor, PRODUCT_ROLES, "CATALOG_PRODUCT_FORBIDDEN", "Only WAVER Product Ops can manage products.");
        return tx.execute(s -> {
            Product product = loadProduct(productId);
            long before = product.version();
            Sku sku = product.addSku(c.skuCode(), c.unit(), c.chargeType(), c.billingPeriod(), c.meterKey());
            if (products.existsSkuCode(sku.skuCode())) {
                throw new DomainException(DomainException.Kind.CONFLICT, "CATALOG_SKU_CODE_EXISTS", "Another product already uses this SKU code.");
            }
            saveProduct(product, before);
            products.insertSku(sku);
            events.skuAdded(product, sku);
            return product;
        });
    }

    public Product publishProduct(TenantContext actor, UUID productId) {
        require(actor, PRODUCT_ROLES, "CATALOG_PRODUCT_FORBIDDEN", "Only WAVER Product Ops can manage products.");
        return tx.execute(s -> {
            Product product = loadProduct(productId);
            long before = product.version();
            product.publish(clock.instant());
            saveProduct(product, before);
            events.productPublished(product);
            return product;
        });
    }

    public ProductDetails product(TenantContext actor, UUID productId) {
        requireReader(actor);
        Instant now = clock.instant();
        return tx.execute(s -> {
            Product product = loadProduct(productId);
            Set<UUID> skuIds = product.skus().stream().map(Sku::id).collect(Collectors.toSet());
            Set<UUID> priced = books.findActivePricing(skuIds).stream()
                    .filter(b -> b.validAt(now))
                    .flatMap(b -> b.entries().stream().map(PriceEntry::skuId))
                    .filter(skuIds::contains)
                    .collect(Collectors.toSet());
            return new ProductDetails(product, priced);
        });
    }

    public List<Product> products(TenantContext actor, Product.Status status, UUID afterId, int limit) {
        requireReader(actor);
        return tx.execute(s -> products.list(status, afterId, Math.clamp(limit, 1, 200)));
    }

    // ---- Price books (WAVER Commercial; BASE_PRICE_CHANGE decided directly until approval-service) ----

    public PriceBook createPriceBook(TenantContext actor, CreatePriceBook c) {
        require(actor, PRICE_ROLES, "CATALOG_PRICE_FORBIDDEN", "Only WAVER Commercial can manage prices.");
        PriceBook book = PriceBook.draft(c.name(), c.currency(), c.scope(), c.scopeRef(), c.validFrom(), c.validTo(), clock.instant());
        tx.executeWithoutResult(s -> {
            books.insert(book);
            events.priceBookDrafted(book);
        });
        return book;
    }

    public PriceBook putEntry(TenantContext actor, UUID bookId, UUID skuId, PriceEntry.WholesalePrice wholesale, PriceEntry.RetailPrice retail) {
        require(actor, PRICE_ROLES, "CATALOG_PRICE_FORBIDDEN", "Only WAVER Commercial can manage prices.");
        PriceEntry entry = PriceEntry.of(skuId, wholesale, retail);
        return changeBook(bookId, book -> {
            if (products.findSkus(List.of(skuId)).isEmpty()) {
                throw new DomainException(DomainException.Kind.NOT_FOUND, "CATALOG_SKU_NOT_FOUND", "SKU not found.");
            }
            book.putEntry(entry);
            events.priceEntryChanged(book, skuId, false);
        });
    }

    public PriceBook removeEntry(TenantContext actor, UUID bookId, UUID skuId) {
        require(actor, PRICE_ROLES, "CATALOG_PRICE_FORBIDDEN", "Only WAVER Commercial can manage prices.");
        return changeBook(bookId, book -> {
            book.removeEntry(skuId);
            events.priceEntryChanged(book, skuId, true);
        });
    }

    /** DRAFT → ACTIVE; the active book of the same scope and currency is retired in the same transaction. */
    public PriceBook activatePriceBook(TenantContext actor, UUID bookId) {
        require(actor, PRICE_ROLES, "CATALOG_PRICE_FORBIDDEN", "Only WAVER Commercial can manage prices.");
        return tx.execute(s -> {
            PriceBook book = loadBook(bookId);
            long before = book.version();
            Optional<PriceBook> current = books.findActive(book.scope(), book.scopeRef().orElse(null), book.currency());
            long currentBefore = current.map(PriceBook::version).orElse(0L);
            Optional<UUID> superseded = book.activate(actor.principalId(), current, clock.instant());
            if (superseded.isPresent()) {
                saveBook(current.get(), currentBefore);
            }
            saveBook(book, before);
            events.priceBookActivated(book, superseded);
            return book;
        });
    }

    public PriceBook priceBook(TenantContext actor, UUID bookId) {
        requireReader(actor);
        return tx.execute(s -> loadBook(bookId));
    }

    public List<PriceBook> priceBooks(TenantContext actor, PriceBook.Status status, UUID afterId, int limit) {
        requireReader(actor);
        return tx.execute(s -> books.list(status, afterId, Math.clamp(limit, 1, 200)));
    }

    // ---- Pricing rules and discount policies (D-04, provisional) ----

    public PricingRule definePricingRule(TenantContext actor, DefineTarget t, BigDecimal minMarginPct, BigDecimal maxMarginPct) {
        require(actor, PRICE_ROLES, "CATALOG_PRICE_FORBIDDEN", "Only WAVER Commercial can manage prices.");
        PricingRule rule = PricingRule.define(target(t), minMarginPct, maxMarginPct, clock.instant());
        tx.executeWithoutResult(s -> {
            requireSkuIfAny(t.skuId());
            policies.insert(rule);
            events.pricingRuleDefined(rule);
        });
        return rule;
    }

    public DiscountPolicy defineDiscountPolicy(TenantContext actor, DefineTarget t, BigDecimal maxDiscountPct, String approvalPolicyKey) {
        require(actor, PRICE_ROLES, "CATALOG_PRICE_FORBIDDEN", "Only WAVER Commercial can manage prices.");
        DiscountPolicy policy = DiscountPolicy.define(target(t), maxDiscountPct, approvalPolicyKey, clock.instant());
        tx.executeWithoutResult(s -> {
            requireSkuIfAny(t.skuId());
            policies.insert(policy);
            events.discountPolicyDefined(policy);
        });
        return policy;
    }

    public List<PricingRule> pricingRules(TenantContext actor) {
        requireReader(actor);
        return tx.execute(s -> policies.rules());
    }

    public List<DiscountPolicy> discountPolicies(TenantContext actor) {
        requireReader(actor);
        return tx.execute(s -> policies.discountPolicies());
    }

    // ---- Evaluation endpoints ----

    public List<QuoteResult> quote(TenantContext actor, QuoteRequest r) {
        ResellerRef reseller = resellerFor(actor, r.resellerOrgId());
        if (r.lines() == null || r.lines().isEmpty() || r.lines().size() > 200) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_QUOTE_LINES_INVALID", "Quote between 1 and 200 SKUs.");
        }
        Instant at = r.at() == null ? clock.instant() : r.at();
        String currency = currency(r.currency());
        return tx.execute(s -> {
            List<UUID> ids = r.lines().stream().map(QuoteLine::skuId).distinct().toList();
            Map<UUID, Sku> skus = products.findSkus(ids).stream().collect(Collectors.toMap(Sku::id, Function.identity()));
            List<PriceBook> candidates = books.findActivePricing(ids);
            List<PricingRule> rules = policies.rules();
            return r.lines().stream().map(line -> {
                Sku sku = skus.get(line.skuId());
                if (sku == null) {
                    return new QuoteResult(line.skuId(), line.quantity(), Optional.empty(), "SKU_NOT_FOUND");
                }
                Optional<Pricing.Quote> q = Pricing.quote(reseller, sku, line.quantity(), at, currency, candidates, rules);
                return new QuoteResult(line.skuId(), line.quantity(), q, q.isPresent() ? null : "PRICE_NOT_AVAILABLE");
            }).toList();
        });
    }

    public Pricing.Evaluation evaluateDiscount(TenantContext actor, EvaluateDiscount r) {
        ResellerRef reseller = resellerFor(actor, r.resellerOrgId());
        if ((r.proposedUnitPrice() == null) == (r.discountPct() == null)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_EVALUATION_INPUT_INVALID",
                    "Inform either the proposed unit price or the discount percentage.");
        }
        if (r.discountPct() != null && (r.discountPct().signum() < 0 || r.discountPct().compareTo(HUNDRED) > 0)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_DISCOUNT_INVALID", "The discount is a percentage between 0 and 100.");
        }
        Instant at = r.at() == null ? clock.instant() : r.at();
        String currency = currency(r.currency());
        return tx.execute(s -> {
            Sku sku = products.findSkus(List.of(r.skuId())).stream().findFirst().orElseThrow(() ->
                    new DomainException(DomainException.Kind.NOT_FOUND, "CATALOG_SKU_NOT_FOUND", "SKU not found."));
            Pricing.Quote q = Pricing.quote(reseller, sku, Math.max(1, r.quantity()), at, currency,
                    books.findActivePricing(List.of(sku.id())), policies.rules()).orElseThrow(() ->
                    new DomainException(DomainException.Kind.NOT_FOUND, "CATALOG_PRICE_NOT_AVAILABLE", "This SKU has no price for this reseller."));
            BigDecimal proposed = r.proposedUnitPrice() != null ? r.proposedUnitPrice()
                    : q.listUnit().subtract(q.listUnit().multiply(r.discountPct()).divide(HUNDRED, 4, RoundingMode.HALF_EVEN));
            Optional<DiscountPolicy> policy = PolicyTarget.mostSpecific(policies.discountPolicies(), DiscountPolicy::target, reseller, sku.id(), at);
            return Pricing.evaluate(q, proposed, policy);
        });
    }

    // ---- helpers ----

    private ResellerRef resellerFor(TenantContext actor, UUID requested) {
        OrgId org;
        if (actor.orgType() == OrgType.RESELLER) {
            if (requested != null && !requested.equals(actor.orgId().value())) {
                throw new DomainException(DomainException.Kind.FORBIDDEN, "CATALOG_QUOTE_FORBIDDEN", "A reseller can only quote for itself.");
            }
            org = actor.orgId();
        } else if (actor.isPlatformStaff()) {
            if (requested == null) {
                throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_RESELLER_REQUIRED", "Inform the reseller to quote for.");
            }
            org = new OrgId(requested);
        } else {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "CATALOG_QUOTE_FORBIDDEN", "You cannot see wholesale prices.");
        }
        Optional<String> tier = tx.execute(s -> tiers.tierOf(org));
        return new ResellerRef(org, tier == null ? Optional.empty() : tier);
    }

    private static String currency(String code) {
        return code == null || code.isBlank() ? null : code.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private PolicyTarget target(DefineTarget t) {
        return PolicyTarget.of(t.scope(), t.scopeRef(), t.skuId(), t.validFrom(), t.validTo(), clock.instant());
    }

    private void requireSkuIfAny(UUID skuId) {
        if (skuId != null && products.findSkus(List.of(skuId)).isEmpty()) {
            throw new DomainException(DomainException.Kind.NOT_FOUND, "CATALOG_SKU_NOT_FOUND", "SKU not found.");
        }
    }

    private PriceBook changeBook(UUID bookId, java.util.function.Consumer<PriceBook> change) {
        return tx.execute(s -> {
            PriceBook book = loadBook(bookId);
            long before = book.version();
            change.accept(book);
            saveBook(book, before);
            return book;
        });
    }

    private void saveProduct(Product product, long expectedVersion) {
        if (!products.update(product, expectedVersion)) {
            throw concurrent();
        }
    }

    private void saveBook(PriceBook book, long expectedVersion) {
        if (!books.update(book, expectedVersion)) {
            throw concurrent();
        }
    }

    private Product loadProduct(UUID id) {
        return products.findById(id).orElseThrow(() ->
                new DomainException(DomainException.Kind.NOT_FOUND, "CATALOG_PRODUCT_NOT_FOUND", "Product not found."));
    }

    private PriceBook loadBook(UUID id) {
        return books.findById(id).orElseThrow(() ->
                new DomainException(DomainException.Kind.NOT_FOUND, "CATALOG_PRICE_BOOK_NOT_FOUND", "Price book not found."));
    }

    private static DomainException concurrent() {
        return new DomainException(DomainException.Kind.CONFLICT, "CATALOG_CONCURRENT_UPDATE", "The catalog changed meanwhile. Reload and try again.");
    }

    private static void require(TenantContext actor, Set<String> roles, String code, String message) {
        if (actor.orgType() != OrgType.PLATFORM || roles.stream().noneMatch(actor::hasRole)) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, code, message);
        }
    }

    /** WAVER staff and resellers (Row-Level Security limits what a reseller sees); never customers (RFC-0017 §1). */
    private static void requireReader(TenantContext actor) {
        if (actor.orgType() != OrgType.RESELLER && !actor.isPlatformStaff()) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "CATALOG_READ_FORBIDDEN", "You cannot see the catalog.");
        }
    }
}
