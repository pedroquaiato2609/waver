package br.com.waver.tenant.adapters.out.persistence;

import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import br.com.waver.tenant.domain.Organization;
import br.com.waver.tenant.domain.OrganizationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC adapter. Row-Level Security in PostgreSQL filters every statement by the caller's organization. */
@Repository
public class JdbcOrganizationRepository implements OrganizationRepository {

    private static final String COLUMNS = "id, org_type, parent_org_id, legal_name, status, version, created_at";

    private final JdbcTemplate jdbc;

    public JdbcOrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Organization o, String idempotencyKey) {
        jdbc.update("""
                insert into organization (id, org_type, parent_org_id, legal_name, status, version, created_at, created_by,
                                          idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true), ?)
                """,
                o.id().value(), o.type().name(), o.parentId().map(OrgId::value).orElse(null), o.legalName(),
                o.status().name(), o.version(), Timestamp.from(o.createdAt()), idempotencyKey);
    }

    @Override
    public boolean update(Organization o, long expectedVersion) {
        return jdbc.update("""
                update organization set status = ?, version = ?, updated_at = now() where id = ? and version = ?
                """, o.status().name(), o.version(), o.id().value(), expectedVersion) == 1;
    }

    @Override
    public Optional<Organization> findByIdempotencyKey(OrgId parentId, String idempotencyKey) {
        return jdbc.query("select " + COLUMNS + " from organization where parent_org_id = ? and idempotency_key = ?",
                this::map, parentId.value(), idempotencyKey).stream().findFirst();
    }

    @Override
    public Optional<Organization> findById(OrgId id) {
        return jdbc.query("select " + COLUMNS + " from organization where id = ?", this::map, id.value())
                .stream().findFirst();
    }

    @Override
    public List<Organization> findVisible(OrgId afterId, int limit) {
        if (afterId == null) {
            return jdbc.query("select " + COLUMNS + " from organization order by id desc limit ?", this::map, limit);
        }
        return jdbc.query("select " + COLUMNS + " from organization where id < ? order by id desc limit ?",
                this::map, afterId.value(), limit);
    }

    private Organization map(ResultSet rs, int rowNum) throws SQLException {
        UUID parent = rs.getObject("parent_org_id", UUID.class);
        return Organization.restore(
                new OrgId(rs.getObject("id", UUID.class)),
                OrgType.valueOf(rs.getString("org_type")),
                parent == null ? null : new OrgId(parent),
                rs.getString("legal_name"),
                Organization.Status.valueOf(rs.getString("status")),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant());
    }
}
