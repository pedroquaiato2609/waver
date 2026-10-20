package br.com.waver.platform.events;

import br.com.waver.events.audit.v1.AuditOutcome;
import br.com.waver.events.audit.v1.AuditRecorded;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.platform.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Records sensitive actions (AR-009) on the audit transport topic through the outbox, in the same
 * transaction as the action itself, so an action can never happen without its audit record.
 */
public class AuditTrail {

    public static final String TOPIC = "waver.audit.audit-record.v1";
    private static final String TYPE = "waver.audit.AuditRecorded.v1";

    private final EventPublisher publisher;
    private final String serviceName;

    public AuditTrail(EventPublisher publisher, String serviceName) {
        this.publisher = publisher;
        this.serviceName = serviceName;
    }

    public void record(String action, String resourceType, String resourceId, UUID targetOrgId, Map<String, String> details) {
        TenantContext actor = TenantContextHolder.current().orElse(null);
        UUID auditId = Ids.newId();
        AuditRecorded record = AuditRecorded.newBuilder()
                .setAuditId(auditId)
                .setOccurredAt(Instant.now())
                .setService(serviceName)
                .setAction(action)
                .setOutcome(AuditOutcome.SUCCESS)
                .setActorId(actor == null ? serviceName : actor.principalId())
                .setActorType(actor == null ? "SERVICE" : actor.principalType().name())
                .setActorOrgId(actor == null ? null : actor.orgId().value())
                .setTargetOrgId(targetOrgId)
                .setResourceType(resourceType)
                .setResourceId(resourceId)
                .setCorrelationId(CorrelationIdFilter.current())
                .setDetails(details)
                .build();
        UUID partitionOrg = targetOrgId != null ? targetOrgId : actor != null ? actor.orgId().value() : auditId;
        publisher.publish(new EventPublisher.Event(TOPIC, TYPE, "audit-record", partitionOrg, targetOrgId, 0, record));
    }
}
