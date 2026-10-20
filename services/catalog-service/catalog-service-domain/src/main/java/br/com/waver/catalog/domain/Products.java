package br.com.waver.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port. Row-Level Security: WAVER staff see every product; resellers only published ones. Writes are staff only. */
public interface Products {

    void insert(Product product);

    void insertSku(Sku sku);

    /** Optimistic: false when the stored version is not {@code expectedVersion}. */
    boolean update(Product product, long expectedVersion);

    Optional<Product> findById(UUID id);

    boolean existsByKey(String productKey);

    boolean existsSkuCode(String skuCode);

    /** SKUs by ID, only those the caller may see. */
    List<Sku> findSkus(Collection<UUID> skuIds);

    /** Newest first, cursor = last seen id. */
    List<Product> list(Product.Status status, UUID afterId, int limit);
}
