package app.tabikime.kimetabi.async;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OutboxDispatchRepository {

    private final JdbcClient jdbcClient;

    OutboxDispatchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<PendingEvent> lockPendingEvents(int limit) {
        return jdbcClient.sql("""
                        SELECT id, trip_id, event_type, resource_id, payload::text
                        FROM outbox_event
                        WHERE published_at IS NULL
                        ORDER BY created_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new PendingEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("trip_id"),
                        resultSet.getString("event_type"),
                        resultSet.getLong("resource_id"),
                        resultSet.getString("payload")))
                .list();
    }

    void markPublished(UUID eventId, String outcomeCode) {
        jdbcClient.sql("""
                        UPDATE outbox_event
                        SET published_at = CURRENT_TIMESTAMP,
                            attempts = attempts + 1,
                            last_outcome_code = :outcomeCode
                        WHERE id = :eventId AND published_at IS NULL
                        """)
                .param("eventId", eventId)
                .param("outcomeCode", outcomeCode)
                .update();
    }

    void markFailed(UUID eventId, String outcomeCode) {
        jdbcClient.sql("""
                        UPDATE outbox_event
                        SET attempts = attempts + 1,
                            last_outcome_code = :outcomeCode
                        WHERE id = :eventId AND published_at IS NULL
                        """)
                .param("eventId", eventId)
                .param("outcomeCode", outcomeCode)
                .update();
    }

    long countPendingEvents() {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL
                        """)
                .query(Long.class)
                .single();
    }

    double oldestPendingAgeSeconds() {
        return jdbcClient.sql("""
                        SELECT COALESCE(
                            EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - MIN(created_at)), 0)
                        FROM outbox_event
                        WHERE published_at IS NULL
                        """)
                .query(Double.class)
                .single();
    }

    record PendingEvent(
            UUID eventId,
            long tripId,
            String eventType,
            long resourceId,
            String payload
    ) {

        boolean isMetadataTaskRequest() {
            return eventType.equals("CANDIDATE_METADATA_REQUESTED");
        }
    }
}
