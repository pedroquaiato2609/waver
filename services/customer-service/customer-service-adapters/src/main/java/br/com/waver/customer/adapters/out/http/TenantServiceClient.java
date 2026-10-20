package br.com.waver.customer.adapters.out.http;

import br.com.waver.customer.application.CustomerService;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.platform.http.BearerRelayInterceptor;
import br.com.waver.platform.http.RemoteProblem;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * tenant-service, with the reseller user's token: tenant-service checks that the user may create customers and
 * that the organization goes under the user's own reseller (RFC-0003). Its rule errors pass through unchanged.
 */
@Component
public class TenantServiceClient implements CustomerService.TenantDirectory {

    record OrganizationResponse(UUID id) {
    }

    private final RestClient tenant;

    public TenantServiceClient(RestClient.Builder builder, @Value("${waver.services.tenant-service}") String baseUrl) {
        this.tenant = RemoteProblem.handled(builder.clone()).baseUrl(baseUrl).requestInterceptor(new BearerRelayInterceptor()).build();
    }

    @Override
    public OrgId createCustomerOrganization(String legalName, String idempotencyKey) {
        OrganizationResponse created = tenant.post().uri("/v1/organizations")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orgType", "CUSTOMER", "legalName", legalName))
                .retrieve()
                .body(OrganizationResponse.class);
        if (created == null) {
            throw new IllegalStateException("tenant-service returned no organization");
        }
        return new OrgId(created.id());
    }
}
