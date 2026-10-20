package br.com.waver.tenant.application;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.tenant.domain.Organization;
import br.com.waver.tenant.domain.OrganizationRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for organizations. Authorization rules come from RFC-0004 (roles) and RFC-0003 (hierarchy). */
public class OrganizationService {

    /** The reseller onboarding workflow creates the reseller organization when a reseller is approved (RFC-0017 §2). */
    static final String RESELLER_ONBOARDING_CLIENT = "svc-reseller-service";

    public record RegisterOrganization(OrgType type, String legalName, OrgId parentId) {
    }

    /** {@code replayed} is true when an earlier request with the same Idempotency-Key already created it. */
    public record Registration(Organization organization, boolean replayed) {
    }

    private final OrganizationRepository organizations;
    private final OrganizationEvents events;
    private final Clock clock;

    public OrganizationService(OrganizationRepository organizations, OrganizationEvents events, Clock clock) {
        this.organizations = organizations;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Registration register(TenantContext actor, RegisterOrganization command, String idempotencyKey) {
        OrgId parentId = command.parentId() != null ? command.parentId() : actor.orgId();
        requireMayCreate(actor, command.type(), parentId);

        if (idempotencyKey != null) {
            Optional<Organization> earlier = organizations.findByIdempotencyKey(parentId, idempotencyKey);
            if (earlier.isPresent()) {
                return new Registration(earlier.get(), true);
            }
        }
        Organization parent = organizations.findById(parentId)
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "TENANT_PARENT_NOT_FOUND",
                        "Parent organization not found."));
        Organization organization = Organization.register(command.type(), parent, command.legalName(), clock.instant());

        organizations.insert(organization, idempotencyKey);
        events.organizationCreated(organization);
        events.audit("tenant.organization.create", organization, Map.of("orgType", organization.type().name()));
        return new Registration(organization, false);
    }

    /**
     * Activates an organization (ResellerActivated for resellers, ProvisioningSucceeded for customers).
     * Runs as the platform service principal from event consumers; idempotent.
     */
    @Transactional
    public Organization activate(OrgId id) {
        Organization current = organizations.findById(id)
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "TENANT_ORGANIZATION_NOT_FOUND",
                        "Organization not found."));
        Organization activated = current.activate();
        if (activated == current) {
            return current;
        }
        if (!organizations.update(activated, current.version())) {
            throw new DomainException(DomainException.Kind.CONFLICT, "TENANT_CONCURRENT_UPDATE",
                    "The organization changed meanwhile. Try again.");
        }
        events.statusChanged(activated, current.status());
        events.audit("tenant.organization.activate", activated, Map.of("fromStatus", current.status().name()));
        return activated;
    }

    @Transactional(readOnly = true)
    public Optional<Organization> get(OrgId id) {
        return organizations.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Organization> list(OrgId afterId, int limit) {
        return organizations.findVisible(afterId, Math.clamp(limit, 1, 200));
    }

    /**
     * WAVER commercial staff and the reseller onboarding workflow create resellers; reseller owners, reseller sales
     * and WAVER staff create customers. A reseller can only create organizations under itself.
     */
    private static void requireMayCreate(TenantContext actor, OrgType type, OrgId parentId) {
        boolean platformCommercial = actor.orgType() == OrgType.PLATFORM
                && (actor.hasRole("PLATFORM_SUPER_ADMIN") || actor.hasRole("WAVER_COMMERCIAL"));
        boolean allowed = switch (type) {
            case RESELLER -> platformCommercial
                    || (actor.orgType() == OrgType.PLATFORM && actor.isService(RESELLER_ONBOARDING_CLIENT));
            case CUSTOMER -> platformCommercial
                    || (actor.orgType() == OrgType.RESELLER && parentId.equals(actor.orgId())
                        && (actor.hasRole("RESELLER_OWNER") || actor.hasRole("RESELLER_SALES")));
            case PLATFORM -> false;
        };
        if (!allowed) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "TENANT_CREATE_FORBIDDEN",
                    "You are not allowed to create a " + type + " organization here.");
        }
    }
}
