package br.com.waver.tenant.adapters.in.web;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.tenant.application.OrganizationService;
import br.com.waver.tenant.domain.Organization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal service API (STD-003): /v1/organizations. Callers are BFFs and services with a user or service token. */
@RestController
@RequestMapping("/v1/organizations")
public class OrganizationController {

    public record CreateOrganizationRequest(
            @NotNull OrgType orgType,
            @NotBlank @Size(min = 2, max = 200) String legalName,
            UUID parentOrgId) {
    }

    public record OrganizationResponse(UUID id, OrgType orgType, UUID parentOrgId, String legalName, String status,
                                       long version, Instant createdAt) {
        static OrganizationResponse from(Organization o) {
            return new OrganizationResponse(o.id().value(), o.type(), o.parentId().map(OrgId::value).orElse(null),
                    o.legalName(), o.status().name(), o.version(), o.createdAt());
        }
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    /** Idempotency-Key (STD-003): a retry with the same key returns the organization created the first time. */
    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request,
                                                       @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey != null && (idempotencyKey.length() < 8 || idempotencyKey.length() > 200)) {
            throw new DomainException(DomainException.Kind.VALIDATION, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must have between 8 and 200 characters.");
        }
        OrganizationService.Registration result = service.register(TenantContextHolder.require(),
                new OrganizationService.RegisterOrganization(request.orgType(), request.legalName(),
                        request.parentOrgId() == null ? null : new OrgId(request.parentOrgId())),
                idempotencyKey);
        Organization created = result.organization();
        return (result.replayed() ? ResponseEntity.ok() : ResponseEntity.created(URI.create("/v1/organizations/" + created.id())))
                .eTag(Long.toString(created.version()))
                .body(OrganizationResponse.from(created));
    }

    @GetMapping
    public Page<OrganizationResponse> list(@RequestParam(defaultValue = "50") int limit,
                                           @RequestParam(required = false) UUID cursor) {
        TenantContextHolder.require();
        List<Organization> items = service.list(cursor == null ? null : new OrgId(cursor), limit);
        String next = items.size() == Math.clamp(limit, 1, 200) ? items.getLast().id().toString() : null;
        return new Page<>(items.stream().map(OrganizationResponse::from).toList(), next);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationResponse> get(@PathVariable UUID id) {
        TenantContextHolder.require();
        Organization o = service.get(new OrgId(id)).orElseThrow(() ->
                new DomainException(DomainException.Kind.NOT_FOUND, "TENANT_ORGANIZATION_NOT_FOUND", "Organization not found."));
        return ResponseEntity.ok().eTag(Long.toString(o.version())).body(OrganizationResponse.from(o));
    }
}
