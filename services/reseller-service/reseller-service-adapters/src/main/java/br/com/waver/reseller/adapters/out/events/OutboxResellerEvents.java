package br.com.waver.reseller.adapters.out.events;

import br.com.waver.events.reseller.v1.ResellerActivated;
import br.com.waver.events.reseller.v1.ResellerApproved;
import br.com.waver.events.reseller.v1.ResellerLeadRegistered;
import br.com.waver.events.reseller.v1.ResellerRejected;
import br.com.waver.events.reseller.v1.ResellerReviewStarted;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.platform.events.AuditTrail;
import br.com.waver.platform.events.EventPublisher;
import br.com.waver.reseller.application.ResellerOnboardingService;
import br.com.waver.reseller.domain.ResellerEvent;
import br.com.waver.reseller.domain.ResellerProfile;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Lifecycle events on waver.reseller.reseller.v1 (RFC-0017, EVENT_CATALOG) and the audit record of every
 * onboarding action (AR-009), in the transaction of the change. Events carry IDs and statuses, never contacts.
 */
@Component
public class OutboxResellerEvents implements ResellerOnboardingService.Events {

    static final String TOPIC = "waver.reseller.reseller.v1";

    private final EventPublisher publisher;
    private final AuditTrail audit;
    private final UUID platformOrgId;

    public OutboxResellerEvents(EventPublisher publisher, AuditTrail audit, @Value("${waver.platform-org-id}") String platformOrgId) {
        this.publisher = publisher;
        this.audit = audit;
        this.platformOrgId = UUID.fromString(platformOrgId);
    }

    @Override
    public void record(ResellerProfile p, ResellerEvent event) {
        UUID id = p.id();
        switch (event) {
            case ResellerEvent.LeadRegistered e -> {
                publish(p, "ResellerLeadRegistered", ResellerLeadRegistered.newBuilder()
                        .setResellerId(id).setLegalName(p.legalName()).setRegisteredAt(e.at()).build());
                audit(p, "reseller.lead.register", Map.of("taxIdType", p.taxId().type().name()));
            }
            case ResellerEvent.ReviewStarted e -> {
                publish(p, "ResellerReviewStarted", ResellerReviewStarted.newBuilder().setResellerId(id).setStartedAt(e.at()).build());
                audit(p, "reseller.review.start", Map.of());
            }
            case ResellerEvent.ChecklistItemCompleted e -> audit(p, "reseller.onboarding.item.complete",
                    e.evidenceRef() == null ? Map.of("item", e.item().name()) : Map.of("item", e.item().name(), "evidenceRef", e.evidenceRef()));
            case ResellerEvent.Approved e -> {
                publish(p, "ResellerApproved", ResellerApproved.newBuilder().setResellerId(id)
                        .setResellerOrgId(p.resellerOrgId().map(OrgId::value).orElse(null))
                        .setApprovalRef(e.approvalRef()).setApprovedAt(e.at()).build());
                audit(p, "reseller.approve", Map.of("decision", "DIRECT"));
            }
            case ResellerEvent.Rejected e -> {
                publish(p, "ResellerRejected", ResellerRejected.newBuilder().setResellerId(id)
                        .setApprovalRef(e.approvalRef()).setRejectedAt(e.at()).build());
                audit(p, "reseller.reject", Map.of("reason", e.reason()));
            }
            case ResellerEvent.OrganizationLinked e -> audit(p, "reseller.organization.link", Map.of("resellerOrgId", e.resellerOrgId().toString()));
            case ResellerEvent.OwnerInvited e -> audit(p, "reseller.owner.invite", Map.of());
            case ResellerEvent.Activated e -> {
                publish(p, "ResellerActivated", ResellerActivated.newBuilder().setResellerId(id)
                        .setResellerOrgId(e.resellerOrgId().value()).setActivatedAt(e.at()).build());
                audit(p, "reseller.activate", Map.of());
            }
        }
    }

    private void publish(ResellerProfile p, String eventName, SpecificRecord data) {
        publisher.publish(new EventPublisher.Event(TOPIC, "waver.reseller." + eventName + ".v1", "reseller",
                p.id(), platformOrgId, p.version(), data));
    }

    private void audit(ResellerProfile p, String action, Map<String, String> details) {
        UUID target = p.resellerOrgId().map(OrgId::value).orElse(platformOrgId);
        audit.record(action, "reseller", p.id().toString(), target, details);
    }
}
