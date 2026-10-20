package br.com.waver.platform.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumption (AR-006, ADR-0012): {@code processed_event(consumer_group, event_id)}.
 * Call inside the consumer's transaction; returns false when the event was already handled.
 */
public class Inbox {

    private final JdbcTemplate jdbc;

    public Inbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumerGroup, String eventId) {
        int inserted = jdbc.update(
                "insert into processed_event (consumer_group, event_id) values (?, ?) on conflict do nothing",
                consumerGroup, eventId);
        return inserted == 1;
    }
}
