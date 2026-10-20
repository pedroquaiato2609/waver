package br.com.waver.audit.adapters.in.kafka;

import br.com.waver.audit.application.AuditService;
import br.com.waver.audit.domain.AuditEntry;
import br.com.waver.events.audit.v1.AuditRecorded;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.events.Inbox;
import br.com.waver.platform.tenancy.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes waver.audit.audit-record.v1 (RFC-0006 §13). The Debezium outbox router puts the event id in the
 * {@code id} header and CloudEvents attributes in {@code ce_*} headers; the payload is Avro (ADR-0024).
 */
@Component
public class AuditRecordListener {

    static final String GROUP = "audit-service.audit-record";
    private static final Logger log = LoggerFactory.getLogger(AuditRecordListener.class);

    private final AuditService audit;
    private final Inbox inbox;
    private final TransactionTemplate tx;
    private final TenantContext serviceContext;

    public AuditRecordListener(AuditService audit, Inbox inbox, TransactionTemplate tx,
                               @Value("${waver.platform-org-id}") String platformOrgId) {
        this.audit = audit;
        this.inbox = inbox;
        this.tx = tx;
        // Audit ingestion is a platform function: it writes entries for every organization (audited oversight path).
        this.serviceContext = new TenantContext("audit-service", TenantContext.PrincipalType.SERVICE,
                OrgId.of(platformOrgId), OrgType.PLATFORM, Set.of("PLATFORM_SERVICE"));
    }

    @KafkaListener(topics = "waver.audit.audit-record.v1", groupId = GROUP)
    public void onAuditRecorded(ConsumerRecord<String, AuditRecorded> record) {
        String ceType = header(record, "ce_type");
        if (ceType != null && !"waver.audit.AuditRecorded.v1".equals(ceType)) {
            return; // type we do not handle: acknowledge without processing (RFC-0006)
        }
        String eventId = header(record, "id");
        AuditRecorded e = record.value();
        TenantContextHolder.runAs(serviceContext, () -> tx.executeWithoutResult(status -> {
            if (eventId != null && !inbox.firstTime(GROUP, eventId)) {
                log.debug("Duplicate audit event {} ignored", eventId);
                return;
            }
            String chainKey = e.getTargetOrgId() != null ? e.getTargetOrgId().toString() : "platform";
            audit.record(new AuditEntry(e.getAuditId(), e.getOccurredAt(), e.getService(), e.getAction(),
                    e.getOutcome().name(), e.getActorId(), e.getActorType(), e.getActorOrgId(), e.getTargetOrgId(),
                    e.getResourceType(), e.getResourceId(), e.getCorrelationId(), e.getDetails(), chainKey, null, null));
        }));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        if (h == null) {
            return null;
        }
        String value = new String(h.value(), StandardCharsets.UTF_8);
        // Debezium may serialize string headers as JSON strings.
        return value.length() > 1 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
}
