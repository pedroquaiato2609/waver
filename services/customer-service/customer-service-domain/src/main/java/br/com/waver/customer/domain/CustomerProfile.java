package br.com.waver.customer.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * CustomerProfile (RFC-0017 §3): a customer in the portfolio of the reseller that registered it (D-01: the
 * customer belongs commercially to that reseller). {@code org_id} is the customer's own organization.
 *
 * Three status fields are kept apart (Commercial §13): {@code lifecycleStage} (commercial, owned here),
 * {@code accessStatus} (from suspension cases) and {@code financialStatus} (projection of billing). The status
 * shown in the apps is derived from them, see {@link #displayStatus()}.
 */
public final class CustomerProfile {

    public enum LifecycleStage { PROSPECT, CONTRACTING, PROVISIONING, ACTIVE, TERMINATED }

    public enum AccessStatus { NORMAL, RESTRICTED, SUSPENDED }

    public enum FinancialStatus { CURRENT, PAST_DUE, GRACE_PERIOD, IN_COLLECTION }

    public enum DisplayStatus { PROSPECT, CONTRACTING, PROVISIONING, ACTIVE, PAST_DUE, RESTRICTED, SUSPENDED, TERMINATED }

    private final UUID id;
    private final OrgId customerOrgId;
    private final OrgId resellerOrgId;
    private final String legalName;
    private final String tradeName;
    private final TaxId taxId;
    private final String segment;
    private final List<CustomerContact> contacts;
    private final LifecycleStage lifecycleStage;
    private final AccessStatus accessStatus;
    private final FinancialStatus financialStatus;
    private final long version;
    private final Instant createdAt;

    private CustomerProfile(UUID id, OrgId customerOrgId, OrgId resellerOrgId, String legalName, String tradeName, TaxId taxId,
                            String segment, List<CustomerContact> contacts, LifecycleStage lifecycleStage,
                            AccessStatus accessStatus, FinancialStatus financialStatus, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.customerOrgId = Objects.requireNonNull(customerOrgId);
        this.resellerOrgId = Objects.requireNonNull(resellerOrgId);
        this.legalName = Objects.requireNonNull(legalName);
        this.tradeName = tradeName;
        this.taxId = Objects.requireNonNull(taxId);
        this.segment = segment;
        this.contacts = List.copyOf(contacts);
        this.lifecycleStage = Objects.requireNonNull(lifecycleStage);
        this.accessStatus = Objects.requireNonNull(accessStatus);
        this.financialStatus = Objects.requireNonNull(financialStatus);
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /** Validated registration data, checked before the customer organization is created in tenant-service. */
    public record Registration(String legalName, String tradeName, TaxId taxId, String segment, CustomerContact admin) {

        public static Registration of(String legalName, String tradeName, TaxId taxId, String segment, CustomerContact admin) {
            if (admin == null || admin.type() != CustomerContact.Type.ADMIN) {
                throw new DomainException(DomainException.Kind.VALIDATION, "CUSTOMER_ADMIN_CONTACT_REQUIRED",
                        "The customer administrator (who will receive the Workspace access) is required.");
            }
            String seg = segment == null || segment.isBlank() ? null : segment.strip();
            if (seg != null && seg.length() > 80) {
                throw new DomainException(DomainException.Kind.VALIDATION, "CUSTOMER_SEGMENT_INVALID", "Segment is too long.");
            }
            return new Registration(name(legalName, true), tradeName == null || tradeName.isBlank() ? null : name(tradeName, false),
                    Objects.requireNonNull(taxId), seg, admin);
        }
    }

    /** First registration: PROSPECT, normal access, current, first ownership row opens with it. */
    public static CustomerProfile register(OrgId customerOrgId, OrgId resellerOrgId, Registration r, Instant now) {
        return new CustomerProfile(Ids.newId(), customerOrgId, resellerOrgId, r.legalName(), r.tradeName(), r.taxId(),
                r.segment(), List.of(r.admin()), LifecycleStage.PROSPECT, AccessStatus.NORMAL, FinancialStatus.CURRENT, 0, now);
    }

    public static CustomerProfile restore(UUID id, OrgId customerOrgId, OrgId resellerOrgId, String legalName, String tradeName,
                                          TaxId taxId, String segment, List<CustomerContact> contacts, LifecycleStage stage,
                                          AccessStatus access, FinancialStatus financial, long version, Instant createdAt) {
        return new CustomerProfile(id, customerOrgId, resellerOrgId, legalName, tradeName, taxId, segment, contacts, stage,
                access, financial, version, createdAt);
    }

    /**
     * RFC-0017 §3 derivation: TERMINATED if the stage says so; else SUSPENDED/RESTRICTED by access; else PAST_DUE
     * when commercially ACTIVE and financially overdue; else the stage. Active and past due at the same time is valid.
     */
    public DisplayStatus displayStatus() {
        if (lifecycleStage == LifecycleStage.TERMINATED) {
            return DisplayStatus.TERMINATED;
        }
        if (accessStatus == AccessStatus.SUSPENDED) {
            return DisplayStatus.SUSPENDED;
        }
        if (accessStatus == AccessStatus.RESTRICTED) {
            return DisplayStatus.RESTRICTED;
        }
        if (lifecycleStage == LifecycleStage.ACTIVE
                && (financialStatus == FinancialStatus.PAST_DUE || financialStatus == FinancialStatus.GRACE_PERIOD)) {
            return DisplayStatus.PAST_DUE;
        }
        return DisplayStatus.valueOf(lifecycleStage.name());
    }

    public Optional<CustomerContact> admin() {
        return contacts.stream().filter(c -> c.type() == CustomerContact.Type.ADMIN).findFirst();
    }

    private static String name(String value, boolean required) {
        String v = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if ((required || !v.isEmpty()) && (v.length() < 2 || v.length() > 200)) {
            throw new DomainException(DomainException.Kind.VALIDATION, required ? "CUSTOMER_LEGAL_NAME_INVALID" : "CUSTOMER_TRADE_NAME_INVALID",
                    "Name must have between 2 and 200 characters.");
        }
        return v;
    }

    public UUID id() { return id; }
    public OrgId customerOrgId() { return customerOrgId; }
    public OrgId resellerOrgId() { return resellerOrgId; }
    public String legalName() { return legalName; }
    public Optional<String> tradeName() { return Optional.ofNullable(tradeName); }
    public TaxId taxId() { return taxId; }
    public Optional<String> segment() { return Optional.ofNullable(segment); }
    public List<CustomerContact> contacts() { return contacts; }
    public LifecycleStage lifecycleStage() { return lifecycleStage; }
    public AccessStatus accessStatus() { return accessStatus; }
    public FinancialStatus financialStatus() { return financialStatus; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
