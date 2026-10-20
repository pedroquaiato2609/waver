package br.com.waver.catalog.domain;

import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port. Row-Level Security: WAVER staff see every book; a reseller sees active and retired books of the
 * global scope, of its tier and of itself, never drafts nor another reseller's books.
 */
public interface PriceBooks {

    void insert(PriceBook book);

    /** Writes status, activation and entries. Optimistic: false when the stored version differs. */
    boolean update(PriceBook book, long expectedVersion);

    Optional<PriceBook> findById(UUID id);

    Optional<PriceBook> findActive(PriceScope scope, String scopeRef, Currency currency);

    /** Active books that price at least one of the SKUs (visible to the caller). */
    List<PriceBook> findActivePricing(Collection<UUID> skuIds);

    List<PriceBook> list(PriceBook.Status status, UUID afterId, int limit);
}
