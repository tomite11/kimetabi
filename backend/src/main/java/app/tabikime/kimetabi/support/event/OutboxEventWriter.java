package app.tabikime.kimetabi.support.event;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxEventWriter {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public long nextRevision(long tripId) {
        return jdbcClient.sql("""
                        UPDATE trip
                        SET revision = revision + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tripId AND deleted_at IS NULL
                        RETURNING revision
                        """)
                .param("tripId", tripId)
                .query(Long.class)
                .single();
    }

    public UUID write(
            long tripId,
            long tripRevision,
            String eventType,
            String resourceType,
            long resourceId,
            Long resourceVersion
    ) {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        TripEvent event = new TripEvent(
                eventId,
                tripId,
                tripRevision,
                eventType,
                resourceType,
                resourceId,
                resourceVersion,
                occurredAt);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize outbox event", exception);
        }
        jdbcClient.sql("""
                        INSERT INTO outbox_event (
                            id, trip_id, trip_revision, event_type, resource_type,
                            resource_id, resource_version, payload, created_at
                        ) VALUES (
                            :id, :tripId, :tripRevision, :eventType, :resourceType,
                            :resourceId, :resourceVersion, CAST(:payload AS jsonb), :occurredAt
                        )
                        """)
                .param("id", eventId)
                .param("tripId", tripId)
                .param("tripRevision", tripRevision)
                .param("eventType", eventType)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("resourceVersion", resourceVersion)
                .param("payload", payload)
                .param("occurredAt", occurredAt)
                .update();
        return eventId;
    }

    private record TripEvent(
            UUID eventId,
            long tripId,
            long tripRevision,
            String type,
            String resourceType,
            long resourceId,
            Long resourceVersion,
            OffsetDateTime occurredAt
    ) {
    }
}
