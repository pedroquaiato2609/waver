package br.com.waver.platform.events;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Reads CloudEvents headers set by the Debezium outbox router (string values may arrive JSON-quoted). */
public final class EventHeaders {

    private EventHeaders() {
    }

    public static String text(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        if (h == null || h.value() == null) {
            return null;
        }
        String value = new String(h.value(), StandardCharsets.UTF_8);
        return value.length() > 1 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }

    /** The event ID used for inbox deduplication (outbox column {@code id}). */
    public static String eventId(ConsumerRecord<?, ?> record) {
        return text(record, "id");
    }
}
