package br.com.waver.catalog.adapters.out.persistence;

import br.com.waver.catalog.domain.Product;
import br.com.waver.catalog.domain.Products;
import br.com.waver.catalog.domain.Sku;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC; Row-Level Security decides which products and SKUs the caller sees. */
@Repository
public class JdbcProducts implements Products {

    private static final String COLUMNS = "id, product_key, name, item_type, technical_ref, status, published_at, version, created_at";
    private static final String SKU_COLUMNS = "id, product_id, sku_code, unit, charge_type, billing_period, meter_key";

    private final JdbcTemplate jdbc;

    public JdbcProducts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Product p) {
        jdbc.update("""
                insert into product (id, product_key, name, item_type, technical_ref, status, version, created_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true), current_setting('waver.org_id', true))
                """, p.id(), p.productKey(), p.name(), p.itemType().name(), p.technicalRef().orElse(null), p.status().name(),
                p.version(), Timestamp.from(p.createdAt()));
    }

    @Override
    public void insertSku(Sku s) {
        jdbc.update("""
                insert into sku (id, product_id, sku_code, unit, charge_type, billing_period, meter_key, created_by)
                values (?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true))
                """, s.id(), s.productId(), s.skuCode(), s.unit().name(), s.chargeType().name(),
                s.period().map(Enum::name).orElse(null), s.meter().orElse(null));
    }

    @Override
    public boolean update(Product p, long expectedVersion) {
        return jdbc.update("""
                update product set status = ?, published_at = ?, version = ?, updated_at = now(), updated_by = current_setting('waver.org_id', true)
                where id = ? and version = ?
                """, p.status().name(), p.publishedAt().map(Timestamp::from).orElse(null), p.version(), p.id(), expectedVersion) == 1;
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from product where id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public boolean existsByKey(String productKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from product where product_key = ?)", Boolean.class, productKey));
    }

    @Override
    public boolean existsSkuCode(String skuCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from sku where sku_code = ?)", Boolean.class, skuCode));
    }

    @Override
    public List<Sku> findSkus(Collection<UUID> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select " + SKU_COLUMNS + " from sku where id = any(?::uuid[])", JdbcProducts::mapSku,
                (Object) skuIds.stream().map(UUID::toString).toArray(String[]::new));
    }

    @Override
    public List<Product> list(Product.Status status, UUID afterId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from product where true");
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

    private Product map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<Sku> skus = jdbc.query("select " + SKU_COLUMNS + " from sku where product_id = ? order by sku_code", JdbcProducts::mapSku, id);
        Timestamp published = rs.getTimestamp("published_at");
        return Product.restore(id, rs.getString("product_key"), rs.getString("name"), Product.ItemType.valueOf(rs.getString("item_type")),
                rs.getString("technical_ref"), skus, Product.Status.valueOf(rs.getString("status")),
                published == null ? null : published.toInstant(), rs.getLong("version"), rs.getTimestamp("created_at").toInstant());
    }

    static Sku mapSku(ResultSet rs, int row) throws SQLException {
        String period = rs.getString("billing_period");
        return new Sku(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("sku_code"),
                Sku.Unit.valueOf(rs.getString("unit")), Sku.ChargeType.valueOf(rs.getString("charge_type")),
                period == null ? null : Sku.BillingPeriod.valueOf(period), rs.getString("meter_key"));
    }
}
