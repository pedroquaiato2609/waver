package br.com.waver.audit.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: append-only audit storage. */
public interface AuditLog {

    /** Locks the chain head and returns its hash (null for a new chain). */
    Optional<String> lockChainHead(String chainKey);

    void append(AuditEntry entry);

    /** Visible entries, newest first. */
    List<AuditEntry> list(UUID beforeAuditId, int limit);
}
