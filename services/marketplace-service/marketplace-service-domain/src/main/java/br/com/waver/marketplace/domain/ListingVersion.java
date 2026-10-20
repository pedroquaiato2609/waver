package br.com.waver.marketplace.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ListingVersion (RFC-0017 §5): what a listing offers (catalog SKUs) and its commercial terms. Editable while
 * DRAFT; immutable once submitted. PUBLISHED is the current offer; the previous published version becomes
 * SUPERSEDED when a new one is published.
 */
public final class ListingVersion {

    public enum Status { DRAFT, REVIEW, PUBLISHED, SUPERSEDED }

    /** The editable part of a version. */
    public record Content(List<UUID> skuIds, String requirements, String docsRef, String supportPolicy) {

        public Content {
            skuIds = skuIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(skuIds.stream().filter(Objects::nonNull).toList()));
            requirements = text(requirements, 2000, "requirements");
            docsRef = text(docsRef, 500, "documentation reference");
            supportPolicy = text(supportPolicy, 500, "support policy");
        }

        private static String text(String value, int max, String what) {
            String v = value == null || value.isBlank() ? null : value.strip();
            if (v != null && v.length() > max) {
                throw new DomainException(DomainException.Kind.VALIDATION, "MARKETPLACE_VERSION_TEXT_TOO_LONG", "The " + what + " is too long.");
            }
            return v;
        }
    }

    private final UUID id;
    private final int versionNo;
    private Content content;
    private Status status;
    private Instant submittedAt;
    private String submittedBy;
    private Instant publishedAt;
    private String approvedBy;
    private String returnReason;

    private ListingVersion(UUID id, int versionNo, Content content, Status status, Instant submittedAt, String submittedBy,
                           Instant publishedAt, String approvedBy, String returnReason) {
        this.id = Objects.requireNonNull(id);
        this.versionNo = versionNo;
        this.content = Objects.requireNonNull(content);
        this.status = Objects.requireNonNull(status);
        this.submittedAt = submittedAt;
        this.submittedBy = submittedBy;
        this.publishedAt = publishedAt;
        this.approvedBy = approvedBy;
        this.returnReason = returnReason;
    }

    static ListingVersion draft(int versionNo, Content content) {
        return new ListingVersion(Ids.newId(), versionNo, content, Status.DRAFT, null, null, null, null, null);
    }

    public static ListingVersion restore(UUID id, int versionNo, Content content, Status status, Instant submittedAt, String submittedBy,
                                         Instant publishedAt, String approvedBy, String returnReason) {
        return new ListingVersion(id, versionNo, content, status, submittedAt, submittedBy, publishedAt, approvedBy, returnReason);
    }

    void revise(Content c) {
        require(Status.DRAFT);
        content = c;
    }

    void submit(String actor, Instant now) {
        require(Status.DRAFT);
        status = Status.REVIEW;
        submittedAt = now;
        submittedBy = actor;
        returnReason = null;
    }

    void publish(String approver, Instant now) {
        require(Status.REVIEW);
        status = Status.PUBLISHED;
        publishedAt = now;
        approvedBy = approver;
    }

    void returnToDraft(String reason) {
        require(Status.REVIEW);
        status = Status.DRAFT;
        returnReason = reason;
    }

    void supersede() {
        require(Status.PUBLISHED);
        status = Status.SUPERSEDED;
    }

    private void require(Status expected) {
        if (status != expected) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "MARKETPLACE_INVALID_TRANSITION",
                    "This version is " + status + "; the action needs " + expected + ".");
        }
    }

    public UUID id() { return id; }
    public int versionNo() { return versionNo; }
    public Content content() { return content; }
    public Status status() { return status; }
    public Optional<Instant> submittedAt() { return Optional.ofNullable(submittedAt); }
    public Optional<String> submittedBy() { return Optional.ofNullable(submittedBy); }
    public Optional<Instant> publishedAt() { return Optional.ofNullable(publishedAt); }
    public Optional<String> approvedBy() { return Optional.ofNullable(approvedBy); }
    public Optional<String> returnReason() { return Optional.ofNullable(returnReason); }
}
