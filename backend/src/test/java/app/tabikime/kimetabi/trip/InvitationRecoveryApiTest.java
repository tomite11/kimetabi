package app.tabikime.kimetabi.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class InvitationRecoveryApiTest {

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

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("""
                        TRUNCATE token_rate_limit, recovery_token, invite_token,
                                 trip_member_unsettled_balance, idempotency_request,
                                 slot, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """)
                .update();
        createTrip("owner-uid");
    }

    @Test
    void onlyOwnerCreatesInvitationAndPlaintextIsNotStored() throws Exception {
        insertMember(1, "organizer-uid", "ORGANIZER", "ACTIVE");

        mockMvc.perform(post("/api/trips/1/invitations")
                        .with(principal("organizer-uid")))
                .andExpect(status().isForbidden());

        MvcResult result = createInvitation();
        String token = tokenFrom(result);

        assertThat(token).hasSize(43);
        assertThat(jdbcClient.sql("SELECT token_hash FROM invite_token")
                .query(String.class).single())
                .hasSize(64)
                .doesNotContain(token);
        OffsetDateTime expiry = jdbcClient.sql("SELECT expires_at FROM invite_token")
                .query(OffsetDateTime.class)
                .single();
        OffsetDateTime creation = jdbcClient.sql("SELECT created_at FROM invite_token")
                .query(OffsetDateTime.class)
                .single();
        assertThat(expiry)
                .isBetween(
                        creation.plusDays(7),
                        creation.plusDays(7).plusSeconds(1));
    }

    @Test
    void invitationIsSingleUseAndRestoresSameUidWithoutDuplicateMember() throws Exception {
        String token = tokenFrom(createInvitation());

        mockMvc.perform(post("/api/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptInvitationJson(token, "Guest"))
                        .with(principal("guest-uid")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members[1].name").value("Guest"));

        mockMvc.perform(post("/api/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptInvitationJson(token, "Guest"))
                        .with(principal("guest-uid")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("リンクが無効です。"));

        assertThat(memberCount(1, "guest-uid")).isEqualTo(1);

        String secondToken = tokenFrom(createInvitation());
        jdbcClient.sql("""
                        UPDATE trip_member
                        SET status = 'LEFT', left_at = CURRENT_TIMESTAMP
                        WHERE trip_id = 1 AND firebase_uid = 'guest-uid'
                        """).update();

        mockMvc.perform(post("/api/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptInvitationJson(secondToken, "Guest Again"))
                        .with(principal("guest-uid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.members[1].name").value("Guest Again"));

        assertThat(memberCount(1, "guest-uid")).isEqualTo(1);
    }

    @Test
    void concurrentInvitationsForSameUidDoNotCreateDuplicateMember() throws Exception {
        String firstToken = tokenFrom(createInvitation());
        String secondToken = tokenFrom(createInvitation());

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<MvcResult>> tasks = List.of(
                    () -> acceptInvitation(firstToken, "concurrent-uid", "203.0.113.1"),
                    () -> acceptInvitation(secondToken, "concurrent-uid", "203.0.113.2"));
            var results = executor.invokeAll(tasks);

            assertThat(List.of(
                    results.get(0).get().getResponse().getStatus(),
                    results.get(1).get().getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 201);
        }
        assertThat(memberCount(1, "concurrent-uid")).isEqualTo(1);
    }

    @Test
    void expiredAndRevokedInvitationsUseSameExternalError() throws Exception {
        MvcResult expiredLink = createInvitation();
        String expiredToken = tokenFrom(expiredLink);
        jdbcClient.sql("""
                        UPDATE invite_token
                        SET created_at = CURRENT_TIMESTAMP - INTERVAL '8 days',
                            expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day'
                        WHERE id = :id
                        """)
                .param("id", idFrom(expiredLink))
                .update();

        expectInvalidInvitation(expiredToken, "expired-uid");

        MvcResult revokedLink = createInvitation();
        mockMvc.perform(delete("/api/trips/1/invitations/{id}", idFrom(revokedLink))
                        .with(principal("owner-uid")))
                .andExpect(status().isNoContent());

        expectInvalidInvitation(tokenFrom(revokedLink), "revoked-uid");
    }

    @Test
    void invitationRevocationChecksRoleAndTripOwnership() throws Exception {
        insertMember(1, "organizer-uid", "ORGANIZER", "ACTIVE");
        long invitationId = idFrom(createInvitation());
        createTrip("other-owner");

        mockMvc.perform(delete("/api/trips/1/invitations/{id}", invitationId)
                        .with(principal("organizer-uid")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/trips/2/invitations/{id}", invitationId)
                        .with(principal("other-owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void recoveryMovesMemberReferenceAndRejectsReplayAndUidConflict() throws Exception {
        long targetId = insertMember(1, "lost-uid", "MEMBER", "ACTIVE");
        MvcResult link = createRecovery(targetId);
        String token = tokenFrom(link);

        OffsetDateTime expiry = jdbcClient.sql(
                        "SELECT expires_at FROM recovery_token WHERE id = :id")
                .param("id", idFrom(link))
                .query(OffsetDateTime.class)
                .single();
        OffsetDateTime creation = jdbcClient.sql(
                        "SELECT created_at FROM recovery_token WHERE id = :id")
                .param("id", idFrom(link))
                .query(OffsetDateTime.class)
                .single();
        assertThat(expiry)
                .isBetween(
                        creation.plusHours(24),
                        creation.plusHours(24).plusSeconds(1));

        mockMvc.perform(post("/api/recoveries/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRecoveryJson(token))
                        .with(principal("replacement-uid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetId));
        assertThat(memberCount(1, "lost-uid")).isZero();
        assertThat(memberCount(1, "replacement-uid")).isEqualTo(1);

        mockMvc.perform(post("/api/recoveries/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRecoveryJson(token))
                        .with(principal("another-uid")))
                .andExpect(status().isNotFound());

        long secondTarget = insertMember(1, "second-lost", "MEMBER", "ACTIVE");
        String conflictToken = tokenFrom(createRecovery(secondTarget));
        mockMvc.perform(post("/api/recoveries/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRecoveryJson(conflictToken))
                        .with(principal("replacement-uid")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));

        long expiredTarget = insertMember(1, "expired-lost", "MEMBER", "ACTIVE");
        MvcResult expiredLink = createRecovery(expiredTarget);
        jdbcClient.sql("""
                        UPDATE recovery_token
                        SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                            expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day'
                        WHERE id = :id
                        """)
                .param("id", idFrom(expiredLink))
                .update();
        mockMvc.perform(post("/api/recoveries/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRecoveryJson(tokenFrom(expiredLink)))
                        .with(principal("expired-replacement")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("リンクが無効です。"));
    }

    @Test
    void recoveryCreationRequiresOwnerAndSameTripMember() throws Exception {
        long memberId = insertMember(1, "member-uid", "MEMBER", "ACTIVE");
        insertMember(1, "organizer-uid", "ORGANIZER", "ACTIVE");
        createTrip("other-owner");
        long otherMemberId = insertMember(2, "other-member", "MEMBER", "ACTIVE");

        mockMvc.perform(post("/api/trips/1/members/{id}/recovery-links", memberId)
                        .with(principal("organizer-uid")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/trips/1/members/{id}/recovery-links", otherMemberId)
                        .with(principal("owner-uid")))
                .andExpect(status().isNotFound());
    }

    @Test
    void limitsInvalidTokenAttemptsByIpAndTokenHash() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/invitations/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(acceptInvitationJson(
                                    "invalid-token-value-0000000000000000-" + attempt,
                                    "Guest"))
                            .with(remoteAddress("203.0.113.10"))
                            .with(principal("guest-" + attempt)))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/api/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptInvitationJson(
                                "invalid-token-value-0000000000000000-6",
                                "Guest"))
                        .with(remoteAddress("203.0.113.10"))
                        .with(principal("guest-6")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));

        jdbcClient.sql("TRUNCATE token_rate_limit").update();
        String repeatedToken = "same-invalid-token-value-000000000000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/recoveries/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(acceptRecoveryJson(repeatedToken))
                            .with(remoteAddress("203.0.113." + attempt))
                            .with(principal("replacement-" + attempt)))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/api/recoveries/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRecoveryJson(repeatedToken))
                        .with(remoteAddress("203.0.113.99"))
                        .with(principal("replacement-6")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        jdbcClient.sql("""
                        UPDATE token_rate_limit
                        SET window_started_at =
                            CURRENT_TIMESTAMP - INTERVAL '16 minutes'
                        """).update();
        mockMvc.perform(post("/api/recoveries/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRecoveryJson(repeatedToken))
                        .with(remoteAddress("203.0.113.99"))
                        .with(principal("replacement-after-window")))
                .andExpect(status().isNotFound());
    }

    private MvcResult createInvitation() throws Exception {
        return mockMvc.perform(post("/api/trips/1/invitations")
                        .with(principal("owner-uid")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.url").isString())
                .andReturn();
    }

    private MvcResult createRecovery(long memberId) throws Exception {
        return mockMvc.perform(post(
                        "/api/trips/1/members/{memberId}/recovery-links", memberId)
                        .with(principal("owner-uid")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
    }

    private void expectInvalidInvitation(String token, String uid) throws Exception {
        mockMvc.perform(post("/api/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptInvitationJson(token, "Guest"))
                        .with(principal(uid)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("リンクが無効です。"));
    }

    private MvcResult acceptInvitation(String token, String uid, String address)
            throws Exception {
        return mockMvc.perform(post("/api/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptInvitationJson(token, "Guest"))
                        .with(remoteAddress(address))
                        .with(principal(uid)))
                .andReturn();
    }

    private String tokenFrom(MvcResult result) throws Exception {
        String url = json(result).get("url").stringValue();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private long idFrom(MvcResult result) throws Exception {
        return json(result).get("id").longValue();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String acceptInvitationJson(String token, String name) {
        return """
                {"token":"%s","name":"%s"}
                """.formatted(token, name);
    }

    private String acceptRecoveryJson(String token) {
        return """
                {"token":"%s"}
                """.formatted(token);
    }

    private void createTrip(String ownerUid) {
        tripService.create(
                ownerUid,
                UUID.randomUUID(),
                new CreateTripRequest(
                        "旅行",
                        "東京",
                        java.time.LocalDate.of(2026, 8, 1),
                        java.time.LocalDate.of(2026, 8, 2),
                        "Asia/Tokyo",
                        3,
                        ownerUid,
                        null,
                        null));
    }

    private long insertMember(
            long tripId,
            String uid,
            String role,
            String status
    ) {
        return jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status, left_at
                        )
                        VALUES (
                            :tripId, :uid, :uid, :role, :status,
                            CASE WHEN :status = 'ACTIVE' THEN NULL ELSE CURRENT_TIMESTAMP END
                        )
                        RETURNING id
                        """)
                .param("tripId", tripId)
                .param("uid", uid)
                .param("role", role)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long memberCount(long tripId, String uid) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM trip_member
                        WHERE trip_id = :tripId
                          AND firebase_uid = :uid
                        """)
                .param("tripId", tripId)
                .param("uid", uid)
                .query(Long.class)
                .single();
    }

    private RequestPostProcessor principal(String uid) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new AppPrincipal(uid),
                null,
                java.util.List.of()));
    }

    private RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
