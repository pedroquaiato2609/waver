package br.com.waver.reseller.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ResellerProfile aggregate with its onboarding checklist (RFC-0017 §2). Owned by the PLATFORM organization;
 * {@code resellerOrgId} is set once the reseller organization exists in tenant-service.
 *
 * <pre>
 * LEAD -> UNDER_REVIEW -> APPROVED -> ACTIVE
 *              \-> REJECTED
 * </pre>
 * Every transition returns the fact it produced; the caller persists the profile and publishes the fact in
 * the same transaction.
 */
public final class ResellerProfile {

    /** Who did it and, for checklist items, where the evidence is (document reference, ticket). */
    public record ChecklistEntry(Instant doneAt, String doneBy, String evidenceRef) {
    }

    public record Decision(String decidedBy, String approvalRef, String reason, Instant decidedAt) {
    }

    private static final int NAME_MIN = 2;
    private static final int NAME_MAX = 200;

    private final UUID id;
    private final String legalName;
    private final String tradeName;
    private final TaxId taxId;
    private final List<Contact> contacts;
    private final Map<ChecklistItem, ChecklistEntry> checklist;
    private final Instant createdAt;
    private OnboardingStatus status;
    private OrgId resellerOrgId;
    private Decision decision;
    private long version;

    private ResellerProfile(UUID id, String legalName, String tradeName, TaxId taxId, List<Contact> contacts,
                            Map<ChecklistItem, ChecklistEntry> checklist, OnboardingStatus status, OrgId resellerOrgId,
                            Decision decision, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.legalName = Objects.requireNonNull(legalName);
        this.tradeName = tradeName;
        this.taxId = Objects.requireNonNull(taxId);
        this.contacts = new ArrayList<>(contacts);
        this.checklist = checklist.isEmpty() ? new EnumMap<>(ChecklistItem.class) : new EnumMap<>(checklist);
        this.status = Objects.requireNonNull(status);
        this.resellerOrgId = resellerOrgId;
        this.decision = decision;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** A new lead. The owner is the person who will receive the Reseller Owner invitation on approval. */
    public static ResellerProfile registerLead(String legalName, String tradeName, TaxId taxId, Contact owner,
                                               List<Contact> otherContacts, Instant now) {
        if (owner == null || owner.type() != Contact.Type.OWNER) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RESELLER_OWNER_REQUIRED",
                    "The reseller owner (who receives the first access) is required.");
        }
        List<Contact> contacts = new ArrayList<>();
        contacts.add(owner);
        for (Contact c : otherContacts) {
            if (c.type() == Contact.Type.OWNER) {
                throw new DomainException(DomainException.Kind.VALIDATION, "RESELLER_OWNER_DUPLICATED", "A reseller has one owner.");
            }
            contacts.add(c);
        }
        return new ResellerProfile(Ids.newId(), name(legalName, "RESELLER_LEGAL_NAME_INVALID", true),
                tradeName == null || tradeName.isBlank() ? null : name(tradeName, "RESELLER_TRADE_NAME_INVALID", false),
                taxId, contacts, Map.of(), OnboardingStatus.LEAD, null, null, 0, now);
    }

    public static ResellerProfile restore(UUID id, String legalName, String tradeName, TaxId taxId, List<Contact> contacts,
                                          Map<ChecklistItem, ChecklistEntry> checklist, OnboardingStatus status,
                                          OrgId resellerOrgId, Decision decision, long version, Instant createdAt) {
        return new ResellerProfile(id, legalName, tradeName, taxId, contacts, checklist, status, resellerOrgId, decision,
                version, createdAt);
    }

    /** LEAD -> UNDER_REVIEW. Guard: owner and a commercial contact are known (contacts complete). */
    public ResellerEvent startReview(Instant now) {
        requireStatus(OnboardingStatus.LEAD, "start the review");
        if (contact(Contact.Type.COMMERCIAL).isEmpty()) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "RESELLER_CONTACTS_INCOMPLETE",
                    "Add the commercial contact before starting the review.");
        }
        status = OnboardingStatus.UNDER_REVIEW;
        version++;
        return new ResellerEvent.ReviewStarted(now);
    }

    /** Marks a manual checklist item. Each item belongs to a phase: due diligence happens during the review. */
    public ResellerEvent completeChecklistItem(ChecklistItem item, String doneBy, String evidenceRef, Instant now) {
        if (item.automatic()) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "RESELLER_CHECKLIST_ITEM_AUTOMATIC",
                    item + " is completed by the onboarding itself.");
        }
        Set<OnboardingStatus> allowed = switch (item) {
            case DUE_DILIGENCE -> Set.of(OnboardingStatus.UNDER_REVIEW);
            case AGREEMENT_SIGNED -> Set.of(OnboardingStatus.UNDER_REVIEW, OnboardingStatus.APPROVED);
            default -> Set.of(OnboardingStatus.APPROVED);
        };
        if (!allowed.contains(status)) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "RESELLER_CHECKLIST_ITEM_OUT_OF_PHASE",
                    item + " cannot be completed while the reseller is " + status + ".");
        }
        String evidence = evidenceRef == null || evidenceRef.isBlank() ? null : evidenceRef.strip();
        if (evidence != null && evidence.length() > 500) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RESELLER_EVIDENCE_INVALID", "Evidence reference is too long.");
        }
        if (checklist.containsKey(item)) {
            return new ResellerEvent.ChecklistItemCompleted(item, checklist.get(item).evidenceRef(), checklist.get(item).doneAt());
        }
        checklist.put(item, new ChecklistEntry(now, doneBy, evidence));
        version++;
        return new ResellerEvent.ChecklistItemCompleted(item, evidence, now);
    }

    /**
     * UNDER_REVIEW -> APPROVED (RESELLER_APPROVAL). Until approval-service exists the decision is taken directly by
     * WAVER Commercial and {@code approvalRef} is null; the guard is the same.
     */
    public ResellerEvent approve(String decidedBy, String approvalRef, Instant now) {
        requireStatus(OnboardingStatus.UNDER_REVIEW, "approve");
        if (!checklist.containsKey(ChecklistItem.DUE_DILIGENCE)) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "RESELLER_DUE_DILIGENCE_PENDING",
                    "Finish the due diligence before approving.");
        }
        status = OnboardingStatus.APPROVED;
        decision = new Decision(decidedBy, approvalRef, null, now);
        checklist.put(ChecklistItem.COMMERCIAL_APPROVAL, new ChecklistEntry(now, decidedBy, approvalRef));
        version++;
        return new ResellerEvent.Approved(approvalRef, now);
    }

    /** UNDER_REVIEW -> REJECTED, with the reason shown to WAVER Commercial. */
    public ResellerEvent reject(String decidedBy, String approvalRef, String reason, Instant now) {
        requireStatus(OnboardingStatus.UNDER_REVIEW, "reject");
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5 || why.length() > 1000) {
            throw new DomainException(DomainException.Kind.VALIDATION, "RESELLER_REJECTION_REASON_REQUIRED",
                    "Explain the rejection (5 to 1000 characters).");
        }
        status = OnboardingStatus.REJECTED;
        decision = new Decision(decidedBy, approvalRef, why, now);
        version++;
        return new ResellerEvent.Rejected(approvalRef, why, now);
    }

    /** Workflow step after approval: the reseller organization exists in tenant-service. Idempotent. */
    public Optional<ResellerEvent> linkOrganization(OrgId orgId, Instant now) {
        requireStatus(OnboardingStatus.APPROVED, "link the organization");
        if (resellerOrgId != null) {
            if (!resellerOrgId.equals(orgId)) {
                throw new DomainException(DomainException.Kind.CONFLICT, "RESELLER_ORGANIZATION_MISMATCH",
                        "The reseller is already linked to another organization.");
            }
            return Optional.empty();
        }
        resellerOrgId = orgId;
        checklist.put(ChecklistItem.ORGANIZATION_CREATED, new ChecklistEntry(now, "reseller-service", orgId.toString()));
        version++;
        return Optional.of(new ResellerEvent.OrganizationLinked(orgId, now));
    }

    /** Workflow step after the organization exists: the Reseller Owner received the invitation. Idempotent. */
    public Optional<ResellerEvent> recordOwnerInvited(String invitationRef, Instant now) {
        requireStatus(OnboardingStatus.APPROVED, "record the owner invitation");
        if (resellerOrgId == null) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "RESELLER_ORGANIZATION_MISSING",
                    "The reseller organization does not exist yet.");
        }
        if (checklist.containsKey(ChecklistItem.ADMIN_INVITED)) {
            return Optional.empty();
        }
        checklist.put(ChecklistItem.ADMIN_INVITED, new ChecklistEntry(now, "reseller-service", invitationRef));
        version++;
        return Optional.of(new ResellerEvent.OwnerInvited(now));
    }

    /** APPROVED -> ACTIVE. Guard: every item of the activation policy is done and the organization exists. */
    public ResellerEvent activate(Set<ChecklistItem> requiredItems, Instant now) {
        requireStatus(OnboardingStatus.APPROVED, "activate");
        Set<ChecklistItem> missing = requiredItems.stream().filter(i -> !checklist.containsKey(i))
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(ChecklistItem.class)));
        if (resellerOrgId == null) {
            missing.add(ChecklistItem.ORGANIZATION_CREATED);
        }
        if (!missing.isEmpty()) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "RESELLER_CHECKLIST_INCOMPLETE",
                    "Onboarding items still open: " + missing.stream().map(Enum::name).collect(Collectors.joining(", ")) + ".");
        }
        status = OnboardingStatus.ACTIVE;
        version++;
        return new ResellerEvent.Activated(resellerOrgId, now);
    }

    /** What the onboarding workflow still has to do on its own. */
    public boolean awaitsOrganization() {
        return status == OnboardingStatus.APPROVED && resellerOrgId == null;
    }

    public boolean awaitsOwnerInvitation() {
        return status == OnboardingStatus.APPROVED && resellerOrgId != null && !checklist.containsKey(ChecklistItem.ADMIN_INVITED);
    }

    public Optional<Contact> contact(Contact.Type type) {
        return contacts.stream().filter(c -> c.type() == type).findFirst();
    }

    public Contact owner() {
        return contact(Contact.Type.OWNER).orElseThrow();
    }

    private void requireStatus(OnboardingStatus expected, String action) {
        if (status != expected) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "RESELLER_INVALID_TRANSITION",
                    "Cannot " + action + " a reseller that is " + status + ".");
        }
    }

    private static String name(String value, String code, boolean required) {
        String v = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if ((required || !v.isEmpty()) && (v.length() < NAME_MIN || v.length() > NAME_MAX)) {
            throw new DomainException(DomainException.Kind.VALIDATION, code,
                    "Name must have between " + NAME_MIN + " and " + NAME_MAX + " characters.");
        }
        return v;
    }

    public UUID id() { return id; }
    public String legalName() { return legalName; }
    public Optional<String> tradeName() { return Optional.ofNullable(tradeName); }
    public TaxId taxId() { return taxId; }
    public List<Contact> contacts() { return Collections.unmodifiableList(contacts); }
    public Map<ChecklistItem, ChecklistEntry> checklist() { return Collections.unmodifiableMap(checklist); }
    public OnboardingStatus status() { return status; }
    public Optional<OrgId> resellerOrgId() { return Optional.ofNullable(resellerOrgId); }
    public Optional<Decision> decision() { return Optional.ofNullable(decision); }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
