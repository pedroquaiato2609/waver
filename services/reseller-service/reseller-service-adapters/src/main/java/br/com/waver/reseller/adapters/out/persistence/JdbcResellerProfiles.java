package br.com.waver.reseller.adapters.out.persistence;

import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.reseller.domain.ChecklistItem;
import br.com.waver.reseller.domain.Contact;
import br.com.waver.reseller.domain.OnboardingStatus;
import br.com.waver.reseller.domain.ResellerProfile;
import br.com.waver.reseller.domain.ResellerProfiles;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC. Row-Level Security filters every statement (WAVER staff: all; a reseller: its own profile). */
@Repository
public class JdbcResellerProfiles implements ResellerProfiles {

    private static final String COLUMNS = """
            id, legal_name, trade_name, tax_id_type, tax_id, onboarding_status, reseller_org_id,
            decided_by, approval_ref, decision_reason, decided_at, version, created_at""";

    private final JdbcTemplate jdbc;
    private final UUID platformOrgId;

    public JdbcResellerProfiles(JdbcTemplate jdbc, @Value("${waver.platform-org-id}") String platformOrgId) {
        this.jdbc = jdbc;
        this.platformOrgId = UUID.fromString(platformOrgId);
    }

    @Override
    public void insert(ResellerProfile p) {
        jdbc.update("""
                insert into reseller_profile (id, org_id, legal_name, trade_name, tax_id_type, tax_id, onboarding_status,
                                              reseller_org_id, version, created_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true))
                """, p.id(), platformOrgId, p.legalName(), p.tradeName().orElse(null), p.taxId().type().name(),
                p.taxId().value(), p.status().name(), p.resellerOrgId().map(OrgId::value).orElse(null), p.version(),
                Timestamp.from(p.createdAt()));
        for (Contact c : p.contacts()) {
            jdbc.update("insert into reseller_contact (reseller_id, org_id, contact_type, name, email, phone) values (?, ?, ?, ?, ?, ?)",
                    p.id(), platformOrgId, c.type().name(), c.name(), c.email(), c.phone());
        }
        writeChecklist(p);
    }

    @Override
    public boolean update(ResellerProfile p, long expectedVersion) {
        int rows = jdbc.update("""
                update reseller_profile set onboarding_status = ?, reseller_org_id = ?, decided_by = ?, approval_ref = ?,
                       decision_reason = ?, decided_at = ?, version = ?, updated_at = now()
                where id = ? and version = ?
                """, p.status().name(), p.resellerOrgId().map(OrgId::value).orElse(null),
                p.decision().map(ResellerProfile.Decision::decidedBy).orElse(null),
                p.decision().map(ResellerProfile.Decision::approvalRef).orElse(null),
                p.decision().map(ResellerProfile.Decision::reason).orElse(null),
                p.decision().map(d -> Timestamp.from(d.decidedAt())).orElse(null),
                p.version(), p.id(), expectedVersion);
        if (rows == 1) {
            writeChecklist(p);
        }
        return rows == 1;
    }

    private void writeChecklist(ResellerProfile p) {
        for (Map.Entry<ChecklistItem, ResellerProfile.ChecklistEntry> e : p.checklist().entrySet()) {
            jdbc.update("""
                    insert into reseller_onboarding_item (reseller_id, org_id, item_key, done_at, done_by, evidence_ref)
                    values (?, ?, ?, ?, ?, ?) on conflict (reseller_id, item_key) do nothing
                    """, p.id(), platformOrgId, e.getKey().name(), Timestamp.from(e.getValue().doneAt()),
                    e.getValue().doneBy(), e.getValue().evidenceRef());
        }
    }

    @Override
    public Optional<ResellerProfile> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from reseller_profile where id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public Optional<ResellerProfile> findByResellerOrg(OrgId resellerOrgId) {
        return jdbc.query("select " + COLUMNS + " from reseller_profile where reseller_org_id = ?", this::map, resellerOrgId.value())
                .stream().findFirst();
    }

    @Override
    public boolean existsByTaxId(TaxId taxId) {
        Boolean exists = jdbc.queryForObject("select exists (select 1 from reseller_profile where tax_id_type = ? and tax_id = ?)",
                Boolean.class, taxId.type().name(), taxId.value());
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<ResellerProfile> list(OnboardingStatus status, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from reseller_profile where true");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" and onboarding_status = ?");
            args.add(status.name());
        }
        if (afterId != null) {
            sql.append(" and id < ?");
            args.add(afterId);
        }
        sql.append(" order by id desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    @Override
    public List<UUID> findWithPendingOnboardingSteps(int limit) {
        return jdbc.queryForList("""
                select p.id from reseller_profile p
                where p.onboarding_status = 'APPROVED'
                  and (p.reseller_org_id is null
                       or not exists (select 1 from reseller_onboarding_item i where i.reseller_id = p.id and i.item_key = 'ADMIN_INVITED'))
                order by p.id limit ?
                """, UUID.class, limit);
    }

    private ResellerProfile map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<Contact> contacts = jdbc.query("select contact_type, name, email, phone from reseller_contact where reseller_id = ? order by contact_type",
                (c, i) -> new Contact(Contact.Type.valueOf(c.getString("contact_type")), c.getString("name"), c.getString("email"),
                        c.getString("phone")), id);
        Map<ChecklistItem, ResellerProfile.ChecklistEntry> checklist = new EnumMap<>(ChecklistItem.class);
        jdbc.query("select item_key, done_at, done_by, evidence_ref from reseller_onboarding_item where reseller_id = ?",
                (ResultSet i) -> {
                    checklist.put(ChecklistItem.valueOf(i.getString("item_key")), new ResellerProfile.ChecklistEntry(
                            i.getTimestamp("done_at").toInstant(), i.getString("done_by"), i.getString("evidence_ref")));
                }, id);
        UUID orgId = rs.getObject("reseller_org_id", UUID.class);
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        ResellerProfile.Decision decision = decidedAt == null ? null : new ResellerProfile.Decision(
                rs.getString("decided_by"), rs.getString("approval_ref"), rs.getString("decision_reason"), decidedAt.toInstant());
        return ResellerProfile.restore(id, rs.getString("legal_name"), rs.getString("trade_name"),
                new TaxId(TaxId.Type.valueOf(rs.getString("tax_id_type")), rs.getString("tax_id")), contacts, checklist,
                OnboardingStatus.valueOf(rs.getString("onboarding_status")), orgId == null ? null : new OrgId(orgId),
                decision, rs.getLong("version"), rs.getTimestamp("created_at").toInstant());
    }
}
