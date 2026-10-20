package br.com.waver.partnerbff;

import br.com.waver.platform.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * "Ofertas disponíveis" of WAVER Partner (RFC-0017 §1, §5): the listings available to the signed-in reseller
 * (marketplace-service, availableTo=me) with the wholesale and retail price of each SKU for that reseller
 * (catalog-service price quotes). Both services decide with the reseller's own token and Row-Level Security,
 * so this BFF never filters: it only joins the two answers for the screen.
 */
@RestController
@RequestMapping("/api")
public class OffersApi {

    record OfferVersion(UUID id, int versionNo, List<UUID> skuIds, String requirements, String docsRef, String supportPolicy,
                        Instant publishedAt) {
    }

    record AvailableListing(UUID id, String listingKey, UUID productId, String category, String title, String summary,
                            OfferVersion currentVersion) {
    }

    record ListingPage(List<AvailableListing> items, String nextCursor) {
    }

    record QuoteItems(List<Map<String, Object>> items) {
    }

    public record Offer(UUID listingId, String listingKey, UUID productId, String category, String title, String summary,
                        Integer versionNo, String requirements, String docsRef, String supportPolicy, Instant publishedAt,
                        List<Map<String, Object>> skus) {
    }

    public record Offers(List<Offer> items) {
    }

    private final RestClient marketplace;
    private final RestClient catalog;

    public OffersApi(@Qualifier("marketplaceService") RestClient marketplace, @Qualifier("catalogService") RestClient catalog) {
        this.marketplace = marketplace;
        this.catalog = catalog;
    }

    @GetMapping("/offers")
    public Offers offers() {
        ListingPage page = marketplace.get()
                .uri(b -> b.path("/v1/listings").queryParam("availableTo", "me").queryParam("limit", 200).build())
                .header(CorrelationIdFilter.HEADER, CorrelationIdFilter.current())
                .retrieve().body(ListingPage.class);
        List<AvailableListing> listings = page == null || page.items() == null ? List.of() : page.items();
        Set<UUID> skuIds = listings.stream().filter(l -> l.currentVersion() != null)
                .flatMap(l -> l.currentVersion().skuIds().stream()).collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Map<String, Object>> quotes = Map.of();
        if (!skuIds.isEmpty()) {
            QuoteItems q = catalog.post().uri("/v1/price-quotes")
                    .header(CorrelationIdFilter.HEADER, CorrelationIdFilter.current())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("items", skuIds.stream().map(id -> Map.of("skuId", id.toString(), "quantity", 1)).toList()))
                    .retrieve().body(QuoteItems.class);
            quotes = q == null || q.items() == null ? Map.of() : q.items().stream()
                    .collect(Collectors.toMap(i -> String.valueOf(i.get("skuId")), Function.identity(), (a, b) -> a));
        }
        Map<String, Map<String, Object>> bySku = quotes;
        return new Offers(listings.stream().map(l -> {
            OfferVersion v = l.currentVersion();
            List<Map<String, Object>> skus = v == null ? List.of()
                    : v.skuIds().stream().map(id -> bySku.get(id.toString())).filter(Objects::nonNull).toList();
            return new Offer(l.id(), l.listingKey(), l.productId(), l.category(), l.title(), l.summary(),
                    v == null ? null : v.versionNo(), v == null ? null : v.requirements(), v == null ? null : v.docsRef(),
                    v == null ? null : v.supportPolicy(), v == null ? null : v.publishedAt(), skus);
        }).toList());
    }

    /** Service errors (problem+json) pass through unchanged, as in the other Partner endpoints. */
    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<String> serviceError(RestClientResponseException e) {
        return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(e.getResponseBodyAsString());
    }
}
