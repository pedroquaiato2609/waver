package br.com.waver.platform.events;

import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;

/**
 * Serializes event payloads with the registry-aware Apicurio serializer (ADR-0024).
 * Schemas are registered by CI, never by services ({@code auto-register=false}); the registry ID travels
 * inside the payload (not in headers) because Debezium copies only the outbox columns to Kafka.
 */
public class AvroEventSerializer implements AutoCloseable {

    private final AvroKafkaSerializer<SpecificRecord> delegate = new AvroKafkaSerializer<>();

    public AvroEventSerializer(String registryUrl) {
        delegate.configure(Map.of(
                "apicurio.registry.url", registryUrl,
                "apicurio.registry.auto-register", "false",
                "apicurio.registry.find-latest", "true",
                "apicurio.registry.headers.enabled", "false",
                "apicurio.registry.artifact-resolver-strategy", "io.apicurio.registry.serde.avro.strategy.TopicRecordIdStrategy"
        ), false);
    }

    public byte[] serialize(String topic, SpecificRecord record) {
        return delegate.serialize(topic, record);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
