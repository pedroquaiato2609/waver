package br.com.waver.controlbff;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Screen-oriented API of WAVER Control. BFF paths are /api/... and unversioned (RFC-0008);
 * service errors (problem+json) pass through unchanged so the UI can map stable codes to messages.
 */
@RestController
@RequestMapping("/api")
public class ControlApi {

    public record Session(boolean authenticated, String name, String email, String orgId, String orgType, List<String> roles) {
    }

    private final RestClient tenant;
    private final RestClient audit;
    private final RestClient reseller;

    public ControlApi(@Qualifier("tenantService") RestClient tenant, @Qualifier("auditService") RestClient audit,
                      @Qualifier("resellerService") RestClient reseller) {
        this.tenant = tenant;
        this.audit = audit;
        this.reseller = reseller;
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

    /** Read-only view of the tenancy structure. Resellers are created by the onboarding (/api/resellers). */
    @GetMapping("/organizations")
    public ResponseEntity<String> organizations(@RequestParam(defaultValue = "50") int limit,
                                                @RequestParam(required = false) String cursor) {
        return relay(tenant.get().uri(b -> b.path("/v1/organizations").queryParam("limit", limit)
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor)).build()));
    }

    @GetMapping("/audit-events")
    public ResponseEntity<String> auditEvents(@RequestParam(defaultValue = "50") int limit,
                                              @RequestParam(required = false) String cursor) {
        return relay(audit.get().uri(b -> b.path("/v1/audit-events").queryParam("limit", limit)
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor)).build()));
    }

    // ---- Reseller onboarding (reseller-service, RFC-0017 §2) ----

    @GetMapping("/resellers")
    public ResponseEntity<String> resellers(@RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(required = false) String cursor) {
        return relay(reseller.get().uri(b -> b.path("/v1/resellers").queryParam("limit", limit)
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor)).build()));
    }

    @PostMapping("/resellers")
    public ResponseEntity<String> registerReseller(@RequestBody Map<String, Object> body) {
        return relay(reseller.post().uri("/v1/resellers").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @GetMapping("/resellers/{id}")
    public ResponseEntity<String> reseller(@PathVariable String id) {
        return relay(reseller.get().uri("/v1/resellers/{id}", id));
    }

    @PostMapping("/resellers/{id}/review")
    public ResponseEntity<String> startReview(@PathVariable String id) {
        return relay(reseller.post().uri("/v1/resellers/{id}/review", id));
    }

    @PutMapping("/resellers/{id}/onboarding-checklist")
    public ResponseEntity<String> completeItem(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return relay(reseller.put().uri("/v1/resellers/{id}/onboarding-checklist", id).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @PostMapping("/resellers/{id}/approval-decision")
    public ResponseEntity<String> decide(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return relay(reseller.post().uri("/v1/resellers/{id}/approval-decision", id).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @PostMapping("/resellers/{id}/activation")
    public ResponseEntity<String> activate(@PathVariable String id) {
        return relay(reseller.post().uri("/v1/resellers/{id}/activation", id));
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
