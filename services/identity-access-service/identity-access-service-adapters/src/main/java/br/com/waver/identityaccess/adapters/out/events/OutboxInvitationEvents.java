package br.com.waver.identityaccess.adapters.out.events;

import br.com.waver.events.identity.v1.UserInvited;
import br.com.waver.identityaccess.application.InvitationService;
import br.com.waver.identityaccess.domain.Invitation;
import br.com.waver.platform.events.AuditTrail;
import br.com.waver.platform.events.EventPublisher;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** UserInvited on waver.identity.user.v1 (RFC-0004) plus the audit record, in the invitation transaction. */
@Component
public class OutboxInvitationEvents implements InvitationService.Events {

    static final String TOPIC = "waver.identity.user.v1";

    private final EventPublisher publisher;
    private final AuditTrail audit;

    public OutboxInvitationEvents(EventPublisher publisher, AuditTrail audit) {
        this.publisher = publisher;
        this.audit = audit;
    }

    @Override
    public void userInvited(Invitation i) {
        UserInvited data = UserInvited.newBuilder()
                .setInvitationId(i.id())
                .setUserId(i.userId())
                .setOrgId(i.orgId().value())
                .setRoleKey(i.roleKey())
                .setExpiresAt(i.expiresAt())
                .build();
        // Keycloak user IDs are UUIDs; the aggregate is the user.
        publisher.publish(new EventPublisher.Event(TOPIC, "waver.identity.UserInvited.v1", "user",
                UUID.fromString(i.userId()), i.orgId().value(), 0, data));
        audit.record("identity.user.invite", "user", i.userId(), i.orgId().value(),
                Map.of("roleKey", i.roleKey(), "invitationId", i.id().toString()));
    }
}
