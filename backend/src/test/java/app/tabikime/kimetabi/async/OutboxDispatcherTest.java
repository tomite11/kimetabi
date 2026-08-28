package app.tabikime.kimetabi.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import app.tabikime.kimetabi.realtime.TripEventPublisher;

@Testcontainers
@SpringBootTest
@Import(OutboxDispatcherTest.Configuration.class)
class OutboxDispatcherTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("kimetabi")
                    .withUsername("kimetabi")
                    .withPassword("kimetabi");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private RecordingTaskGateway gateway;

    @Autowired
    private TripEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("""
                        TRUNCATE idempotency_request, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO trip (
                            id, title, destination, starts_on, ends_on,
                            timezone, expected_member_count, revision
                        ) VALUES (
                            1, '旅行', '東京', DATE '2026-08-10', DATE '2026-08-11',
                            'Asia/Tokyo', 2, 1
                        )
                        """).update();
        gateway.created.clear();
        gateway.fail = false;
        gateway.failAfterCreateOnce = false;
        reset(eventPublisher);
    }

    @Test
    void dispatchesEachEventOnceAndTreatsRecoveryAsIdempotent() {
        UUID eventId = insertEvent();

        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(1, 1, 0));
        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(0, 0, 0));
        assertThat(gateway.created).containsExactly(new CreatedTask(eventId, 42));
        assertThat(jdbcClient.sql("""
                        SELECT attempts, last_outcome_code, published_at IS NOT NULL
                        FROM outbox_event WHERE id = :eventId
                        """).param("eventId", eventId)
                .query((row, number) -> List.of(
                        row.getInt(1), row.getString(2), row.getBoolean(3)))
                .single()).containsExactly(1, "TASK_CREATED", true);
    }

    @Test
    void schedulerRetryRecoversFailedDispatchWithoutLosingEvent() {
        UUID eventId = insertEvent();
        gateway.fail = true;

        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(1, 0, 1));
        assertThat(jdbcClient.sql("""
                        SELECT attempts, last_outcome_code, published_at IS NULL
                        FROM outbox_event WHERE id = :eventId
                        """).param("eventId", eventId)
                .query((row, number) -> List.of(
                        row.getInt(1), row.getString(2), row.getBoolean(3)))
                .single()).containsExactly(1, "TASK_CREATE_FAILED", true);

        gateway.fail = false;
        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(1, 1, 0));
        assertThat(gateway.created).containsExactly(new CreatedTask(eventId, 42));
    }

    @Test
    void recoversWhenTaskWasCreatedButDispatchResponseWasLost() {
        UUID eventId = insertEvent();
        gateway.failAfterCreateOnce = true;

        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(1, 0, 1));
        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(1, 1, 0));
        assertThat(gateway.created).containsExactly(new CreatedTask(eventId, 42));
    }

    @Test
    void publishesCommittedTripEventAndMarksItOnlyAfterStompSend() {
        UUID eventId = insertRealtimeEvent();

        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(1, 1, 0));

        verify(eventPublisher).publish(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        eventId.toString().equals(payload.get("eventId"))
                                && "MEMBER_JOINED".equals(payload.get("type"))));
        assertThat(jdbcClient.sql("""
                        SELECT attempts, last_outcome_code, published_at IS NOT NULL
                        FROM outbox_event WHERE id = :eventId
                        """).param("eventId", eventId)
                .query((row, number) -> List.of(
                        row.getInt(1), row.getString(2), row.getBoolean(3)))
                .single()).containsExactly(1, "STOMP_PUBLISHED", true);
    }

    @Test
    void schedulerRetryRecoversStompFailureWithAtLeastOnceDelivery() {
        insertRealtimeEvent();
        doThrow(new IllegalStateException("broker unavailable fixture"))
                .doNothing()
                .when(eventPublisher).publish(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.anyMap());

        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(1, 0, 1));
        assertThat(dispatcher.dispatch(50))
                .isEqualTo(new OutboxDispatcher.DispatchResult(1, 1, 0));

        verify(eventPublisher, times(2)).publish(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyMap());
    }

    private UUID insertEvent() {
        UUID eventId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO outbox_event (
                            id, trip_id, trip_revision, event_type, resource_type,
                            resource_id, resource_version, payload
                        ) VALUES (
                            :eventId, 1, 1, 'CANDIDATE_METADATA_REQUESTED',
                            'candidate', 42, 0, '{}'::jsonb
                        )
                        """).param("eventId", eventId).update();
        return eventId;
    }

    private UUID insertRealtimeEvent() {
        UUID eventId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO outbox_event (
                            id, trip_id, trip_revision, event_type, resource_type,
                            resource_id, payload
                        ) VALUES (
                            :eventId, 1, 1, 'MEMBER_JOINED', 'member', 42,
                            CAST(:payload AS jsonb)
                        )
                        """)
                .param("eventId", eventId)
                .param("payload", """
                        {"eventId":"%s","tripId":1,"tripRevision":1,
                         "type":"MEMBER_JOINED","resourceType":"member",
                         "resourceId":42,"occurredAt":"2026-08-28T00:00:00Z"}
                        """.formatted(eventId))
                .update();
        return eventId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        RecordingTaskGateway recordingTaskGateway() {
            return new RecordingTaskGateway();
        }

        @Bean
        @Primary
        TripEventPublisher tripEventPublisher() {
            return mock(TripEventPublisher.class);
        }
    }

    static final class RecordingTaskGateway implements MetadataTaskGateway {

        private final List<CreatedTask> created = new ArrayList<>();
        private boolean fail;
        private boolean failAfterCreateOnce;

        @Override
        public void create(UUID eventId, long candidateId) throws IOException {
            if (fail) throw new IOException("fixture failure");
            CreatedTask task = new CreatedTask(eventId, candidateId);
            if (!created.contains(task)) created.add(task);
            if (failAfterCreateOnce) {
                failAfterCreateOnce = false;
                throw new IOException("fixture lost response after create");
            }
        }
    }

    record CreatedTask(UUID eventId, long candidateId) {
    }
}
