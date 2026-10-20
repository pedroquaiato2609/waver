package br.com.waver.catalog.adapters.out.persistence;

import br.com.waver.catalog.domain.DiscountPolicy;
import br.com.waver.catalog.domain.PolicyTarget;
import br.com.waver.catalog.domain.PriceScope;
import br.com.waver.catalog.domain.PricingPolicies;
import br.com.waver.catalog.domain.PricingRule;
import br.com.waver.catalog.domain.ResellerTiers;
import br.com.waver.kernel.tenancy.OrgId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Pricing rules, discount policies and the reseller tier projection. RLS limits a reseller to its own scopes. */
@Repository
public class JdbcPricingPolicies implements PricingPolicies, ResellerTiers {

    private static final String TARGET = "scope, scope_ref, sku_id, valid_from, valid_to";

    private final JdbcTemplate jdbc;

    public JdbcPricingPolicies(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PricingRule r) {
        PolicyTarget t = r.target();
        jdbc.update("insert into pricing_rule (id, " + TARGET + ", min_margin_pct, max_margin_pct, created_at, created_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true))",
                r.id(), t.scope().name(), t.scopeRef(), t.skuId(), Timestamp.from(t.validFrom()), t.until().map(Timestamp::from).orElse(null),
                r.minMarginPct(), r.maxMarginPct(), Timestamp.from(r.createdAt()));
    }

    @Override
    public void insert(DiscountPolicy p) {
        PolicyTarget t = p.target();
        jdbc.update("insert into discount_policy (id, " + TARGET + ", max_discount_without_approval_pct, approval_policy_key, created_at, created_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true))",
                p.id(), t.scope().name(), t.scopeRef(), t.skuId(), Timestamp.from(t.validFrom()), t.until().map(Timestamp::from).orElse(null),
                p.maxDiscountWithoutApprovalPct(), p.approvalPolicyKey(), Timestamp.from(p.createdAt()));
    }

    @Override
    public List<PricingRule> rules() {
        return jdbc.query("select id, " + TARGET + ", min_margin_pct, max_margin_pct, created_at from pricing_rule order by created_at desc",
                (rs, i) -> new PricingRule(rs.getObject("id", UUID.class), target(rs), rs.getBigDecimal("min_margin_pct"),
                        rs.getBigDecimal("max_margin_pct"), rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public List<DiscountPolicy> discountPolicies() {
        return jdbc.query("select id, " + TARGET + ", max_discount_without_approval_pct, approval_policy_key, created_at "
                        + "from discount_policy order by created_at desc",
                (rs, i) -> new DiscountPolicy(rs.getObject("id", UUID.class), target(rs), rs.getBigDecimal("max_discount_without_approval_pct"),
                        rs.getString("approval_policy_key"), rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public Optional<String> tierOf(OrgId resellerOrgId) {
        return jdbc.queryForList("select tier_key from reseller_tier where org_id = ?", String.class, resellerOrgId.value()).stream().findFirst();
    }

    private static PolicyTarget target(ResultSet rs) throws SQLException {
        return new PolicyTarget(PriceScope.valueOf(rs.getString("scope")), rs.getString("scope_ref"), rs.getObject("sku_id", UUID.class),
                rs.getTimestamp("valid_from").toInstant(), JdbcPriceBooks.instant(rs.getTimestamp("valid_to")));
    }
}
