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

    List<PendingMetadataEvent> lockPendingMetadataEvents(int limit) {
        return jdbcClient.sql("""
                        SELECT id, resource_id
                        FROM outbox_event
                        WHERE published_at IS NULL
                          AND event_type = 'CANDIDATE_METADATA_REQUESTED'
                        ORDER BY created_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new PendingMetadataEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("resource_id")))
                .list();
    }

    void markPublished(UUID eventId) {
        jdbcClient.sql("""
                        UPDATE outbox_event
                        SET published_at = CURRENT_TIMESTAMP,
                            attempts = attempts + 1,
                            last_outcome_code = 'TASK_CREATED'
                        WHERE id = :eventId AND published_at IS NULL
                        """)
                .param("eventId", eventId)
                .update();
    }

    void markFailed(UUID eventId) {
        jdbcClient.sql("""
                        UPDATE outbox_event
                        SET attempts = attempts + 1,
                            last_outcome_code = 'TASK_CREATE_FAILED'
                        WHERE id = :eventId AND published_at IS NULL
                        """)
                .param("eventId", eventId)
                .update();
    }

    record PendingMetadataEvent(UUID eventId, long candidateId) {
    }
}
