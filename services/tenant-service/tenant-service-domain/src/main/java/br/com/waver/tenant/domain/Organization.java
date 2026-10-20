package br.com.waver.tenant.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Organization aggregate (RFC-0003 §1): WAVER (PLATFORM), a reseller or a customer.
 * Commercial facts (reseller profile, customer ownership) live in reseller-service and customer-service;
 * this aggregate only holds the tenancy structure.
 */
public final class Organization {

    public enum Status { PENDING, ACTIVE, SUSPENDED, TERMINATED }

    private static final int LEGAL_NAME_MIN = 2;
    private static final int LEGAL_NAME_MAX = 200;

    private final OrgId id;
    private final OrgType type;
    private final OrgId parentId;
    private final String legalName;
    private final Status status;
    private final long version;
    private final Instant createdAt;

    private Organization(OrgId id, OrgType type, OrgId parentId, String legalName, Status status, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.type = Objects.requireNonNull(type);
        this.parentId = parentId;
        this.legalName = Objects.requireNonNull(legalName);
        this.status = Objects.requireNonNull(status);
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /**
     * Registers a new organization under {@code parent}. Reseller and customer organizations start PENDING:
     * provisioning-service activates them (MICROSERVICES_MAP v1.3, "Organization creation").
     */
    public static Organization register(OrgType type, Organization parent, String legalName, Instant now) {
        Objects.requireNonNull(parent, "parent");
        if (type == OrgType.PLATFORM) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "TENANT_PLATFORM_ORG_EXISTS",
                    "There is exactly one PLATFORM organization.");
        }
        if (!type.canBeChildOf(parent.type)) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "TENANT_INVALID_HIERARCHY",
                    "A " + type + " organization cannot belong to a " + parent.type + " organization.");
        }
        if (parent.status != Status.ACTIVE) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "TENANT_PARENT_NOT_ACTIVE",
                    "The parent organization is not active.");
        }
        return new Organization(new OrgId(Ids.newId()), type, parent.id, normalizeLegalName(legalName), Status.PENDING, 0, now);
    }

    /**
     * PENDING -> ACTIVE: the organization may operate (a reseller after onboarding, a customer after provisioning).
     * Activating an ACTIVE organization changes nothing, so redelivered events are harmless.
     */
    public Organization activate() {
        if (status == Status.ACTIVE) {
            return this;
        }
        if (status != Status.PENDING) {
            throw new DomainException(DomainException.Kind.BUSINESS_RULE, "TENANT_INVALID_STATUS_TRANSITION",
                    "A " + status + " organization cannot be activated.");
        }
        return new Organization(id, type, parentId, legalName, Status.ACTIVE, version + 1, createdAt);
    }

    public static Organization restore(OrgId id, OrgType type, OrgId parentId, String legalName, Status status, long version, Instant createdAt) {
        return new Organization(id, type, parentId, legalName, status, version, createdAt);
    }

    private static String normalizeLegalName(String legalName) {
        String value = legalName == null ? "" : legalName.strip().replaceAll("\\s+", " ");
        if (value.length() < LEGAL_NAME_MIN || value.length() > LEGAL_NAME_MAX) {
            throw new DomainException(DomainException.Kind.VALIDATION, "TENANT_LEGAL_NAME_INVALID",
                    "Legal name must have between " + LEGAL_NAME_MIN + " and " + LEGAL_NAME_MAX + " characters.");
        }
        return value;
    }

    public OrgId id() { return id; }
    public OrgType type() { return type; }
    public Optional<OrgId> parentId() { return Optional.ofNullable(parentId); }
    public String legalName() { return legalName; }
    public Status status() { return status; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
