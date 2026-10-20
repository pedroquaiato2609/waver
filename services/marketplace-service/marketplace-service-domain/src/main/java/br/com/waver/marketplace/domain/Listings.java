package br.com.waver.marketplace.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port. Row-Level Security: WAVER staff see every listing; a reseller sees only published listings whose
 * availability rule matches its organization or tier, and only their current version. Writes are staff only.
 */
public interface Listings {

    void insert(Listing listing);

    /** Writes the listing, its versions and its availability rule. Optimistic: false when the stored version differs. */
    boolean update(Listing listing, long expectedVersion);

    Optional<Listing> findById(UUID id);

    boolean existsByKey(String listingKey);

    /** Newest first, cursor = last seen id; for a reseller, only what is available to it. */
    List<Listing> list(Listing.Status status, UUID afterId, int limit);
}
