package br.com.waver.tenant.adapters.in.kafka;

import br.com.waver.events.reseller.v1.ResellerActivated;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.events.EventHeaders;
import br.com.waver.platform.events.Inbox;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.tenant.application.OrganizationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** ResellerActivated (waver.reseller.reseller.v1) activates the reseller organization (EVENT_CATALOG §3). */
@Component
public class ResellerLifecycleListener {

    static final String GROUP = "tenant-service.reseller-lifecycle";

    private final OrganizationService organizations;
    private final Inbox inbox;
    private final TransactionTemplate tx;
    private final TenantContext serviceContext;

    public ResellerLifecycleListener(OrganizationService organizations, Inbox inbox, TransactionTemplate tx,
                                     @Value("${waver.platform-org-id}") String platformOrgId) {
        this.organizations = organizations;
        this.inbox = inbox;
        this.tx = tx;
        this.serviceContext = TenantContext.platformService("tenant-service", OrgId.of(platformOrgId));
    }

    @KafkaListener(topics = "waver.reseller.reseller.v1", groupId = GROUP)
    public void onResellerEvent(ConsumerRecord<String, Object> record) {
        if (!(record.value() instanceof ResellerActivated activated)) {
            return; // the other reseller events do not change the organization
        }
        String eventId = EventHeaders.eventId(record);
        TenantContextHolder.runAs(serviceContext, () -> tx.executeWithoutResult(status -> {
            if (eventId != null && !inbox.firstTime(GROUP, eventId)) {
                return;
            }
            organizations.activate(new OrgId(activated.getResellerOrgId()));
        }));
    }
}
