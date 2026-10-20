package br.com.waver.identityaccess.adapters.out.keycloak;

import br.com.waver.kernel.tenancy.OrgType;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where an invited person lands after setting up the account: the app of the organization type
 * (RESELLER -> WAVER Partner, CUSTOMER -> WAVER Workspace). Each entry names the app's Keycloak client.
 */
@ConfigurationProperties("waver.invitations")
public record InvitationApps(Map<OrgType, App> apps) {

    public record App(String clientId, String redirectUri) {
    }

    public App forOrgType(OrgType type) {
        App app = apps == null ? null : apps.get(type);
        if (app == null) {
            throw new IllegalStateException("No app configured for invitations to " + type + " organizations (waver.invitations.apps)");
        }
        return app;
    }
}
