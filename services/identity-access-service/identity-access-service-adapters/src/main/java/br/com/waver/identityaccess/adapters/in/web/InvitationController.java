package br.com.waver.identityaccess.adapters.in.web;

import br.com.waver.identityaccess.application.InvitationService;
import br.com.waver.identityaccess.domain.Invitation;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.platform.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal service API (RFC-0004): POST /v1/invitations, GET /v1/invitations?orgId=. */
@RestController
@RequestMapping("/v1/invitations")
public class InvitationController {

    public record InviteRequest(@NotNull UUID orgId, @NotBlank String email, @NotBlank String firstName,
                                @NotBlank String lastName, @NotBlank String roleKey) {
    }

    public record InvitationResponse(UUID id, UUID orgId, String email, String firstName, String lastName, String roleKey,
                                     String userId, String status, Instant invitedAt, Instant expiresAt) {
        static InvitationResponse from(Invitation i) {
            return new InvitationResponse(i.id(), i.orgId().value(), i.email(), i.firstName(), i.lastName(), i.roleKey(),
                    i.userId(), i.status().name(), i.invitedAt(), i.expiresAt());
        }
    }

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    /** Idempotent by organization and e-mail: an open invitation is returned with 200 instead of a second one. */
    @PostMapping
    public ResponseEntity<InvitationResponse> invite(@Valid @RequestBody InviteRequest request) {
        InvitationService.Result result = invitations.invite(TenantContextHolder.require(), new InvitationService.Invite(
                new OrgId(request.orgId()), request.email(), request.firstName(), request.lastName(), request.roleKey()));
        InvitationResponse body = InvitationResponse.from(result.invitation());
        return result.replayed() ? ResponseEntity.ok(body)
                : ResponseEntity.created(URI.create("/v1/invitations/" + body.id())).body(body);
    }

    @GetMapping
    public List<InvitationResponse> list(@RequestParam UUID orgId) {
        return invitations.list(TenantContextHolder.require(), new OrgId(orgId)).stream().map(InvitationResponse::from).toList();
    }
}
