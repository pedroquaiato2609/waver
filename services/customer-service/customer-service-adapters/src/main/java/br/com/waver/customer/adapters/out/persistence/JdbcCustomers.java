package br.com.waver.customer.adapters.out.persistence;

import br.com.waver.customer.domain.CustomerContact;
import br.com.waver.customer.domain.CustomerProfile;
import br.com.waver.customer.domain.Customers;
import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC; Row-Level Security filters rows by portfolio (reseller_org_id) or by the customer itself (org_id). */
@Repository
public class JdbcCustomers implements Customers {

    private static final String COLUMNS = """
            id, org_id, reseller_org_id, legal_name, trade_name, tax_id_type, tax_id, segment, lifecycle_stage,
            access_status, financial_status, version, created_at""";

    private final JdbcTemplate jdbc;

    public JdbcCustomers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(CustomerProfile c, String idempotencyKey) {
        jdbc.update("""
                insert into customer_profile (id, org_id, reseller_org_id, legal_name, trade_name, tax_id_type, tax_id, segment,
                    lifecycle_stage, access_status, financial_status, version, created_at, created_by, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true), ?)
                """, c.id(), c.customerOrgId().value(), c.resellerOrgId().value(), c.legalName(), c.tradeName().orElse(null),
                c.taxId().type().name(), c.taxId().value(), c.segment().orElse(null), c.lifecycleStage().name(),
                c.accessStatus().name(), c.financialStatus().name(), c.version(), Timestamp.from(c.createdAt()), idempotencyKey);
        for (CustomerContact contact : c.contacts()) {
            jdbc.update("insert into customer_contact (customer_id, contact_type, name, email, phone) values (?, ?, ?, ?, ?)",
                    c.id(), contact.type().name(), contact.name(), contact.email(), contact.phone());
        }
        jdbc.update("insert into customer_ownership (id, customer_id, customer_org_id, reseller_org_id, valid_from) values (?, ?, ?, ?, ?)",
                Ids.newId(), c.id(), c.customerOrgId().value(), c.resellerOrgId().value(), Timestamp.from(c.createdAt()));
    }

    @Override
    public Optional<CustomerProfile> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from customer_profile where id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public Optional<CustomerProfile> findByIdempotencyKey(OrgId resellerOrgId, String idempotencyKey) {
        return jdbc.query("select " + COLUMNS + " from customer_profile where reseller_org_id = ? and idempotency_key = ?",
                this::map, resellerOrgId.value(), idempotencyKey).stream().findFirst();
    }

    @Override
    public boolean existsInPortfolio(OrgId resellerOrgId, TaxId taxId) {
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from customer_profile where reseller_org_id = ? and tax_id_type = ? and tax_id = ?)
                """, Boolean.class, resellerOrgId.value(), taxId.type().name(), taxId.value());
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<CustomerProfile> list(UUID afterId, int limit) {
        if (afterId == null) {
            return jdbc.query("select " + COLUMNS + " from customer_profile order by id desc limit ?", this::map, limit);
        }
        return jdbc.query("select " + COLUMNS + " from customer_profile where id < ? order by id desc limit ?", this::map, afterId, limit);
    }

    private CustomerProfile map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<CustomerContact> contacts = jdbc.query(
                "select contact_type, name, email, phone from customer_contact where customer_id = ? order by contact_type",
                (c, i) -> new CustomerContact(CustomerContact.Type.valueOf(c.getString("contact_type")), c.getString("name"),
                        c.getString("email"), c.getString("phone")), id);
        return CustomerProfile.restore(id, new OrgId(rs.getObject("org_id", UUID.class)),
                new OrgId(rs.getObject("reseller_org_id", UUID.class)), rs.getString("legal_name"), rs.getString("trade_name"),
                new TaxId(TaxId.Type.valueOf(rs.getString("tax_id_type")), rs.getString("tax_id")), rs.getString("segment"), contacts,
                CustomerProfile.LifecycleStage.valueOf(rs.getString("lifecycle_stage")),
                CustomerProfile.AccessStatus.valueOf(rs.getString("access_status")),
                CustomerProfile.FinancialStatus.valueOf(rs.getString("financial_status")),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant());
    }
}
