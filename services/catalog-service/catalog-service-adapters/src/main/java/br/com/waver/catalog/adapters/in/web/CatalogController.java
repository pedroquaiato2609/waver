package br.com.waver.catalog.adapters.in.web;

import br.com.waver.catalog.application.CatalogService;
import br.com.waver.catalog.domain.DiscountPolicy;
import br.com.waver.catalog.domain.PolicyTarget;
import br.com.waver.catalog.domain.PriceBook;
import br.com.waver.catalog.domain.PriceEntry;
import br.com.waver.catalog.domain.PriceScope;
import br.com.waver.catalog.domain.Pricing;
import br.com.waver.catalog.domain.PricingRule;
import br.com.waver.catalog.domain.Product;
import br.com.waver.catalog.domain.Sku;
import br.com.waver.platform.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service API (RFC-0017 §4): /v1/products, /v1/price-books, /v1/pricing-rules, /v1/discount-policies,
 * /v1/price-quotes and /v1/discount-policy-evaluations. Amounts are decimals in the currency of their book.
 */
@RestController
@RequestMapping("/v1")
public class CatalogController {

    // ---- requests ----

    public record CreateProductRequest(String productKey, String name, @NotNull Product.ItemType itemType, String technicalRef) {
    }

    public record AddSkuRequest(String skuCode, @NotNull Sku.Unit unit, @NotNull Sku.ChargeType chargeType,
                                Sku.BillingPeriod billingPeriod, String meterKey) {
    }

    public record CreatePriceBookRequest(String name, String currency, @NotNull PriceScope scope, String scopeRef,
                                         Instant validFrom, Instant validTo) {
    }

    public record WholesaleInput(@NotNull PriceEntry.ChargeModel chargeModel, BigDecimal amount) {
    }

    public record RetailInput(@NotNull PriceEntry.PricingModel pricingModel, BigDecimal suggestedAmount, BigDecimal fixedAmount,
                              BigDecimal floorAmount, BigDecimal ceilingAmount) {
    }

    public record PriceEntryRequest(@NotNull @Valid WholesaleInput wholesale, @NotNull @Valid RetailInput retail) {
    }

    public record PricingRuleRequest(@NotNull PriceScope scope, String scopeRef, UUID skuId, BigDecimal minMarginPct,
                                     BigDecimal maxMarginPct, Instant validFrom, Instant validTo) {
    }

    public record DiscountPolicyRequest(@NotNull PriceScope scope, String scopeRef, UUID skuId, BigDecimal maxDiscountWithoutApprovalPct,
                                        String approvalPolicyKey, Instant validFrom, Instant validTo) {
    }

    public record QuoteItem(@NotNull UUID skuId, Long quantity) {
    }

    public record PriceQuoteRequest(UUID resellerOrgId, String currency, Instant at, @NotNull List<@Valid QuoteItem> items) {
    }

    public record EvaluationRequest(UUID resellerOrgId, String currency, Instant at, @NotNull UUID skuId, Long quantity,
                                    BigDecimal proposedUnitPrice, BigDecimal discountPct) {
    }

    // ---- views ----

    public record SkuView(UUID id, UUID productId, String skuCode, String unit, String chargeType, String billingPeriod, String meterKey,
                          Boolean priced) {
        static SkuView from(Sku s, Boolean priced) {
            return new SkuView(s.id(), s.productId(), s.skuCode(), s.unit().name(), s.chargeType().name(),
                    s.period().map(Enum::name).orElse(null), s.meter().orElse(null), priced);
        }
    }

    public record ProductView(UUID id, String productKey, String name, String itemType, String technicalRef, String status,
                              Instant publishedAt, List<SkuView> skus, long version, Instant createdAt) {
        static ProductView from(Product p, Set<UUID> priced) {
            return new ProductView(p.id(), p.productKey(), p.name(), p.itemType().name(), p.technicalRef().orElse(null), p.status().name(),
                    p.publishedAt().orElse(null),
                    p.skus().stream().map(s -> SkuView.from(s, priced == null ? null : priced.contains(s.id()))).toList(),
                    p.version(), p.createdAt());
        }
    }

    public record PriceEntryView(UUID skuId, String chargeModel, BigDecimal wholesaleAmount, String pricingModel,
                                 BigDecimal suggestedAmount, BigDecimal fixedAmount, BigDecimal floorAmount, BigDecimal ceilingAmount) {
        static PriceEntryView from(PriceEntry e) {
            PriceEntry.RetailPrice r = e.retail();
            return new PriceEntryView(e.skuId(), e.wholesale().chargeModel().name(), e.wholesale().amount(), r.pricingModel().name(),
                    r.suggestedAmount(), r.fixedAmount(), r.floorAmount(), r.ceilingAmount());
        }
    }

    public record PriceBookView(UUID id, String name, String currency, String scope, String scopeRef, Instant validFrom, Instant validTo,
                                String status, Instant activatedAt, String activatedBy, List<PriceEntryView> entries, long version,
                                Instant createdAt) {
        static PriceBookView from(PriceBook b) {
            return new PriceBookView(b.id(), b.name(), b.currency().getCurrencyCode(), b.scope().name(), b.scopeRef().orElse(null),
                    b.validFrom(), b.validTo().orElse(null), b.status().name(), b.activatedAt().orElse(null), b.activatedBy().orElse(null),
                    b.entries().stream().map(PriceEntryView::from).toList(), b.version(), b.createdAt());
        }
    }

    public record TargetView(String scope, String scopeRef, UUID skuId, Instant validFrom, Instant validTo) {
        static TargetView from(PolicyTarget t) {
            return new TargetView(t.scope().name(), t.scopeRef(), t.skuId(), t.validFrom(), t.validTo());
        }
    }

    public record PricingRuleView(UUID id, TargetView target, BigDecimal minMarginPct, BigDecimal maxMarginPct, Instant createdAt) {
        static PricingRuleView from(PricingRule r) {
            return new PricingRuleView(r.id(), TargetView.from(r.target()), r.minMarginPct(), r.maxMarginPct(), r.createdAt());
        }
    }

    public record DiscountPolicyView(UUID id, TargetView target, BigDecimal maxDiscountWithoutApprovalPct, String approvalPolicyKey,
                                     Instant createdAt) {
        static DiscountPolicyView from(DiscountPolicy p) {
            return new DiscountPolicyView(p.id(), TargetView.from(p.target()), p.maxDiscountWithoutApprovalPct(), p.approvalPolicyKey(),
                    p.createdAt());
        }
    }

    public record MarginBandView(UUID ruleId, BigDecimal minMarginPct, BigDecimal maxMarginPct) {
    }

    public record QuoteView(UUID skuId, UUID productId, String skuCode, String unit, String chargeType, String billingPeriod, long quantity,
                            boolean available, String unavailableReason, UUID priceBookId, String currency, String chargeModel,
                            BigDecimal wholesaleUnitAmount, BigDecimal wholesaleTotalAmount, String pricingModel,
                            BigDecimal retailUnitAmount, BigDecimal retailTotalAmount, BigDecimal retailFloorAmount,
                            BigDecimal retailCeilingAmount, MarginBandView marginBand) {
        static QuoteView from(CatalogService.QuoteResult r) {
            if (r.quote().isEmpty()) {
                return new QuoteView(r.skuId(), null, null, null, null, null, r.quantity(), false, r.unavailableReason(), null, null,
                        null, null, null, null, null, null, null, null, null);
            }
            Pricing.Quote q = r.quote().get();
            Sku s = q.sku();
            PriceEntry.RetailPrice retail = q.entry().retail();
            return new QuoteView(s.id(), s.productId(), s.skuCode(), s.unit().name(), s.chargeType().name(), s.period().map(Enum::name).orElse(null),
                    q.quantity(), true, null, q.book().id(), q.book().currency().getCurrencyCode(), q.entry().wholesale().chargeModel().name(),
                    q.wholesaleUnit(), q.wholesaleTotal(), retail.pricingModel().name(), q.listUnit(), q.listTotal(),
                    retail.floorAmount(), retail.ceilingAmount(),
                    q.rule().map(rule -> new MarginBandView(rule.id(), rule.minMarginPct(), rule.maxMarginPct())).orElse(null));
        }
    }

    public record QuotesView(List<QuoteView> items) {
    }

    public record EvaluationView(String outcome, String approvalPolicyKey, List<String> reasons, BigDecimal proposedUnitPrice,
                                 BigDecimal discountPct, BigDecimal marginPct, QuoteView quote) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    // ---- products ----

    @PostMapping("/products")
    public ResponseEntity<ProductView> createProduct(@Valid @RequestBody CreateProductRequest r) {
        Product p = catalog.createProduct(TenantContextHolder.require(),
                new CatalogService.CreateProduct(r.productKey(), r.name(), r.itemType(), r.technicalRef()));
        return ResponseEntity.created(URI.create("/v1/products/" + p.id())).body(ProductView.from(p, Set.of()));
    }

    @GetMapping("/products")
    public Page<ProductView> products(@RequestParam(required = false) Product.Status status,
                                      @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) UUID cursor) {
        List<Product> items = catalog.products(TenantContextHolder.require(), status, cursor, limit);
        return page(items.stream().map(p -> ProductView.from(p, null)).toList(), items.size() == Math.clamp(limit, 1, 200)
                ? items.getLast().id() : null);
    }

    @GetMapping("/products/{id}")
    public ProductView product(@PathVariable UUID id) {
        CatalogService.ProductDetails d = catalog.product(TenantContextHolder.require(), id);
        return ProductView.from(d.product(), d.pricedSkuIds());
    }

    @PostMapping("/products/{id}/skus")
    public ProductView addSku(@PathVariable UUID id, @Valid @RequestBody AddSkuRequest r) {
        catalog.addSku(TenantContextHolder.require(), id,
                new CatalogService.AddSku(r.skuCode(), r.unit(), r.chargeType(), r.billingPeriod(), r.meterKey()));
        return product(id);
    }

    @PostMapping("/products/{id}/publication")
    public ProductView publish(@PathVariable UUID id) {
        catalog.publishProduct(TenantContextHolder.require(), id);
        return product(id);
    }

    // ---- price books ----

    @PostMapping("/price-books")
    public ResponseEntity<PriceBookView> createPriceBook(@Valid @RequestBody CreatePriceBookRequest r) {
        PriceBook b = catalog.createPriceBook(TenantContextHolder.require(), new CatalogService.CreatePriceBook(
                r.name(), r.currency(), r.scope(), r.scopeRef(), r.validFrom(), r.validTo()));
        return ResponseEntity.created(URI.create("/v1/price-books/" + b.id())).body(PriceBookView.from(b));
    }

    @GetMapping("/price-books")
    public Page<PriceBookView> priceBooks(@RequestParam(required = false) PriceBook.Status status,
                                          @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) UUID cursor) {
        List<PriceBook> items = catalog.priceBooks(TenantContextHolder.require(), status, cursor, limit);
        return page(items.stream().map(PriceBookView::from).toList(), items.size() == Math.clamp(limit, 1, 200) ? items.getLast().id() : null);
    }

    @GetMapping("/price-books/{id}")
    public PriceBookView priceBook(@PathVariable UUID id) {
        return PriceBookView.from(catalog.priceBook(TenantContextHolder.require(), id));
    }

    @GetMapping("/price-books/{id}/entries")
    public List<PriceEntryView> entries(@PathVariable UUID id) {
        return priceBook(id).entries();
    }

    @PutMapping("/price-books/{id}/entries/{skuId}")
    public PriceBookView putEntry(@PathVariable UUID id, @PathVariable UUID skuId, @Valid @RequestBody PriceEntryRequest r) {
        return PriceBookView.from(catalog.putEntry(TenantContextHolder.require(), id, skuId,
                new PriceEntry.WholesalePrice(r.wholesale().chargeModel(), r.wholesale().amount()),
                new PriceEntry.RetailPrice(r.retail().pricingModel(), r.retail().suggestedAmount(), r.retail().fixedAmount(),
                        r.retail().floorAmount(), r.retail().ceilingAmount())));
    }

    @DeleteMapping("/price-books/{id}/entries/{skuId}")
    public PriceBookView removeEntry(@PathVariable UUID id, @PathVariable UUID skuId) {
        return PriceBookView.from(catalog.removeEntry(TenantContextHolder.require(), id, skuId));
    }

    @PostMapping("/price-books/{id}/activation")
    public PriceBookView activate(@PathVariable UUID id) {
        return PriceBookView.from(catalog.activatePriceBook(TenantContextHolder.require(), id));
    }

    // ---- pricing rules and discount policies ----

    @PostMapping("/pricing-rules")
    public ResponseEntity<PricingRuleView> definePricingRule(@Valid @RequestBody PricingRuleRequest r) {
        PricingRule rule = catalog.definePricingRule(TenantContextHolder.require(),
                new CatalogService.DefineTarget(r.scope(), r.scopeRef(), r.skuId(), r.validFrom(), r.validTo()), r.minMarginPct(), r.maxMarginPct());
        return ResponseEntity.created(URI.create("/v1/pricing-rules/" + rule.id())).body(PricingRuleView.from(rule));
    }

    @GetMapping("/pricing-rules")
    public Page<PricingRuleView> pricingRules() {
        return page(catalog.pricingRules(TenantContextHolder.require()).stream().map(PricingRuleView::from).toList(), null);
    }

    @PostMapping("/discount-policies")
    public ResponseEntity<DiscountPolicyView> defineDiscountPolicy(@Valid @RequestBody DiscountPolicyRequest r) {
        DiscountPolicy p = catalog.defineDiscountPolicy(TenantContextHolder.require(),
                new CatalogService.DefineTarget(r.scope(), r.scopeRef(), r.skuId(), r.validFrom(), r.validTo()),
                r.maxDiscountWithoutApprovalPct(), r.approvalPolicyKey());
        return ResponseEntity.created(URI.create("/v1/discount-policies/" + p.id())).body(DiscountPolicyView.from(p));
    }

    @GetMapping("/discount-policies")
    public Page<DiscountPolicyView> discountPolicies() {
        return page(catalog.discountPolicies(TenantContextHolder.require()).stream().map(DiscountPolicyView::from).toList(), null);
    }

    // ---- evaluation ----

    @PostMapping("/price-quotes")
    public QuotesView quote(@Valid @RequestBody PriceQuoteRequest r) {
        List<CatalogService.QuoteLine> lines = r.items().stream()
                .map(i -> new CatalogService.QuoteLine(i.skuId(), i.quantity() == null ? 1 : i.quantity())).toList();
        return new QuotesView(catalog.quote(TenantContextHolder.require(),
                new CatalogService.QuoteRequest(r.resellerOrgId(), r.currency(), r.at(), lines)).stream().map(QuoteView::from).toList());
    }

    @PostMapping("/discount-policy-evaluations")
    public EvaluationView evaluate(@Valid @RequestBody EvaluationRequest r) {
        Pricing.Evaluation e = catalog.evaluateDiscount(TenantContextHolder.require(), new CatalogService.EvaluateDiscount(
                r.resellerOrgId(), r.currency(), r.at(), r.skuId(), r.quantity() == null ? 1 : r.quantity(), r.proposedUnitPrice(), r.discountPct()));
        return new EvaluationView(e.outcome().name(), e.approvalPolicyKey(), e.reasons(), e.proposedUnit(), e.discountPct(), e.marginPct(),
                QuoteView.from(new CatalogService.QuoteResult(e.quote().sku().id(), e.quote().quantity(), java.util.Optional.of(e.quote()), null)));
    }

    private static <T> Page<T> page(List<T> items, UUID next) {
        return new Page<>(items, next == null ? null : next.toString());
    }
}
