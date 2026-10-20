package br.com.waver.catalog.adapters.out.persistence;

import br.com.waver.catalog.domain.PriceBook;
import br.com.waver.catalog.domain.PriceBooks;
import br.com.waver.catalog.domain.PriceEntry;
import br.com.waver.catalog.domain.PriceScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC; Row-Level Security limits a reseller to the books of its scopes, never drafts. */
@Repository
public class JdbcPriceBooks implements PriceBooks {

    private static final String COLUMNS = """
            id, name, currency, scope, scope_ref, valid_from, valid_to, status, activated_at, activated_by, version, created_at""";

    private final JdbcTemplate jdbc;

    public JdbcPriceBooks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PriceBook b) {
        jdbc.update("""
                insert into price_book (id, name, currency, scope, scope_ref, valid_from, valid_to, status, version, created_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true), current_setting('waver.org_id', true))
                """, b.id(), b.name(), b.currency().getCurrencyCode(), b.scope().name(), b.scopeRef().orElse(null),
                Timestamp.from(b.validFrom()), b.validTo().map(Timestamp::from).orElse(null), b.status().name(), b.version(),
                Timestamp.from(b.createdAt()));
        writeEntries(b);
    }

    @Override
    public boolean update(PriceBook b, long expectedVersion) {
        int updated = jdbc.update("""
                update price_book set status = ?, activated_at = ?, activated_by = ?, version = ?, updated_at = now(),
                    updated_by = current_setting('waver.org_id', true)
                where id = ? and version = ?
                """, b.status().name(), b.activatedAt().map(Timestamp::from).orElse(null), b.activatedBy().orElse(null), b.version(),
                b.id(), expectedVersion);
        if (updated != 1) {
            return false;
        }
        if (b.status() == PriceBook.Status.DRAFT) {
            jdbc.update("delete from price_entry where price_book_id = ?", b.id());
            writeEntries(b);
        }
        return true;
    }

    private void writeEntries(PriceBook b) {
        for (PriceEntry e : b.entries()) {
            PriceEntry.RetailPrice r = e.retail();
            jdbc.update("""
                    insert into price_entry (price_book_id, sku_id, charge_model, wholesale_amount, pricing_model, suggested_amount,
                        fixed_amount, floor_amount, ceiling_amount)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, b.id(), e.skuId(), e.wholesale().chargeModel().name(), e.wholesale().amount(), r.pricingModel().name(),
                    r.suggestedAmount(), r.fixedAmount(), r.floorAmount(), r.ceilingAmount());
        }
    }

    @Override
    public Optional<PriceBook> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from price_book where id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public Optional<PriceBook> findActive(PriceScope scope, String scopeRef, Currency currency) {
        return jdbc.query("select " + COLUMNS + " from price_book where status = 'ACTIVE' and scope = ? and coalesce(scope_ref, '') = ? and currency = ?",
                this::map, scope.name(), scopeRef == null ? "" : scopeRef, currency.getCurrencyCode()).stream().findFirst();
    }

    @Override
    public List<PriceBook> findActivePricing(Collection<UUID> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select " + COLUMNS + """
                 from price_book b where status = 'ACTIVE'
                 and exists (select 1 from price_entry e where e.price_book_id = b.id and e.sku_id = any(?::uuid[]))
                """, this::map, (Object) skuIds.stream().map(UUID::toString).toArray(String[]::new));
    }

    @Override
    public List<PriceBook> list(PriceBook.Status status, UUID afterId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from price_book where true");
        if (status != null) {
            sql.append(" and status = ?");
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

    private PriceBook map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<PriceEntry> entries = jdbc.query("""
                select sku_id, charge_model, wholesale_amount, pricing_model, suggested_amount, fixed_amount, floor_amount, ceiling_amount
                from price_entry where price_book_id = ? order by sku_id
                """, (e, i) -> new PriceEntry(e.getObject("sku_id", UUID.class),
                new PriceEntry.WholesalePrice(PriceEntry.ChargeModel.valueOf(e.getString("charge_model")), e.getBigDecimal("wholesale_amount")),
                new PriceEntry.RetailPrice(PriceEntry.PricingModel.valueOf(e.getString("pricing_model")), e.getBigDecimal("suggested_amount"),
                        e.getBigDecimal("fixed_amount"), e.getBigDecimal("floor_amount"), e.getBigDecimal("ceiling_amount"))), id);
        return PriceBook.restore(id, rs.getString("name"), Currency.getInstance(rs.getString("currency")),
                PriceScope.valueOf(rs.getString("scope")), rs.getString("scope_ref"), rs.getTimestamp("valid_from").toInstant(),
                instant(rs.getTimestamp("valid_to")), entries, PriceBook.Status.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("activated_at")), rs.getString("activated_by"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant());
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
