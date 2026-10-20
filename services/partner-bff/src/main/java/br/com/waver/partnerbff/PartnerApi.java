package br.com.waver.partnerbff;

import br.com.waver.platform.web.CorrelationIdFilter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Screen-oriented API of WAVER Partner. BFF paths are /api/... and unversioned (RFC-0008); service errors
 * (problem+json) pass through unchanged. Reachable from here (RFC-0017 §1 audience matrix): reseller-service (own
 * profile), customer-service (own portfolio), and for the offers screen (OffersApi) marketplace-service (available
 * listings) and catalog-service (wholesale and retail prices for the reseller).
 */
@RestController
@RequestMapping("/api")
public class PartnerApi {

    public record Session(boolean authenticated, String name, String email, String orgId, String orgType, List<String> roles) {
    }

    private final RestClient reseller;
    private final RestClient customer;

    public PartnerApi(@Qualifier("resellerService") RestClient reseller, @Qualifier("customerService") RestClient customer) {
        this.reseller = reseller;
        this.customer = customer;
    }

    /** Public: tells the SPA whether to show the app or start the login. */
    @GetMapping("/session")
    public Session session(@AuthenticationPrincipal OidcUser user) {
        if (user == null) {
            return new Session(false, null, null, null, null, List.of());
        }
        return new Session(true, user.getFullName(), user.getEmail(), user.getClaimAsString("org_id"),
                user.getClaimAsString("org_type"), SecurityConfig.roles(user));
    }

    @GetMapping("/reseller")
    public ResponseEntity<String> ownReseller() {
        return relay(reseller.get().uri("/v1/resellers/me"));
    }

    @GetMapping("/customers")
    public ResponseEntity<String> customers(@RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(required = false) String cursor) {
        return relay(customer.get().uri(b -> b.path("/v1/customers").queryParam("limit", limit)
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor)).build()));
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<String> customerById(@PathVariable String id) {
        return relay(customer.get().uri("/v1/customers/{id}", id));
    }

    /** The SPA sends an Idempotency-Key per form submission, so a double click never registers twice. */
    @PostMapping("/customers")
    public ResponseEntity<String> registerCustomer(@RequestBody Map<String, Object> body,
                                                   @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        RestClient.RequestBodySpec request = customer.post().uri("/v1/customers").contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return relay(request.body(body));
    }

    static ResponseEntity<String> relay(RestClient.RequestHeadersSpec<?> request) {
        return request.header(CorrelationIdFilter.HEADER, CorrelationIdFilter.current())
                .exchange((req, res) -> {
                    HttpHeaders headers = new HttpHeaders();
                    if (res.getHeaders().getContentType() != null) {
                        headers.setContentType(res.getHeaders().getContentType());
                    }
                    byte[] bytes = res.getBody().readAllBytes();
                    String body = bytes.length == 0 ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    return ResponseEntity.status(res.getStatusCode()).headers(headers).body(body);
                });
    }
}
