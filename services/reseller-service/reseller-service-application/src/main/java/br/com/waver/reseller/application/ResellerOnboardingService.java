package br.com.waver.reseller.application;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.reseller.domain.ChecklistItem;
import br.com.waver.reseller.domain.Contact;
import br.com.waver.reseller.domain.OnboardingStatus;
import br.com.waver.reseller.domain.ResellerEvent;
import br.com.waver.reseller.domain.ResellerProfile;
import br.com.waver.reseller.domain.ResellerProfiles;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reseller onboarding use cases (RFC-0017 §2). People act through WAVER Control; the onboarding workflow
 * (Temporal, ADR-0023) runs the automatic steps after approval: create the reseller organization in
 * tenant-service, then invite the Reseller Owner through identity-access-service.
 *
 * Every command changes the profile and writes its event to the outbox in one transaction, then wakes the
 * workflow after commit. A missed wake-up is caught by the reconciliation job.
 */
public class ResellerOnboardingService {

    /** Commands of WAVER staff (RFC-0017 §7: reseller.* belongs to WAVER_COMMERCIAL). */
    private static final Set<String> COMMERCIAL_ROLES = Set.of("PLATFORM_SUPER_ADMIN", "WAVER_COMMERCIAL");

    public record RegisterLead(String legalName, String tradeName, TaxId.Type taxIdType, String taxId,
                               String ownerName, String ownerEmail,
                               String commercialName, String commercialEmail, String commercialPhone) {
    }

    /** What the workflow needs to decide its next step. Only flags, no personal data in workflow history. */
    public record OnboardingSnapshot(boolean finished, boolean awaitsOrganization, boolean awaitsOwnerInvitation) {
    }

    public interface Events {
        void record(ResellerProfile profile, ResellerEvent event);
    }

    /** tenant-service: create the reseller organization (idempotent by key). */
    public interface TenantDirectory {
        OrgId createResellerOrganization(String legalName, String idempotencyKey);
    }

    /** identity-access-service: invite the Reseller Owner (idempotent by organization and e-mail). */
    public interface IdentityDirectory {
        String inviteResellerOwner(OrgId resellerOrgId, Contact owner);
    }

    /** Wakes the onboarding workflow of a reseller (starts it if needed). */
    public interface OnboardingOrchestrator {
        void changed(UUID resellerId);
    }

    private final ResellerProfiles profiles;
    private final Events events;
    private final TenantDirectory tenants;
    private final IdentityDirectory identity;
    private final OnboardingOrchestrator orchestrator;
    private final TransactionOperations tx;
    private final Set<ChecklistItem> activationRequires;
    private final Clock clock;

    public ResellerOnboardingService(ResellerProfiles profiles, Events events, TenantDirectory tenants,
                                     IdentityDirectory identity, OnboardingOrchestrator orchestrator,
                                     TransactionOperations tx, Set<ChecklistItem> activationRequires, Clock clock) {
        this.profiles = profiles;
        this.events = events;
        this.tenants = tenants;
        this.identity = identity;
        this.orchestrator = orchestrator;
        this.tx = tx;
        this.activationRequires = Set.copyOf(activationRequires);
        this.clock = clock;
    }

    public Set<ChecklistItem> activationRequires() {
        return activationRequires;
    }

    public ResellerProfile registerLead(TenantContext actor, RegisterLead c) {
        requireCommercial(actor);
        Contact owner = Contact.of(Contact.Type.OWNER, c.ownerName(), c.ownerEmail(), null);
        Contact commercial = Contact.of(Contact.Type.COMMERCIAL, c.commercialName(), c.commercialEmail(), c.commercialPhone());
        TaxId taxId = TaxId.of(c.taxIdType(), c.taxId());
        return tx.execute(status -> {
            if (profiles.existsByTaxId(taxId)) {
                throw new DomainException(DomainException.Kind.CONFLICT, "RESELLER_TAX_ID_EXISTS",
                        "A reseller with this tax ID is already registered.");
            }
            ResellerProfile profile = ResellerProfile.registerLead(c.legalName(), c.tradeName(), taxId, owner,
                    List.of(commercial), clock.instant());
            profiles.insert(profile);
            events.record(profile, new ResellerEvent.LeadRegistered(profile.createdAt()));
            wakeAfterCommit(profile.id());
            return profile;
        });
    }

    public ResellerProfile startReview(TenantContext actor, UUID id) {
        requireCommercial(actor);
        return change(id, p -> p.startReview(clock.instant()));
    }

    public ResellerProfile completeChecklistItem(TenantContext actor, UUID id, ChecklistItem item, String evidenceRef) {
        requireCommercial(actor);
        return change(id, p -> p.completeChecklistItem(item, actor.principalId(), evidenceRef, clock.instant()));
    }

    /** Direct decision by WAVER Commercial until approval-service (RESELLER_APPROVAL policy) is in place. */
    public ResellerProfile decide(TenantContext actor, UUID id, boolean approve, String reason) {
        requireCommercial(actor);
        return change(id, p -> approve
                ? p.approve(actor.principalId(), null, clock.instant())
                : p.reject(actor.principalId(), null, reason, clock.instant()));
    }

    public ResellerProfile activate(TenantContext actor, UUID id) {
        requireCommercial(actor);
        return change(id, p -> p.activate(activationRequires, clock.instant()));
    }

    public ResellerProfile get(TenantContext actor, UUID id) {
        if (!actor.isPlatformStaff()) {
            throw forbidden();
        }
        return tx.execute(status -> load(id));
    }

    public List<ResellerProfile> list(TenantContext actor, OnboardingStatus status, UUID afterId, int limit) {
        if (!actor.isPlatformStaff()) {
            throw forbidden();
        }
        return tx.execute(s -> profiles.list(status, afterId, Math.clamp(limit, 1, 200)));
    }

    /** The reseller's own profile, for WAVER Partner (RFC-0017 §1: own profile, contacts, tier). */
    public ResellerProfile mine(TenantContext actor) {
        if (actor.orgType() != OrgType.RESELLER) {
            throw forbidden();
        }
        return tx.execute(s -> profiles.findByResellerOrg(actor.orgId()).orElseThrow(ResellerOnboardingService::notFound));
    }

    // ---- Workflow steps (run by Temporal activities as the reseller-service principal) ----

    public OnboardingSnapshot snapshot(UUID id) {
        return tx.execute(s -> {
            ResellerProfile p = load(id);
            return new OnboardingSnapshot(p.status().onboardingFinished(), p.awaitsOrganization(), p.awaitsOwnerInvitation());
        });
    }

    /** Creates the reseller organization (tenant-service, Idempotency-Key from the workflow and item) and links it. */
    public void createOrganization(UUID id) {
        ResellerProfile p = tx.execute(s -> load(id));
        if (!p.awaitsOrganization()) {
            return;
        }
        OrgId orgId = tenants.createResellerOrganization(p.legalName(), "reseller-onboarding:" + id + ":ORGANIZATION_CREATED");
        changeQuietly(id, profile -> profile.linkOrganization(orgId, clock.instant()));
    }

    /** Invites the Reseller Owner (identity-access-service sends the account setup e-mail) and records it. */
    public void inviteOwner(UUID id) {
        ResellerProfile p = tx.execute(s -> load(id));
        if (!p.awaitsOwnerInvitation()) {
            return;
        }
        String invitationRef = identity.inviteResellerOwner(p.resellerOrgId().orElseThrow(), p.owner());
        changeQuietly(id, profile -> profile.recordOwnerInvited(invitationRef, clock.instant()));
    }

    /** Reconciliation: approved resellers with steps left, so a lost wake-up never stalls an onboarding. */
    public void wakePendingOnboardings(int limit) {
        List<UUID> pending = tx.execute(s -> profiles.findWithPendingOnboardingSteps(limit));
        if (pending != null) {
            pending.forEach(orchestrator::changed);
        }
    }

    // ---- helpers ----

    private ResellerProfile change(UUID id, Function<ResellerProfile, ResellerEvent> transition) {
        return tx.execute(status -> {
            ResellerProfile profile = load(id);
            long before = profile.version();
            ResellerEvent event = transition.apply(profile);
            if (profile.version() != before) {
                save(profile, before);
                events.record(profile, event);
                wakeAfterCommit(id);
            }
            return profile;
        });
    }

    private void changeQuietly(UUID id, Function<ResellerProfile, Optional<ResellerEvent>> step) {
        tx.executeWithoutResult(status -> {
            ResellerProfile profile = load(id);
            long before = profile.version();
            step.apply(profile).ifPresent(event -> {
                save(profile, before);
                events.record(profile, event);
            });
        });
    }

    private void save(ResellerProfile profile, long expectedVersion) {
        if (!profiles.update(profile, expectedVersion)) {
            throw new DomainException(DomainException.Kind.CONFLICT, "RESELLER_CONCURRENT_UPDATE",
                    "The reseller changed meanwhile. Reload and try again.");
        }
    }

    private ResellerProfile load(UUID id) {
        return profiles.findById(id).orElseThrow(ResellerOnboardingService::notFound);
    }

    private void wakeAfterCommit(UUID id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    orchestrator.changed(id);
                }
            });
        } else {
            orchestrator.changed(id);
        }
    }

    private static void requireCommercial(TenantContext actor) {
        if (actor.orgType() != OrgType.PLATFORM || COMMERCIAL_ROLES.stream().noneMatch(actor::hasRole)) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "RESELLER_COMMAND_FORBIDDEN",
                    "Only WAVER Commercial can manage reseller onboarding.");
        }
    }

    private static DomainException forbidden() {
        return new DomainException(DomainException.Kind.FORBIDDEN, "RESELLER_READ_FORBIDDEN", "You cannot see this reseller.");
    }

    private static DomainException notFound() {
        return new DomainException(DomainException.Kind.NOT_FOUND, "RESELLER_NOT_FOUND", "Reseller not found.");
    }
}
