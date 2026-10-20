package br.com.waver.marketplace.adapters.out.persistence;

import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.marketplace.domain.AvailabilityRule;
import br.com.waver.marketplace.domain.Listing;
import br.com.waver.marketplace.domain.ListingVersion;
import br.com.waver.marketplace.domain.Listings;
import br.com.waver.marketplace.domain.ResellerTiers;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC. Row-Level Security decides what the caller sees: a reseller gets only available, published
 * listings and their current version, and never the availability rule (it reads as absent).
 */
@Repository
public class JdbcListings implements Listings, ResellerTiers {

    private static final String COLUMNS = "id, listing_key, product_id, category, title, summary, status, current_version_id, version, created_at";

    private final JdbcTemplate jdbc;

    public JdbcListings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Listing l) {
        jdbc.update("""
                insert into listing (id, listing_key, product_id, category, title, summary, status, current_version_id, version, created_at,
                    created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_setting('waver.org_id', true), current_setting('waver.org_id', true))
                """, l.id(), l.listingKey(), l.productId(), l.category(), l.title(), l.summary().orElse(null), l.status().name(),
                l.currentVersionId().orElse(null), l.version(), Timestamp.from(l.createdAt()));
        writeChildren(l);
    }

    @Override
    public boolean update(Listing l, long expectedVersion) {
        int updated = jdbc.update("""
                update listing set status = ?, current_version_id = ?, version = ?, updated_at = now(),
                    updated_by = current_setting('waver.org_id', true)
                where id = ? and version = ?
                """, l.status().name(), l.currentVersionId().orElse(null), l.version(), l.id(), expectedVersion);
        if (updated != 1) {
            return false;
        }
        writeChildren(l);
        return true;
    }

    /** Versions in ascending order, so a superseded version leaves PUBLISHED before the new one enters it. */
    private void writeChildren(Listing l) {
        for (ListingVersion v : l.versions()) {
            ListingVersion.Content c = v.content();
            jdbc.update("""
                    insert into listing_version (id, listing_id, version_no, sku_ids, requirements, docs_ref, support_policy, status,
                        submitted_at, submitted_by, published_at, approved_by, return_reason)
                    values (?, ?, ?, ?::uuid[], ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do update set sku_ids = excluded.sku_ids, requirements = excluded.requirements,
                        docs_ref = excluded.docs_ref, support_policy = excluded.support_policy, status = excluded.status,
                        submitted_at = excluded.submitted_at, submitted_by = excluded.submitted_by, published_at = excluded.published_at,
                        approved_by = excluded.approved_by, return_reason = excluded.return_reason
                    """, v.id(), l.id(), v.versionNo(), c.skuIds().stream().map(UUID::toString).toArray(String[]::new),
                    c.requirements(), c.docsRef(), c.supportPolicy(), v.status().name(), ts(v.submittedAt()), v.submittedBy().orElse(null),
                    ts(v.publishedAt()), v.approvedBy().orElse(null), v.returnReason().orElse(null));
        }
        l.availability().ifPresent(a -> jdbc.update("""
                insert into availability_rule (listing_id, mode, include_refs, exclude_refs, valid_from, valid_to, updated_by)
                values (?, ?, ?::text[], ?::text[], ?, ?, current_setting('waver.org_id', true))
                on conflict (listing_id) do update set mode = excluded.mode, include_refs = excluded.include_refs,
                    exclude_refs = excluded.exclude_refs, valid_from = excluded.valid_from, valid_to = excluded.valid_to,
                    updated_at = now(), updated_by = excluded.updated_by
                """, l.id(), a.mode().name(), a.include().toArray(String[]::new), a.exclude().toArray(String[]::new),
                ts(a.from()), ts(a.until())));
    }

    @Override
    public Optional<Listing> findById(UUID id) {
        return jdbc.query("select " + COLUMNS + " from listing where id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public boolean existsByKey(String listingKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from listing where listing_key = ?)", Boolean.class, listingKey));
    }

    @Override
    public List<Listing> list(Listing.Status status, UUID afterId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from listing where true");
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

    @Override
    public Optional<String> tierOf(OrgId resellerOrgId) {
        return jdbc.queryForList("select tier_key from reseller_tier where org_id = ?", String.class, resellerOrgId.value()).stream().findFirst();
    }

    private Listing map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<ListingVersion> versions = jdbc.query("""
                select id, version_no, sku_ids, requirements, docs_ref, support_policy, status, submitted_at, submitted_by,
                    published_at, approved_by, return_reason
                from listing_version where listing_id = ? order by version_no
                """, (v, i) -> ListingVersion.restore(v.getObject("id", UUID.class), v.getInt("version_no"),
                new ListingVersion.Content(Arrays.stream(uuids(v.getArray("sku_ids"))).toList(), v.getString("requirements"),
                        v.getString("docs_ref"), v.getString("support_policy")),
                ListingVersion.Status.valueOf(v.getString("status")), instant(v.getTimestamp("submitted_at")), v.getString("submitted_by"),
                instant(v.getTimestamp("published_at")), v.getString("approved_by"), v.getString("return_reason")), id);
        AvailabilityRule rule = jdbc.query("""
                select mode, include_refs, exclude_refs, valid_from, valid_to from availability_rule where listing_id = ?
                """, (a, i) -> new AvailabilityRule(AvailabilityRule.Mode.valueOf(a.getString("mode")), strings(a.getArray("include_refs")),
                strings(a.getArray("exclude_refs")), instant(a.getTimestamp("valid_from")), instant(a.getTimestamp("valid_to"))), id)
                .stream().findFirst().orElse(null);
        return Listing.restore(id, rs.getString("listing_key"), rs.getObject("product_id", UUID.class), rs.getString("category"),
                rs.getString("title"), rs.getString("summary"), versions, Listing.Status.valueOf(rs.getString("status")),
                rs.getObject("current_version_id", UUID.class), rule, rs.getLong("version"), rs.getTimestamp("created_at").toInstant());
    }

    private static UUID[] uuids(Array array) throws SQLException {
        return array == null ? new UUID[0] : (UUID[]) array.getArray();
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Timestamp ts(Optional<Instant> instant) {
        return instant.map(Timestamp::from).orElse(null);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
