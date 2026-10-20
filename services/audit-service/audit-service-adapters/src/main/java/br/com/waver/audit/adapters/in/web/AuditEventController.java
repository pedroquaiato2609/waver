package br.com.waver.audit.adapters.in.web;

import br.com.waver.audit.application.AuditService;
import br.com.waver.audit.domain.AuditEntry;
import br.com.waver.platform.tenancy.TenantContextHolder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit Center read API (Commercial §3). Row-Level Security limits non-WAVER callers to their own organization. */
@RestController
@RequestMapping("/v1/audit-events")
public class AuditEventController {

    public record AuditEventResponse(UUID auditId, Instant occurredAt, String service, String action, String outcome,
                                     String actorId, String actorType, UUID targetOrgId, String resourceType,
                                     String resourceId, String correlationId, Map<String, String> details, boolean chainValid) {
        static AuditEventResponse from(AuditEntry e) {
            return new AuditEventResponse(e.auditId(), e.occurredAt(), e.service(), e.action(), e.outcome(), e.actorId(),
                    e.actorType(), e.targetOrgId(), e.resourceType(), e.resourceId(), e.correlationId(), e.details(), e.verifies());
        }
    }

    public record Page(List<AuditEventResponse> items, String nextCursor) {
    }

    private final AuditService audit;

    public AuditEventController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_SUPER_ADMIN', 'WAVER_SUPPORT', 'WAVER_FINANCE', 'WAVER_COMMERCIAL', 'RESELLER_OWNER', 'CUSTOMER_ADMIN')")
    public Page list(@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) UUID cursor) {
        TenantContextHolder.require();
        List<AuditEntry> entries = audit.list(cursor, limit);
        String next = entries.size() == Math.clamp(limit, 1, 200) ? entries.getLast().auditId().toString() : null;
        return new Page(entries.stream().map(AuditEventResponse::from).toList(), next);
    }
}
