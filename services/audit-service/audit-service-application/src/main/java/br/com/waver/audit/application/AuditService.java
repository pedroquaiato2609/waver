package br.com.waver.audit.application;

import br.com.waver.audit.domain.AuditEntry;
import br.com.waver.audit.domain.AuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Records audit facts arriving on the audit transport topic and serves the audit trail. */
public class AuditService {

    private final AuditLog log;

    public AuditService(AuditLog log) {
        this.log = log;
    }

    /** Appends an entry at the head of its chain. The caller handles idempotency (inbox) in the same transaction. */
    @Transactional
    public AuditEntry record(AuditEntry unchained) {
        String head = log.lockChainHead(unchained.chainKey()).orElse(null);
        AuditEntry entry = AuditEntry.chained(unchained, head);
        log.append(entry);
        return entry;
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> list(UUID before, int limit) {
        return log.list(before, Math.clamp(limit, 1, 200));
    }
}
