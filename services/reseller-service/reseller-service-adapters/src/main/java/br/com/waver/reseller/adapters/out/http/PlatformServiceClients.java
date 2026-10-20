package br.com.waver.reseller.adapters.out.http;

import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.platform.http.RemoteProblem;
import br.com.waver.platform.http.ServiceCredentials;
import br.com.waver.platform.web.CorrelationIdFilter;
import br.com.waver.reseller.application.ResellerOnboardingService;
import br.com.waver.reseller.domain.Contact;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Synchronous dependencies of the onboarding workflow (MICROSERVICES_MAP: reseller -> tenant, identity-access).
 * Called from Temporal activities, so they use the reseller-service account token, not a user token.
 */
@Component
public class PlatformServiceClients implements ResellerOnboardingService.TenantDirectory, ResellerOnboardingService.IdentityDirectory {

    record OrganizationResponse(UUID id, String status) {
    }

    record InvitationResponse(UUID id, String status) {
    }

    private final RestClient tenant;
    private final RestClient identity;

    public PlatformServiceClients(RestClient.Builder builder, ServiceCredentials credentials,
                                  @Value("${waver.services.tenant-service}") String tenantUrl,
                                  @Value("${waver.services.identity-access-service}") String identityUrl) {
        this.tenant = RemoteProblem.handled(builder.clone()).baseUrl(tenantUrl).requestInterceptor(credentials).build();
        this.identity = RemoteProblem.handled(builder.clone()).baseUrl(identityUrl).requestInterceptor(credentials).build();
    }

    @Override
    public OrgId createResellerOrganization(String legalName, String idempotencyKey) {
        OrganizationResponse created = tenant.post().uri("/v1/organizations")
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationIdFilter.HEADER, CorrelationIdFilter.current())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orgType", "RESELLER", "legalName", legalName))
                .retrieve()
                .body(OrganizationResponse.class);
        if (created == null) {
            throw new IllegalStateException("tenant-service returned no organization");
        }
        return new OrgId(created.id());
    }

    @Override
    public String inviteResellerOwner(OrgId resellerOrgId, Contact owner) {
        InvitationResponse invitation = identity.post().uri("/v1/invitations")
                .header(CorrelationIdFilter.HEADER, CorrelationIdFilter.current())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orgId", resellerOrgId.value(), "email", owner.email(), "firstName", owner.firstName(),
                        "lastName", owner.lastName(), "roleKey", "RESELLER_OWNER"))
                .retrieve()
                .body(InvitationResponse.class);
        if (invitation == null) {
            throw new IllegalStateException("identity-access-service returned no invitation");
        }
        return invitation.id().toString();
    }
}
