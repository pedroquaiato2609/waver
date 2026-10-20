package br.com.waver.identityaccess.adapters.out.persistence;

import br.com.waver.identityaccess.domain.IdentityOrganization;
import br.com.waver.identityaccess.domain.IdentityOrganizations;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.kernel.tenancy.OrgType;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityOrganizations implements IdentityOrganizations {

    private final JdbcTemplate jdbc;

    public JdbcIdentityOrganizations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdentityOrganization> find(OrgId orgId) {
        return jdbc.query("select org_id, org_type, keycloak_organization_id, display_name, linked_at from identity_organization where org_id = ?",
                (rs, i) -> new IdentityOrganization(new OrgId(rs.getObject("org_id", UUID.class)), OrgType.valueOf(rs.getString("org_type")),
                        rs.getString("keycloak_organization_id"), rs.getString("display_name"), rs.getTimestamp("linked_at").toInstant()),
                orgId.value()).stream().findFirst();
    }

    @Override
    public void save(IdentityOrganization link) {
        jdbc.update("insert into identity_organization (org_id, org_type, keycloak_organization_id, display_name, linked_at) values (?, ?, ?, ?, ?)",
                link.orgId().value(), link.orgType().name(), link.keycloakOrganizationId(), link.displayName(), Timestamp.from(link.linkedAt()));
    }
}
