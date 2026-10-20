package br.com.waver.marketplace.adapters.out.http;

import br.com.waver.marketplace.application.MarketplaceService;
import br.com.waver.marketplace.domain.ProductOffer;
import br.com.waver.platform.http.BearerRelayInterceptor;
import br.com.waver.platform.http.RemoteProblem;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * catalog-service, with the Product Ops user's own token: the product, its SKUs and which of them have a price in
 * an active book. Catalog errors (e.g. CATALOG_PRODUCT_NOT_FOUND) pass through unchanged.
 */
@Component
public class CatalogServiceClient implements MarketplaceService.CatalogDirectory {

    record SkuResponse(UUID id, Boolean priced) {
    }

    record ProductResponse(UUID id, String status, List<SkuResponse> skus) {
    }

    private final RestClient catalog;

    public CatalogServiceClient(RestClient.Builder builder, @Value("${waver.services.catalog-service}") String baseUrl) {
        this.catalog = RemoteProblem.handled(builder.clone()).baseUrl(baseUrl).requestInterceptor(new BearerRelayInterceptor()).build();
    }

    @Override
    public ProductOffer offer(UUID productId) {
        ProductResponse p = catalog.get().uri("/v1/products/{id}", productId).retrieve().body(ProductResponse.class);
        if (p == null) {
            throw new IllegalStateException("catalog-service returned no product");
        }
        List<SkuResponse> skus = p.skus() == null ? List.of() : p.skus();
        return new ProductOffer(p.id(), "PUBLISHED".equals(p.status()),
                skus.stream().map(SkuResponse::id).collect(Collectors.toSet()),
                skus.stream().filter(s -> Boolean.TRUE.equals(s.priced())).map(SkuResponse::id).collect(Collectors.toSet()));
    }
}
