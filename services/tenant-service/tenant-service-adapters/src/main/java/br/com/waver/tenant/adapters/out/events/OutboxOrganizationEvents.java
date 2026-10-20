package br.com.waver.tenant.adapters.out.events;

import br.com.waver.events.tenant.v1.OrgType;
import br.com.waver.events.tenant.v1.OrganizationCreated;
import br.com.waver.events.tenant.v1.OrganizationStatus;
import br.com.waver.events.tenant.v1.OrganizationStatusChanged;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.platform.events.AuditTrail;
import br.com.waver.platform.events.EventPublisher;
import br.com.waver.tenant.application.OrganizationEvents;
import br.com.waver.tenant.domain.Organization;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Publishes tenant facts on waver.tenant.organization.v1 (EVENT_CATALOG §2) through the outbox. */
@Component
public class OutboxOrganizationEvents implements OrganizationEvents {

    static final String TOPIC = "waver.tenant.organization.v1";

    private final EventPublisher publisher;
    private final AuditTrail audit;

    public OutboxOrganizationEvents(EventPublisher publisher, AuditTrail audit) {
        this.publisher = publisher;
        this.audit = audit;
    }

    @Override
    public void organizationCreated(Organization o) {
        OrganizationCreated data = OrganizationCreated.newBuilder()
                .setOrganizationId(o.id().value())
                .setOrgType(OrgType.valueOf(o.type().name()))
                .setStatus(OrganizationStatus.valueOf(o.status().name()))
                .setParentOrgId(o.parentId().map(OrgId::value).orElse(null))
                .setLegalName(o.legalName())
                .setCreatedAt(o.createdAt())
                .build();
        publisher.publish(new EventPublisher.Event(TOPIC, "waver.tenant.OrganizationCreated.v1", "organization",
                o.id().value(), o.id().value(), o.version(), data));
    }

    @Override
    public void statusChanged(Organization o, Organization.Status fromStatus) {
        OrganizationStatusChanged data = OrganizationStatusChanged.newBuilder()
                .setOrganizationId(o.id().value())
                .setOrgType(o.type().name())
                .setFromStatus(fromStatus.name())
                .setToStatus(o.status().name())
                .setChangedAt(java.time.Instant.now())
                .build();
        publisher.publish(new EventPublisher.Event(TOPIC, "waver.tenant.OrganizationStatusChanged.v1", "organization",
                o.id().value(), o.id().value(), o.version(), data));
    }

    @Override
    public void audit(String action, Organization o, Map<String, String> details) {
        audit.record(action, "organization", o.id().toString(), o.id().value(), details);
    }
}
