package br.com.waver.marketplace.domain;

import java.util.Set;
import java.util.UUID;

/**
 * What catalog-service says about the product behind a listing: whether it is published, its SKUs, and which of
 * them have a price in an active book. A listing is publishable only over a published, priced offer.
 */
public record ProductOffer(UUID productId, boolean published, Set<UUID> skuIds, Set<UUID> pricedSkuIds) {

    public ProductOffer {
        skuIds = Set.copyOf(skuIds);
        pricedSkuIds = Set.copyOf(pricedSkuIds);
    }
}
