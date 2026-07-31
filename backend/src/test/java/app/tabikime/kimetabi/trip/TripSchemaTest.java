package app.tabikime.kimetabi.trip;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class TripSchemaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("kimetabi")
                    .withUsername("kimetabi")
                    .withPassword("kimetabi");

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("""
                        TRUNCATE idempotency_request, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """)
                .update();
    }

    @Test
    @Transactional
    void rejectsSecondActiveOwnerForSameTrip() {
        long tripId = insertTrip();
        insertMember(tripId, "owner-1", "OWNER");

        assertThatThrownBy(() -> insertMember(tripId, "owner-2", "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsMemberWhoseStatusAndLeftAtDisagree() {
        long tripId = insertTrip();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status, left_at
                        )
                        VALUES (
                            :tripId, 'left-user', '退出者', 'MEMBER', 'LEFT', NULL
                        )
                        """)
                .param("tripId", tripId)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertTrip() {
        return jdbcClient.sql("""
                        INSERT INTO trip (
                            title, destination, starts_on, ends_on, timezone,
                            expected_member_count, vote_visibility
                        )
                        VALUES (
                            'テスト旅行', '東京', DATE '2030-01-01', DATE '2030-01-02',
                            'Asia/Tokyo', 2, 'NAMED'
                        )
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
    }

    private void insertMember(long tripId, String firebaseUid, String role) {
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status
                        )
                        VALUES (:tripId, :firebaseUid, 'メンバー', :role, 'ACTIVE')
                        """)
                .param("tripId", tripId)
                .param("firebaseUid", firebaseUid)
                .param("role", role)
                .update();
    }
}
