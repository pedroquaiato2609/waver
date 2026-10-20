package br.com.waver.reseller.domain;

import br.com.waver.kernel.tenancy.OrgId;
import java.time.Instant;

/** Facts produced by {@link ResellerProfile} transitions; each becomes an outbox event and/or an audit record. */
public sealed interface ResellerEvent {

    Instant at();

    record LeadRegistered(Instant at) implements ResellerEvent {
    }

    record ReviewStarted(Instant at) implements ResellerEvent {
    }

    record ChecklistItemCompleted(ChecklistItem item, String evidenceRef, Instant at) implements ResellerEvent {
    }

    record Approved(String approvalRef, Instant at) implements ResellerEvent {
    }

    record Rejected(String approvalRef, String reason, Instant at) implements ResellerEvent {
    }

    record OrganizationLinked(OrgId resellerOrgId, Instant at) implements ResellerEvent {
    }

    record OwnerInvited(Instant at) implements ResellerEvent {
    }

    record Activated(OrgId resellerOrgId, Instant at) implements ResellerEvent {
    }
}
