package br.com.waver.identityaccess.adapters.in.kafka;

import br.com.waver.events.tenant.v1.OrganizationCreated;
import br.com.waver.identityaccess.application.OrganizationProvisioning;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.events.Inbox;
import br.com.waver.platform.tenancy.TenantContextHolder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Consumes waver.tenant.organization.v1 and mirrors new organizations into Keycloak. */
@Component
public class TenantOrganizationListener {

    static final String GROUP = "identity-access-service.tenant-organization";

    private final OrganizationProvisioning provisioning;
    private final Inbox inbox;
    private final TransactionTemplate tx;
    private final TenantContext serviceContext;

    public TenantOrganizationListener(OrganizationProvisioning provisioning, Inbox inbox,
                                      TransactionTemplate tx, @Value("${waver.platform-org-id}") String platformOrgId) {
        this.provisioning = provisioning;
        this.inbox = inbox;
        this.tx = tx;
        this.serviceContext = new TenantContext("identity-access-service", TenantContext.PrincipalType.SERVICE,
                OrgId.of(platformOrgId), OrgType.PLATFORM, Set.of("PLATFORM_SERVICE"));
    }

    @KafkaListener(topics = "waver.tenant.organization.v1", groupId = GROUP)
    public void onOrganizationEvent(ConsumerRecord<String, Object> record) {
        if (!(record.value() instanceof OrganizationCreated created)) {
            return; // other event types on this topic are not handled here
        }
        String eventId = header(record, "id");
        TenantContextHolder.runAs(serviceContext, () -> tx.executeWithoutResult(status -> {
            if (eventId != null && !inbox.firstTime(GROUP, eventId)) {
                return;
            }
            if (created.getOrgType() == br.com.waver.events.tenant.v1.OrgType.UNKNOWN) {
                return;
            }
            provisioning.onOrganizationCreated(new OrgId(created.getOrganizationId()),
                    OrgType.valueOf(created.getOrgType().name()), created.getLegalName());
        }));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        if (h == null) {
            return null;
        }
        String value = new String(h.value(), StandardCharsets.UTF_8);
        return value.length() > 1 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
}
