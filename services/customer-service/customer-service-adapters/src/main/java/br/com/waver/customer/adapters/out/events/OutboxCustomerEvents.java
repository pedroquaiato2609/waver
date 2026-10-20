package br.com.waver.customer.adapters.out.events;

import br.com.waver.customer.application.CustomerService;
import br.com.waver.customer.domain.CustomerProfile;
import br.com.waver.events.customer.v1.CustomerLifecycleStage;
import br.com.waver.events.customer.v1.CustomerRegistered;
import br.com.waver.platform.events.AuditTrail;
import br.com.waver.platform.events.EventPublisher;
import java.util.Map;
import org.springframework.stereotype.Component;

/** CustomerRegistered on waver.customer.customer.v1 (RFC-0017, EVENT_CATALOG) plus its audit record. IDs only. */
@Component
public class OutboxCustomerEvents implements CustomerService.Events {

    static final String TOPIC = "waver.customer.customer.v1";

    private final EventPublisher publisher;
    private final AuditTrail audit;

    public OutboxCustomerEvents(EventPublisher publisher, AuditTrail audit) {
        this.publisher = publisher;
        this.audit = audit;
    }

    @Override
    public void registered(CustomerProfile c) {
        CustomerRegistered data = CustomerRegistered.newBuilder()
                .setCustomerId(c.id())
                .setCustomerOrgId(c.customerOrgId().value())
                .setResellerOrgId(c.resellerOrgId().value())
                .setLifecycleStage(CustomerLifecycleStage.valueOf(c.lifecycleStage().name()))
                .setRegisteredAt(c.createdAt())
                .build();
        publisher.publish(new EventPublisher.Event(TOPIC, "waver.customer.CustomerRegistered.v1", "customer",
                c.id(), c.customerOrgId().value(), c.version(), data));
        audit.record("customer.register", "customer", c.id().toString(), c.customerOrgId().value(),
                Map.of("resellerOrgId", c.resellerOrgId().toString(), "taxIdType", c.taxId().type().name()));
    }
}
