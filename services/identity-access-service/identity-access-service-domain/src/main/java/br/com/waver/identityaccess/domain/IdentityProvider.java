package br.com.waver.identityaccess.domain;

import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;

/** Port to the identity provider (Keycloak, ADR-0021). Every operation is idempotent. */
public interface IdentityProvider {

    /** Creates the IdP organization for a WAVER Organization, or returns the existing one (idempotent by alias). */
    String ensureOrganization(OrgId orgId, String displayName);

    /**
     * Creates the user (disabled password, required actions pending) or returns the existing one with the same
     * e-mail. The user carries the WAVER org claims and the role. Fails with IDENTITY_USER_IN_OTHER_ORGANIZATION
     * when the e-mail already belongs to another organization (one organization per account in v1).
     */
    String ensureUser(Invitation.Invitee invitee, OrgId orgId, OrgType orgType);

    void addOrganizationMember(String identityOrganizationId, String userId);

    /** Sends the account setup e-mail: verify e-mail, set password, configure MFA, then go to the user's app. */
    void sendAccountSetupEmail(String userId, OrgType orgType);
}
