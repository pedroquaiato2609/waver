package br.com.waver.controlbff;

import static br.com.waver.controlbff.ControlApi.relay;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
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
import org.springframework.web.client.RestClient;

/**
 * Catálogo and Marketplace screens of WAVER Control (RFC-0017 §4, §5): products, price books, pricing rules and
 * listings. Control has the full audience of both services (RFC-0017 §1); the services decide who may change what.
 */
@RestController
@RequestMapping("/api")
public class CatalogApi {

    private final RestClient catalog;
    private final RestClient marketplace;

    public CatalogApi(@Qualifier("catalogService") RestClient catalog, @Qualifier("marketplaceService") RestClient marketplace) {
        this.catalog = catalog;
        this.marketplace = marketplace;
    }

    // ---- catalog-service ----

    @GetMapping("/catalog/products")
    public ResponseEntity<String> products(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit,
                                           @RequestParam(required = false) String cursor) {
        return relay(catalog.get().uri(b -> b.path("/v1/products").queryParam("limit", limit)
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor)).build()));
    }

    @PostMapping("/catalog/products")
    public ResponseEntity<String> createProduct(@RequestBody Map<String, Object> body) {
        return relay(catalog.post().uri("/v1/products").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @GetMapping("/catalog/products/{id}")
    public ResponseEntity<String> product(@PathVariable String id) {
        return relay(catalog.get().uri("/v1/products/{id}", id));
    }

    @PostMapping("/catalog/products/{id}/skus")
    public ResponseEntity<String> addSku(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return relay(catalog.post().uri("/v1/products/{id}/skus", id).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @PostMapping("/catalog/products/{id}/publication")
    public ResponseEntity<String> publishProduct(@PathVariable String id) {
        return relay(catalog.post().uri("/v1/products/{id}/publication", id));
    }

    @GetMapping("/catalog/price-books")
    public ResponseEntity<String> priceBooks(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit,
                                             @RequestParam(required = false) String cursor) {
        return relay(catalog.get().uri(b -> b.path("/v1/price-books").queryParam("limit", limit)
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor)).build()));
    }

    @PostMapping("/catalog/price-books")
    public ResponseEntity<String> createPriceBook(@RequestBody Map<String, Object> body) {
        return relay(catalog.post().uri("/v1/price-books").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @GetMapping("/catalog/price-books/{id}")
    public ResponseEntity<String> priceBook(@PathVariable String id) {
        return relay(catalog.get().uri("/v1/price-books/{id}", id));
    }

    @PutMapping("/catalog/price-books/{id}/entries/{skuId}")
    public ResponseEntity<String> putEntry(@PathVariable String id, @PathVariable String skuId, @RequestBody Map<String, Object> body) {
        return relay(catalog.put().uri("/v1/price-books/{id}/entries/{skuId}", id, skuId).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @DeleteMapping("/catalog/price-books/{id}/entries/{skuId}")
    public ResponseEntity<String> removeEntry(@PathVariable String id, @PathVariable String skuId) {
        return relay(catalog.delete().uri("/v1/price-books/{id}/entries/{skuId}", id, skuId));
    }

    @PostMapping("/catalog/price-books/{id}/activation")
    public ResponseEntity<String> activatePriceBook(@PathVariable String id) {
        return relay(catalog.post().uri("/v1/price-books/{id}/activation", id));
    }

    @GetMapping("/catalog/pricing-rules")
    public ResponseEntity<String> pricingRules() {
        return relay(catalog.get().uri("/v1/pricing-rules"));
    }

    @PostMapping("/catalog/pricing-rules")
    public ResponseEntity<String> definePricingRule(@RequestBody Map<String, Object> body) {
        return relay(catalog.post().uri("/v1/pricing-rules").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @GetMapping("/catalog/discount-policies")
    public ResponseEntity<String> discountPolicies() {
        return relay(catalog.get().uri("/v1/discount-policies"));
    }

    @PostMapping("/catalog/discount-policies")
    public ResponseEntity<String> defineDiscountPolicy(@RequestBody Map<String, Object> body) {
        return relay(catalog.post().uri("/v1/discount-policies").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @PostMapping("/catalog/price-quotes")
    public ResponseEntity<String> quote(@RequestBody Map<String, Object> body) {
        return relay(catalog.post().uri("/v1/price-quotes").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @PostMapping("/catalog/discount-policy-evaluations")
    public ResponseEntity<String> evaluate(@RequestBody Map<String, Object> body) {
        return relay(catalog.post().uri("/v1/discount-policy-evaluations").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    // ---- marketplace-service ----

    @GetMapping("/marketplace/listings")
    public ResponseEntity<String> listings(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit,
                                           @RequestParam(required = false) String cursor) {
        return relay(marketplace.get().uri(b -> b.path("/v1/listings").queryParam("limit", limit)
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor)).build()));
    }

    @PostMapping("/marketplace/listings")
    public ResponseEntity<String> createListing(@RequestBody Map<String, Object> body) {
        return relay(marketplace.post().uri("/v1/listings").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @GetMapping("/marketplace/listings/{id}")
    public ResponseEntity<String> listing(@PathVariable String id) {
        return relay(marketplace.get().uri("/v1/listings/{id}", id));
    }

    @PostMapping("/marketplace/listings/{id}/versions")
    public ResponseEntity<String> revise(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return relay(marketplace.post().uri("/v1/listings/{id}/versions", id).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @PostMapping("/marketplace/listings/{id}/submission")
    public ResponseEntity<String> submit(@PathVariable String id) {
        return relay(marketplace.post().uri("/v1/listings/{id}/submission", id));
    }

    @PostMapping("/marketplace/listings/{id}/review-decision")
    public ResponseEntity<String> decide(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return relay(marketplace.post().uri("/v1/listings/{id}/review-decision", id).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @PutMapping("/marketplace/listings/{id}/availability-rules")
    public ResponseEntity<String> availability(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return relay(marketplace.put().uri("/v1/listings/{id}/availability-rules", id).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @GetMapping("/marketplace/listings/{id}/availability")
    public ResponseEntity<String> availableTo(@PathVariable String id, @RequestParam String resellerOrgId) {
        return relay(marketplace.get().uri(b -> b.path("/v1/listings/{id}/availability").queryParam("resellerOrgId", resellerOrgId).build(id)));
    }
}
