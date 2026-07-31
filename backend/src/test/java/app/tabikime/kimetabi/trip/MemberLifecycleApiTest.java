package app.tabikime.kimetabi.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class MemberLifecycleApiTest {

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
    private TripService tripService;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("""
                        TRUNCATE trip_member_unsettled_balance, idempotency_request,
                                 slot, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """)
                .update();
    }

    @Test
    void ownerTransfersOwnershipAtomically() throws Exception {
        createTrip();
        long memberId = insertMember("member-uid", "MEMBER");

        mockMvc.perform(post("/api/trips/1/owner-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberId": %d, "version": 0}
                                """.formatted(memberId))
                        .with(principal("owner-uid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trip.version").value(1))
                .andExpect(jsonPath("$.trip.revision").value(1))
                .andExpect(jsonPath("$.members[0].role").value("ORGANIZER"))
                .andExpect(jsonPath("$.members[1].role").value("OWNER"));

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM trip_member
                        WHERE trip_id = 1 AND role = 'OWNER' AND status = 'ACTIVE'
                        """).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void ownerCannotLeaveBeforeTransfer() throws Exception {
        createTrip();

        mockMvc.perform(post("/api/trips/1/leave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}")
                        .with(principal("owner-uid")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void memberLeavesLogicallyAndHistoryRowRemains() throws Exception {
        createTrip();
        insertMember("member-uid", "MEMBER");

        mockMvc.perform(post("/api/trips/1/leave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}")
                        .with(principal("member-uid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[1].status").value("LEFT"));

        assertThat(memberStatus("member-uid")).isEqualTo("LEFT");
    }

    @Test
    void onlyOwnerCanRemoveAndRemovalIsLogical() throws Exception {
        createTrip();
        long organizerId = insertMember("organizer-uid", "ORGANIZER");
        long memberId = insertMember("member-uid", "MEMBER");

        mockMvc.perform(delete("/api/trips/1/members/{memberId}", memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}")
                        .with(principal("organizer-uid")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/trips/1/members/{memberId}", memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}")
                        .with(principal("owner-uid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[2].status").value("REMOVED"));

        assertThat(memberStatus("member-uid")).isEqualTo("REMOVED");
        assertThat(organizerId).isPositive();
    }

    @Test
    void rejectsLeavingOrRemovalWithUnsettledBalance() throws Exception {
        createTrip();
        long memberId = insertMember("member-uid", "MEMBER");
        jdbcClient.sql("""
                        INSERT INTO trip_member_unsettled_balance (trip_id, member_id, balance_yen)
                        VALUES (1, :memberId, -1200)
                        """).param("memberId", memberId).update();

        mockMvc.perform(post("/api/trips/1/leave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}")
                        .with(principal("member-uid")))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(delete("/api/trips/1/members/{memberId}", memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}")
                        .with(principal("owner-uid")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(memberStatus("member-uid")).isEqualTo("ACTIVE");
    }

    @Test
    void staleMutationReturnsCurrentSnapshot() throws Exception {
        createTrip();
        long memberId = insertMember("member-uid", "MEMBER");
        jdbcClient.sql("UPDATE trip SET version = 2, revision = 2 WHERE id = 1").update();

        mockMvc.perform(delete("/api/trips/1/members/{memberId}", memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}")
                        .with(principal("owner-uid")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.current.trip.version").value(2));
    }

    @Test
    void targetFromAnotherTripIsHiddenAsNotFound() throws Exception {
        createTrip();
        long otherTripId = tripService.create(
                "other-owner", UUID.randomUUID(), request("別旅行")).trip().id();
        long otherMemberId = jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status
                        ) VALUES (:tripId, 'outsider', '別旅行メンバー', 'MEMBER', 'ACTIVE')
                        RETURNING id
                        """).param("tripId", otherTripId).query(Long.class).single();

        mockMvc.perform(delete("/api/trips/1/members/{memberId}", otherMemberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 0}")
                        .with(principal("owner-uid")))
                .andExpect(status().isNotFound());
    }

    @Test
    void inactiveTransferTargetIsRejectedWithoutChangingOwnerOrVersion() throws Exception {
        createTrip();
        long memberId = insertMember("member-uid", "MEMBER");
        jdbcClient.sql("""
                        UPDATE trip_member
                        SET status = 'LEFT', left_at = CURRENT_TIMESTAMP
                        WHERE id = :memberId
                        """).param("memberId", memberId).update();

        mockMvc.perform(post("/api/trips/1/owner-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberId": %d, "version": 0}
                                """.formatted(memberId))
                        .with(principal("owner-uid")))
                .andExpect(status().isNotFound());

        assertThat(jdbcClient.sql("""
                        SELECT firebase_uid
                        FROM trip_member
                        WHERE id = (SELECT owner_member_id FROM trip WHERE id = 1)
                        """).query(String.class).single()).isEqualTo("owner-uid");
        assertThat(jdbcClient.sql("SELECT version FROM trip WHERE id = 1")
                .query(Long.class).single()).isZero();
    }

    private void createTrip() {
        tripService.create("owner-uid", UUID.randomUUID(), request("旅行"));
    }

    private CreateTripRequest request(String title) {
        return new CreateTripRequest(
                title, "東京",
                java.time.LocalDate.of(2030, 1, 1),
                java.time.LocalDate.of(2030, 1, 2),
                "Asia/Tokyo", 3, "OWNER", null, null);
    }

    private long insertMember(String uid, String role) {
        return jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status
                        ) VALUES (1, :uid, :uid, :role, 'ACTIVE')
                        RETURNING id
                        """)
                .param("uid", uid)
                .param("role", role)
                .query(Long.class)
                .single();
    }

    private String memberStatus(String uid) {
        return jdbcClient.sql("""
                        SELECT status FROM trip_member
                        WHERE trip_id = 1 AND firebase_uid = :uid
                        """).param("uid", uid).query(String.class).single();
    }

    private static RequestPostProcessor principal(String uid) {
        AppPrincipal principal = new AppPrincipal(uid);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, "token", java.util.List.of()));
    }
}
