package br.com.waver.audit.adapters.out.persistence;

import br.com.waver.audit.domain.AuditEntry;
import br.com.waver.audit.domain.AuditLog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only storage: the app role has INSERT/SELECT only and a trigger rejects UPDATE/DELETE. */
@Repository
public class JdbcAuditLog implements AuditLog {

    private final JdbcTemplate jdbc;

    public JdbcAuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> lockChainHead(String chainKey) {
        jdbc.update("insert into audit_chain (chain_key, last_hash) values (?, null) on conflict do nothing", chainKey);
        return Optional.ofNullable(jdbc.queryForObject(
                "select last_hash from audit_chain where chain_key = ? for update", String.class, chainKey));
    }

    @Override
    public void append(AuditEntry e) {
        jdbc.update("""
                insert into audit_event (audit_id, occurred_at, service, action, outcome, actor_id, actor_type, actor_org_id,
                    target_org_id, resource_type, resource_id, correlation_id, details, chain_key, previous_hash, hash)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                e.auditId(), Timestamp.from(e.occurredAt()), e.service(), e.action(), e.outcome(), e.actorId(), e.actorType(),
                e.actorOrgId(), e.targetOrgId(), e.resourceType(), e.resourceId(), e.correlationId(),
                toText(e.details()), e.chainKey(), e.previousHash(), e.hash());
        jdbc.update("update audit_chain set last_hash = ? where chain_key = ?", e.hash(), e.chainKey());
    }

    @Override
    public List<AuditEntry> list(UUID before, int limit) {
        String sql = "select * from audit_event " + (before == null ? "" : "where audit_id < ? ") + "order by audit_id desc limit ?";
        Object[] args = before == null ? new Object[] {limit} : new Object[] {before, limit};
        return jdbc.query(sql, this::map, args);
    }

    private AuditEntry map(ResultSet rs, int i) throws SQLException {
        return new AuditEntry(rs.getObject("audit_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("service"), rs.getString("action"), rs.getString("outcome"), rs.getString("actor_id"),
                rs.getString("actor_type"), rs.getObject("actor_org_id", UUID.class), rs.getObject("target_org_id", UUID.class),
                rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("correlation_id"),
                fromText(rs.getString("details")), rs.getString("chain_key"), rs.getString("previous_hash"), rs.getString("hash"));
    }

    /** Details are small key/values: stored as "k=v" lines to keep the hash canonical and storage simple. */
    private static String toText(Map<String, String> details) {
        StringBuilder sb = new StringBuilder();
        details.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(en -> sb.append(en.getKey().replace("\n", " ")).append('=').append(en.getValue().replace("\n", " ")).append('\n'));
        return sb.toString();
    }

    private static Map<String, String> fromText(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    map.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        }
        return map;
    }
}
