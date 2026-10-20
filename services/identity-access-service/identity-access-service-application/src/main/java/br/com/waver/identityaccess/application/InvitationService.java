package br.com.waver.identityaccess.application;

import br.com.waver.identityaccess.domain.IdentityOrganization;
import br.com.waver.identityaccess.domain.IdentityOrganizations;
import br.com.waver.identityaccess.domain.IdentityProvider;
import br.com.waver.identityaccess.domain.Invitation;
import br.com.waver.identityaccess.domain.Invitations;
import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invites people to organizations (RFC-0004 §Invitation): identity-access creates or finds the Keycloak user,
 * adds it to the Keycloak organization and triggers Keycloak's account setup e-mail (verify e-mail, password, MFA).
 */
public class InvitationService {

    /** The reseller onboarding workflow invites the Reseller Owner when the reseller is approved (RFC-0017 §2). */
    static final String RESELLER_ONBOARDING_CLIENT = "svc-reseller-service";

    public interface Events {
        void userInvited(Invitation invitation);
    }

    public record Invite(OrgId orgId, String email, String firstName, String lastName, String roleKey) {
    }

    public record Result(Invitation invitation, boolean replayed) {
    }

    private final Invitations invitations;
    private final IdentityOrganizations links;
    private final IdentityProvider idp;
    private final Events events;
    private final Clock clock;

    public InvitationService(Invitations invitations, IdentityOrganizations links, IdentityProvider idp, Events events, Clock clock) {
        this.invitations = invitations;
        this.links = links;
        this.idp = idp;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Result invite(TenantContext actor, Invite command) {
        // The Keycloak organization is created from OrganizationCreated; right after the organization is created
        // it may not exist yet, so the caller retries (409, transient).
        IdentityOrganization link = links.find(command.orgId()).orElseThrow(() -> new DomainException(
                DomainException.Kind.CONFLICT, "IDENTITY_ORGANIZATION_NOT_READY",
                "The organization is still being set up in the identity provider. Try again in a few seconds."));
        Invitation.Invitee invitee = Invitation.Invitee.of(link.orgType(), command.email(), command.firstName(),
                command.lastName(), command.roleKey());
        requireMayInvite(actor, link, invitee.roleKey());

        Optional<Invitation> open = invitations.findLatest(command.orgId(), invitee.email())
                .filter(i -> i.isOpen(clock.instant()));
        if (open.isPresent()) {
            return new Result(open.get(), true);
        }

        String userId = idp.ensureUser(invitee, link.orgId(), link.orgType());
        idp.addOrganizationMember(link.keycloakOrganizationId(), userId);
        Invitation invitation = Invitation.create(link.orgId(), link.orgType(), invitee, userId, clock.instant());
        invitations.save(invitation);
        events.userInvited(invitation);
        idp.sendAccountSetupEmail(userId, link.orgType());
        return new Result(invitation, false);
    }

    @Transactional(readOnly = true)
    public List<Invitation> list(TenantContext actor, OrgId orgId) {
        if (!actor.isPlatformStaff() && !actor.orgId().equals(orgId)) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "IDENTITY_INVITATION_FORBIDDEN",
                    "You can only see invitations of your own organization.");
        }
        return invitations.findByOrg(orgId);
    }

    /**
     * WAVER commercial staff and the reseller onboarding workflow invite reseller users; a Reseller Owner invites
     * users of its own reseller. Customer admins are invited by provisioning (Phase 1, later slice).
     */
    private static void requireMayInvite(TenantContext actor, IdentityOrganization target, String roleKey) {
        boolean allowed = switch (target.orgType()) {
            case RESELLER -> (actor.orgType() == OrgType.PLATFORM
                        && (actor.hasRole("PLATFORM_SUPER_ADMIN") || actor.hasRole("WAVER_COMMERCIAL")
                            || (actor.isService(RESELLER_ONBOARDING_CLIENT) && roleKey.equals("RESELLER_OWNER"))))
                    || (actor.orgType() == OrgType.RESELLER && actor.orgId().equals(target.orgId()) && actor.hasRole("RESELLER_OWNER"));
            case CUSTOMER -> actor.orgType() == OrgType.PLATFORM && actor.hasRole("PLATFORM_SUPER_ADMIN");
            case PLATFORM -> false;
        };
        if (!allowed) {
            throw new DomainException(DomainException.Kind.FORBIDDEN, "IDENTITY_INVITATION_FORBIDDEN",
                    "You are not allowed to invite users to this organization.");
        }
    }
}
