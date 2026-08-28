package app.tabikime.kimetabi.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import app.tabikime.kimetabi.identity.AppPrincipal;
import app.tabikime.kimetabi.support.event.OutboxEventWriter;
import app.tabikime.kimetabi.sync.RevisionDeliveryFixture.DeliveredEvent;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class M7RevisionRecoveryApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("kimetabi")
                    .withUsername("kimetabi")
                    .withPassword("kimetabi");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OutboxEventWriter eventWriter;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
                            1, '同期する旅', '東京', DATE '2030-08-10', DATE '2030-08-11',
                            'Asia/Tokyo', 2, 0
                        );
                        INSERT INTO trip_member (
                            id, trip_id, firebase_uid, name, role, status
                        ) VALUES
                            (1, 1, 'owner-uid', 'オーナー', 'OWNER', 'ACTIVE'),
                            (2, 1, 'member-uid', '参加者', 'MEMBER', 'ACTIVE');
                        UPDATE trip SET owner_member_id = 1 WHERE id = 1;
                        """).update();

        commitMemberChange("参加者 1", "MEMBER_JOINED");
        commitMemberChange("参加者 2", "MEMBER_ROLE_CHANGED");
        commitMemberChange("最新の参加者", "MEMBER_ROLE_CHANGED");
    }

    @Test
    void recoverySnapshotConvergesAfterDuplicateMissingAndReversedDelivery() throws Exception {
        List<DeliveredEvent> committed = committedEvents();

        List<DeliveredEvent> duplicated = RevisionDeliveryFixture.duplicate(committed, 1);
        assertThat(duplicated).extracting(DeliveredEvent::eventId)
                .containsSequence(committed.get(1).eventId(), committed.get(1).eventId());
        assertLatestSnapshot();

        List<DeliveredEvent> missing = RevisionDeliveryFixture.omit(committed, 1);
        assertThat(missing).extracting(DeliveredEvent::tripRevision)
                .containsExactly(1L, 3L);
        assertLatestSnapshot();

        List<DeliveredEvent> reversed = RevisionDeliveryFixture.reverse(committed);
        assertThat(reversed).extracting(DeliveredEvent::tripRevision)
                .containsExactly(3L, 2L, 1L);
        assertLatestSnapshot();
    }

    @Test
    void recoverySnapshotRemainsProtectedByTripMembership() throws Exception {
        mockMvc.perform(get("/api/trips/1").with(principal("outsider-uid")))
                .andExpect(status().isNotFound());
    }

    private void assertLatestSnapshot() throws Exception {
        mockMvc.perform(get("/api/trips/1").with(principal("owner-uid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.revision").value(3))
                .andExpect(jsonPath("$.members[1].id").value(2))
                .andExpect(jsonPath("$.members[1].name").value("最新の参加者"));
    }

    private void commitMemberChange(String name, String eventType) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            UPDATE trip_member
                            SET name = :name, updated_at = CURRENT_TIMESTAMP
                            WHERE id = 2 AND trip_id = 1
                            """).param("name", name).update();
            long revision = eventWriter.nextRevision(1);
            eventWriter.write(1, revision, eventType, "member", 2, null);
        });
    }

    private List<DeliveredEvent> committedEvents() {
        return jdbcClient.sql("""
                        SELECT id, trip_revision
                        FROM outbox_event
                        WHERE trip_id = 1
                        ORDER BY trip_revision
                        """)
                .query((resultSet, rowNumber) -> new DeliveredEvent(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("trip_revision")))
                .list();
    }

    private static RequestPostProcessor principal(String uid) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new AppPrincipal(uid),
                null,
                List.of()));
    }
}
