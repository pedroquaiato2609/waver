package br.com.waver.platform.events;

import br.com.waver.kernel.id.Ids;
import br.com.waver.kernel.tenancy.TenantContext;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.platform.web.CorrelationIdFilter;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events to the service's transactional outbox (RFC-0006 §8). Must run inside the domain transaction:
 * the aggregate is written first, then the outbox row; Debezium relays it to Kafka. Nothing is sent to Kafka here.
 */
public class EventPublisher {

    private static final String INSERT = """
            insert into outbox_event (id, org_id, aggregate_type, aggregate_id, topic, payload, ce_type, ce_source,
                ce_subject, ce_time, ce_dataschema, ce_orgid, ce_correlationid, ce_causationid, ce_actorid, ce_actortype,
                ce_aggregateversion)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final AvroEventSerializer serializer;
    private final String source;

    public EventPublisher(JdbcTemplate jdbc, AvroEventSerializer serializer, String serviceName) {
        this.jdbc = jdbc;
        this.serializer = serializer;
        this.source = "urn:waver:service:" + serviceName;
    }

    /** One event about one aggregate. {@code ceType} follows CONVENTIONS: waver.<context>.<EventName>.v<major>. */
    public record Event(String topic, String ceType, String aggregateType, UUID aggregateId, UUID orgId,
                        long aggregateVersion, SpecificRecord data) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(Event event) {
        UUID eventId = Ids.newId();
        TenantContext actor = TenantContextHolder.current().orElse(null);
        String correlationId = CorrelationIdFilter.current();
        byte[] payload = serializer.serialize(event.topic(), event.data());
        // Apicurio TopicRecordIdStrategy coordinates: group = Avro namespace, artifact = <topic>-<EventName>.
        String schemaRef = event.data().getSchema().getNamespace() + "/" + event.topic() + "-" + event.data().getSchema().getName();

        jdbc.update(INSERT, ps -> {
            ps.setObject(1, eventId);
            if (event.orgId() == null) {
                ps.setNull(2, Types.OTHER);
            } else {
                ps.setObject(2, event.orgId());
            }
            ps.setString(3, event.aggregateType());
            ps.setObject(4, event.aggregateId());
            ps.setString(5, event.topic());
            ps.setBytes(6, payload);
            ps.setString(7, event.ceType());
            ps.setString(8, source);
            ps.setString(9, event.aggregateType() + "/" + event.aggregateId());
            ps.setString(10, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
            ps.setString(11, "apicurio:" + schemaRef);
            ps.setString(12, event.orgId() == null ? "" : event.orgId().toString());
            ps.setString(13, correlationId);
            ps.setString(14, correlationId);
            ps.setString(15, actor == null ? source : actor.principalId());
            ps.setString(16, actor == null ? "SERVICE" : actor.principalType().name());
            ps.setString(17, Long.toString(event.aggregateVersion()));
        });
        return eventId;
    }
}
