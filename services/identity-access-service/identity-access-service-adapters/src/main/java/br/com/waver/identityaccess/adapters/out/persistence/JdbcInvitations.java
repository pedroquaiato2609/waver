package br.com.waver.identityaccess.adapters.out.persistence;

import br.com.waver.identityaccess.domain.Invitation;
import br.com.waver.identityaccess.domain.Invitations;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvitations implements Invitations {

    private static final String COLUMNS =
            "id, org_id, org_type, email, first_name, last_name, role_key, user_id, status, invited_at, expires_at";

    private final JdbcTemplate jdbc;

    public JdbcInvitations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Invitation> findLatest(OrgId orgId, String email) {
        return jdbc.query("select " + COLUMNS + " from invitation where org_id = ? and email = ? order by invited_at desc limit 1",
                this::map, orgId.value(), email).stream().findFirst();
    }

    @Override
    public List<Invitation> findByOrg(OrgId orgId) {
        return jdbc.query("select " + COLUMNS + " from invitation where org_id = ? order by invited_at desc", this::map, orgId.value());
    }

    @Override
    public void save(Invitation i) {
        jdbc.update("""
                insert into invitation (id, org_id, org_type, email, first_name, last_name, role_key, user_id, status, invited_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, i.id(), i.orgId().value(), i.orgType().name(), i.email(), i.firstName(), i.lastName(), i.roleKey(),
                i.userId(), i.status().name(), Timestamp.from(i.invitedAt()), Timestamp.from(i.expiresAt()));
    }

    private Invitation map(ResultSet rs, int row) throws SQLException {
        return new Invitation(rs.getObject("id", UUID.class), new OrgId(rs.getObject("org_id", UUID.class)),
                OrgType.valueOf(rs.getString("org_type")), rs.getString("email"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("role_key"), rs.getString("user_id"),
                Invitation.Status.valueOf(rs.getString("status")), rs.getTimestamp("invited_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }
}
