package br.com.waver.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEntryTest {

    private static AuditEntry entry(String action) {
        return new AuditEntry(UUID.randomUUID(), Instant.parse("2026-10-19T12:00:00Z"), "tenant-service", action, "SUCCESS",
                "user-1", "USER", null, null, "organization", "o-1", "c-1", Map.of("orgType", "RESELLER"), "platform", null, null);
    }

    @Test
    void chainedEntriesVerify() {
        AuditEntry first = AuditEntry.chained(entry("tenant.organization.create"), null);
        AuditEntry second = AuditEntry.chained(entry("tenant.organization.create"), first.hash());
        assertThat(first.previousHash()).isEqualTo("GENESIS");
        assertThat(second.previousHash()).isEqualTo(first.hash());
        assertThat(first.verifies()).isTrue();
        assertThat(second.verifies()).isTrue();
    }

    @Test
    void tamperingBreaksTheHash() {
        AuditEntry original = AuditEntry.chained(entry("tenant.organization.create"), null);
        AuditEntry tampered = new AuditEntry(original.auditId(), original.occurredAt(), original.service(), "tenant.organization.delete",
                original.outcome(), original.actorId(), original.actorType(), original.actorOrgId(), original.targetOrgId(),
                original.resourceType(), original.resourceId(), original.correlationId(), original.details(), original.chainKey(),
                original.previousHash(), original.hash());
        assertThat(tampered.verifies()).isFalse();
    }
}
