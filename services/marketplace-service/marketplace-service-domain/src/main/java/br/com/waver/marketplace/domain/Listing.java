package br.com.waver.marketplace.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Listing (RFC-0017 §5): how a catalog product is offered to resellers. Only WAVER publishes in v1 (D-10,
 * publisher PLATFORM). Lifecycle DRAFT → REVIEW → PUBLISHED, REVIEW → DRAFT when returned. A new version of a
 * published listing goes through review on its own while the listing stays PUBLISHED with its current version.
 *
 * Publication needs approval LISTING_PUBLICATION; until approval-service exists WAVER Product Ops approves
 * directly. Certification (security review, docs, support readiness) joins the guard in a later slice.
 */
public final class Listing {

    public enum Status { DRAFT, REVIEW, PUBLISHED }

    public enum Decision { APPROVE, RETURN }

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{2,62}$");

    private final UUID id;
    private final String listingKey;
    private final UUID productId;
    private final String category;
    private final String title;
    private final String summary;
    private final List<ListingVersion> versions;
    private Status status;
    private UUID currentVersionId;
    private AvailabilityRule availability;
    private long version;
    private final Instant createdAt;

    private Listing(UUID id, String listingKey, UUID productId, String category, String title, String summary, List<ListingVersion> versions,
                    Status status, UUID currentVersionId, AvailabilityRule availability, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.listingKey = Objects.requireNonNull(listingKey);
        this.productId = Objects.requireNonNull(productId);
        this.category = Objects.requireNonNull(category);
        this.title = Objects.requireNonNull(title);
        this.summary = summary;
        this.versions = new ArrayList<>(versions);
        this.versions.sort(Comparator.comparingInt(ListingVersion::versionNo));
        this.status = Objects.requireNonNull(status);
        this.currentVersionId = currentVersionId;
        this.availability = availability;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Listing draft(String listingKey, UUID productId, String category, String title, String summary,
                                ListingVersion.Content content, Instant now) {
        String key = listingKey == null ? "" : listingKey.strip();
        if (!KEY.matcher(key).matches()) {
            throw invalid("MARKETPLACE_LISTING_KEY_INVALID", "Listing key: 3 to 63 lowercase letters, digits or hyphens.");
        }
        if (productId == null) {
            throw invalid("MARKETPLACE_PRODUCT_REQUIRED", "Inform the catalog product of the listing.");
        }
        String cat = text(category, 2, 60, "MARKETPLACE_CATEGORY_INVALID", "Category must have between 2 and 60 characters.");
        String t = text(title, 2, 120, "MARKETPLACE_TITLE_INVALID", "Title must have between 2 and 120 characters.");
        String s = summary == null || summary.isBlank() ? null
                : text(summary, 2, 600, "MARKETPLACE_SUMMARY_INVALID", "Summary must have up to 600 characters.");
        return new Listing(Ids.newId(), key, productId, cat, t, s, List.of(ListingVersion.draft(1, content)), Status.DRAFT, null, null, 0, now);
    }

    public static Listing restore(UUID id, String listingKey, UUID productId, String category, String title, String summary,
                                  List<ListingVersion> versions, Status status, UUID currentVersionId, AvailabilityRule availability,
                                  long version, Instant createdAt) {
        return new Listing(id, listingKey, productId, category, title, summary, versions, status, currentVersionId, availability,
                version, createdAt);
    }

    /** Edits the open draft version, or opens the next version after a published one. Never while one is in review. */
    public ListingVersion revise(ListingVersion.Content content) {
        Optional<ListingVersion> open = openVersion();
        if (open.isPresent() && open.get().status() == ListingVersion.Status.REVIEW) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "MARKETPLACE_VERSION_IN_REVIEW",
                    "A version is in review. Return it to draft before changing it.");
        }
        ListingVersion v;
        if (open.isPresent()) {
            v = open.get();
            v.revise(content);
        } else {
            v = ListingVersion.draft(versions.getLast().versionNo() + 1, content);
            versions.add(v);
        }
        version++;
        return v;
    }

    /** DRAFT → REVIEW for the open version: needs SKUs, all of them in the published product and priced. */
    public ListingVersion submit(ProductOffer offer, String actor, Instant now) {
        ListingVersion v = openVersion().filter(x -> x.status() == ListingVersion.Status.DRAFT).orElseThrow(() ->
                new DomainException(DomainException.Kind.BUSINESS_RULE, "MARKETPLACE_NOTHING_TO_SUBMIT", "There is no draft version to submit."));
        requirePublishable(v, offer);
        v.submit(actor, now);
        if (status == Status.DRAFT) {
            status = Status.REVIEW;
        }
        version++;
        return v;
    }

    /**
     * Review decision on the version in REVIEW. APPROVE publishes it (the guard is checked again, prices may have
     * changed meanwhile) and supersedes the previous one; RETURN sends it back to draft with a reason.
     */
    public ListingVersion decide(Decision decision, ProductOffer offer, String actor, String reason, Instant now) {
        ListingVersion v = openVersion().filter(x -> x.status() == ListingVersion.Status.REVIEW).orElseThrow(() ->
                new DomainException(DomainException.Kind.BUSINESS_RULE, "MARKETPLACE_NOTHING_IN_REVIEW", "There is no version in review."));
        if (decision == Decision.APPROVE) {
            requirePublishable(v, offer);
            currentVersion().ifPresent(ListingVersion::supersede);
            v.publish(actor, now);
            currentVersionId = v.id();
            status = Status.PUBLISHED;
        } else {
            String r = reason == null ? "" : reason.strip();
            if (r.length() < 5 || r.length() > 500) {
                throw invalid("MARKETPLACE_RETURN_REASON_REQUIRED", "Explain in at least 5 characters what must change.");
            }
            v.returnToDraft(r);
            if (currentVersionId == null) {
                status = Status.DRAFT;
            }
        }
        version++;
        return v;
    }

    public void setAvailability(AvailabilityRule rule) {
        availability = Objects.requireNonNull(rule);
        version++;
    }

    /** Published, with a rule that lets this reseller sell it now. */
    public boolean availableTo(ResellerRef reseller, Instant at) {
        return status == Status.PUBLISHED && availability != null && availability.allows(reseller, at);
    }

    public Optional<ListingVersion> currentVersion() {
        return versions.stream().filter(v -> v.id().equals(currentVersionId)).findFirst();
    }

    /** The version being prepared or reviewed, if any. */
    public Optional<ListingVersion> openVersion() {
        return versions.stream().filter(v -> v.status() == ListingVersion.Status.DRAFT || v.status() == ListingVersion.Status.REVIEW).findFirst();
    }

    private void requirePublishable(ListingVersion v, ProductOffer offer) {
        if (v.content().skuIds().isEmpty()) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "MARKETPLACE_VERSION_WITHOUT_SKU", "Choose at least one SKU to offer.");
        }
        if (offer == null || !offer.productId().equals(productId) || !offer.published()) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "MARKETPLACE_PRODUCT_NOT_PUBLISHED",
                    "The catalog product must be published first.");
        }
        if (!offer.skuIds().containsAll(v.content().skuIds())) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "MARKETPLACE_SKU_NOT_IN_PRODUCT",
                    "Every SKU of the listing must belong to its product.");
        }
        if (!offer.pricedSkuIds().containsAll(v.content().skuIds())) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "MARKETPLACE_SKU_WITHOUT_PRICE",
                    "Every SKU of the listing needs a price in an active price book.");
        }
    }

    private static String text(String value, int min, int max, String code, String message) {
        String v = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (v.length() < min || v.length() > max) {
            throw invalid(code, message);
        }
        return v;
    }

    private static DomainException invalid(String code, String message) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message);
    }

    public UUID id() { return id; }
    public String listingKey() { return listingKey; }
    public UUID productId() { return productId; }
    public String category() { return category; }
    public String title() { return title; }
    public Optional<String> summary() { return Optional.ofNullable(summary); }
    public List<ListingVersion> versions() { return List.copyOf(versions); }
    public Status status() { return status; }
    public Optional<UUID> currentVersionId() { return Optional.ofNullable(currentVersionId); }
    public Optional<AvailabilityRule> availability() { return Optional.ofNullable(availability); }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
