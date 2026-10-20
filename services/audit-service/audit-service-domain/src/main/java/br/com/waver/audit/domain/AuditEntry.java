package br.com.waver.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable audit record (AR-009). Entries form a hash chain per {@code chainKey} (the target organization,
 * or the platform chain): each hash covers the previous hash, so a deleted or edited entry breaks the chain.
 */
public record AuditEntry(
        UUID auditId,
        Instant occurredAt,
        String service,
        String action,
        String outcome,
        String actorId,
        String actorType,
        UUID actorOrgId,
        UUID targetOrgId,
        String resourceType,
        String resourceId,
        String correlationId,
        Map<String, String> details,
        String chainKey,
        String previousHash,
        String hash) {

    public AuditEntry {
        Objects.requireNonNull(auditId);
        Objects.requireNonNull(occurredAt);
        details = Map.copyOf(details);
    }

    /** Links a new entry to the head of its chain. */
    public static AuditEntry chained(AuditEntry unchained, String previousHash) {
        String prev = previousHash == null ? "GENESIS" : previousHash;
        String hash = sha256(prev + "|" + unchained.canonical());
        return new AuditEntry(unchained.auditId, unchained.occurredAt, unchained.service, unchained.action,
                unchained.outcome, unchained.actorId, unchained.actorType, unchained.actorOrgId, unchained.targetOrgId,
                unchained.resourceType, unchained.resourceId, unchained.correlationId, unchained.details,
                unchained.chainKey, prev, hash);
    }

    public boolean verifies() {
        return hash != null && hash.equals(sha256(previousHash + "|" + canonical()));
    }

    private String canonical() {
        return String.join("|", auditId.toString(), Long.toString(occurredAt.toEpochMilli()), service, action, outcome,
                actorId, actorType, String.valueOf(actorOrgId), String.valueOf(targetOrgId), resourceType, resourceId,
                String.valueOf(correlationId), new TreeMap<>(details).toString(), chainKey);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
