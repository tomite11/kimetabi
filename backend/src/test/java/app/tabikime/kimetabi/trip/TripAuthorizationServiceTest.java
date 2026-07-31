package app.tabikime.kimetabi.trip;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class TripAuthorizationServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("kimetabi")
                    .withUsername("kimetabi")
                    .withPassword("kimetabi");

    @Autowired
    private TripAuthorizationService authorization;

    @Autowired
    private TripService tripService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("""
                        TRUNCATE trip_member_unsettled_balance, idempotency_request,
                                 slot, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """)
                .update();
        tripService.create(
                "owner-uid",
                UUID.randomUUID(),
                new CreateTripRequest(
                        "旅行",
                        "東京",
                        java.time.LocalDate.of(2026, 8, 1),
                        java.time.LocalDate.of(2026, 8, 2),
                        "Asia/Tokyo",
                        3,
                        "Owner",
                        null,
                        null));
        insertMember("organizer-uid", "ORGANIZER", "ACTIVE");
        insertMember("member-uid", "MEMBER", "ACTIVE");
        insertMember("left-uid", "MEMBER", "LEFT");
    }

    @Test
    void deniesNonMemberAndInactiveMemberWithoutDisclosingTrip() {
        assertThatThrownBy(() -> authorization.require(
                "other-uid", 1, TripPermission.VIEW_TRIP))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> authorization.require(
                "left-uid", 1, TripPermission.VIEW_TRIP))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void appliesRoleTableFromSpecification() {
        assertThatThrownBy(() -> authorization.require(
                "member-uid", 1, TripPermission.ADOPT_CANDIDATE))
                .isInstanceOf(TripForbiddenException.class);
        assertThatCode(() -> authorization.require(
                "organizer-uid", 1, TripPermission.ADOPT_CANDIDATE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> authorization.require(
                "organizer-uid", 1, TripPermission.MANAGE_MEMBERS))
                .isInstanceOf(TripForbiddenException.class);
        assertThatCode(() -> authorization.require(
                "owner-uid", 1, TripPermission.MANAGE_MEMBERS))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNestedResourcesFromAnotherTrip() {
        tripService.create(
                "other-owner",
                UUID.randomUUID(),
                new CreateTripRequest(
                        "別旅行",
                        "大阪",
                        java.time.LocalDate.of(2026, 9, 1),
                        java.time.LocalDate.of(2026, 9, 1),
                        "Asia/Tokyo",
                        1,
                        "Other",
                        null,
                        null));
        long otherSlotId = jdbcClient.sql(
                        "SELECT id FROM slot WHERE trip_id = 2 ORDER BY id LIMIT 1")
                .query(Long.class)
                .single();

        assertThatThrownBy(() -> authorization.requireSlotResource(
                "owner-uid",
                1,
                TripPermission.VIEW_TRIP,
                otherSlotId))
                .isInstanceOf(TripNotFoundException.class);
    }

    private void insertMember(String uid, String role, String status) {
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status, left_at
                        )
                        VALUES (
                            1, :uid, :uid, :role, :status,
                            CASE WHEN :status = 'ACTIVE' THEN NULL ELSE CURRENT_TIMESTAMP END
                        )
                        """)
                .param("uid", uid)
                .param("role", role)
                .param("status", status)
                .update();
    }
}
