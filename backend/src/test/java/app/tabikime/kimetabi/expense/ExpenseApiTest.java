package app.tabikime.kimetabi.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ExpenseApiTest {

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

    @BeforeEach
    void setUp() {
        jdbcClient.sql("""
                        TRUNCATE idempotency_request, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """).update();
        insertTrip(1, "owner-a", "OWNER");
        insertMember(2, 1, "member-a", "MEMBER");
        insertMember(3, 1, "organizer-a", "ORGANIZER");
        insertTrip(2, "owner-b", "OWNER");
        insertMember(5, 2, "member-b", "MEMBER");
        jdbcClient.sql("""
                        INSERT INTO slot (
                            id, trip_id, category, title, day_from, day_to,
                            units, sort_order, status
                        ) VALUES (1, 1, 'MEAL', '夕食', 1, 1, 1, 0, 'OPEN')
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO candidate (
                            id, trip_id, slot_id, created_by_member_id, title,
                            status, metadata_status
                        ) VALUES (1, 1, 1, 1, '夕食候補', 'OPEN', 'COMPLETED')
                        """).update();
        jdbcClient.sql("""
                        UPDATE slot SET adopted_candidate_id = 1, status = 'DECIDED'
                        WHERE id = 1
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO plan_item (
                            id, trip_id, slot_id, from_candidate_id, title
                        ) VALUES (1, 1, 1, 1, '夕食')
                        """).update();
    }

    @Test
    void createsPhotoOnlyDraftAndWritesRevisionAndOutboxAtomically() throws Exception {
        mockMvc.perform(post("/api/trips/1/expenses")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hasReceipt\":true}")
                        .with(principal("member-a")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/trips/1/expenses/1"))
                .andExpect(jsonPath("$.createdByMemberId").value(2))
                .andExpect(jsonPath("$.amount").doesNotExist())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.receipts").isArray())
                .andExpect(jsonPath("$.shares").isEmpty())
                .andExpect(jsonPath("$.version").value(0));

        assertThat(singleLong("SELECT revision FROM trip WHERE id = 1")).isEqualTo(1);
        assertThat(singleLong("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE trip_id = 1 AND action = 'EXPENSE_DRAFT_CREATED'
                        """)).isEqualTo(1);
        assertThat(singleLong("""
                        SELECT COUNT(*) FROM outbox_event
                        WHERE trip_id = 1 AND event_type = 'EXPENSE_DRAFT_CREATED'
                        """)).isEqualTo(1);
    }

    @Test
    void replaysExpenseDraftCreationAndRejectsKeyReuseWithDifferentPayload() throws Exception {
        UUID key = UUID.randomUUID();
        String request = "{\"amount\":1200,\"source\":\"MANUAL\"}";

        MvcResult first = mockMvc.perform(post("/api/trips/1/expenses")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(principal("member-a")))
                .andExpect(status().isCreated())
                .andReturn();
        mockMvc.perform(post("/api/trips/1/expenses")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(principal("member-a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(content().json(first.getResponse().getContentAsString()));

        assertThat(singleLong("SELECT COUNT(*) FROM expense")).isEqualTo(1);
        assertThat(singleLong("SELECT COUNT(*) FROM audit_event")).isEqualTo(1);
        assertThat(singleLong("SELECT COUNT(*) FROM outbox_event")).isEqualTo(1);
        assertThat(singleLong("SELECT revision FROM trip WHERE id = 1")).isEqualTo(1);

        mockMvc.perform(post("/api/trips/1/expenses")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1300,\"source\":\"MANUAL\"}")
                        .with(principal("member-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void exposesOfflineRetryDispositionThroughStableProblemResponses() throws Exception {
        mockMvc.perform(post("/api/trips/1/expenses")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/trips/1/expenses")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(principal("member-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("request"));

        createDraft("member-a", "{\"amount\":100}");
        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"amount\":101}")
                        .with(principal("member-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"amount\":102}")
                        .with(principal("member-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.current.amount").value(101));
    }

    @Test
    void confirmsDraftWithCompleteSharesAuditAndCurrentVersionConflict() throws Exception {
        createDraft("member-a", "{\"amount\":100,\"paidAt\":\"2030-08-01T12:00:00+09:00\"}");

        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":0,
                                  "payerId":2,
                                  "allocationType":"EQUAL",
                                  "shares":[{"memberId":3},{"memberId":1},{"memberId":2}],
                                  "status":"CONFIRMED"
                                }
                                """)
                        .with(principal("member-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.baseAmount").value(100))
                .andExpect(jsonPath("$.shares[0].memberId").value(1))
                .andExpect(jsonPath("$.shares[0].finalAmount").value(34))
                .andExpect(jsonPath("$.shares[1].finalAmount").value(33))
                .andExpect(jsonPath("$.shares[2].finalAmount").value(33))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(singleLong("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE action = 'EXPENSE_CONFIRMED'
                          AND before_state ->> 'status' = 'DRAFT'
                          AND after_state ->> 'status' = 'CONFIRMED'
                          AND trace_id <> ''
                        """)).isEqualTo(1);
        assertThat(singleLong("""
                        SELECT COUNT(*) FROM outbox_event
                        WHERE event_type = 'EXPENSE_CONFIRMED' AND resource_version = 1
                        """)).isEqualTo(1);

        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"amount\":101}")
                        .with(principal("organizer-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.current.status").value("CONFIRMED"));
    }

    @Test
    void rejectsIncompleteOrCrossTripConfirmationWithoutPartialWrites() throws Exception {
        createDraft("member-a", "{\"amount\":100}");

        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"status\":\"CONFIRMED\"}")
                        .with(principal("member-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("payerId"));

        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":0,
                                  "payerId":5,
                                  "paidAt":"2030-08-01T12:00:00+09:00",
                                  "allocationType":"EQUAL",
                                  "shares":[{"memberId":2}],
                                  "status":"CONFIRMED"
                                }
                                """)
                        .with(principal("member-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("payerId"));

        assertThat(jdbcClient.sql("SELECT status FROM expense WHERE id = 1")
                .query(String.class).single()).isEqualTo("DRAFT");
        assertThat(singleLong("SELECT COUNT(*) FROM expense_share WHERE expense_id = 1"))
                .isZero();
    }

    @Test
    void hidesCrossTripExpenseAndMemberCannotCorrectConfirmedExpense() throws Exception {
        createDraft("member-a", "{\"amount\":100,\"paidAt\":\"2030-08-01T12:00:00+09:00\"}");
        confirmAsMember();

        mockMvc.perform(get("/api/trips/2/expenses/1").with(principal("owner-b")))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"amount\":101}")
                        .with(principal("member-a")))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizerCorrectionRecalculatesSharesAndAppendsHistory() throws Exception {
        createDraft("member-a", "{\"amount\":100,\"paidAt\":\"2030-08-01T12:00:00+09:00\"}");
        confirmAsMember();

        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"amount\":101}")
                        .with(principal("organizer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(101))
                .andExpect(jsonPath("$.baseAmount").value(101))
                .andExpect(jsonPath("$.shares[0].finalAmount").value(51))
                .andExpect(jsonPath("$.shares[1].finalAmount").value(50))
                .andExpect(jsonPath("$.version").value(2));

        assertThat(singleLong("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE action = 'EXPENSE_CORRECTED'
                          AND before_state ->> 'amount' = '100'
                          AND after_state ->> 'amount' = '101'
                        """)).isEqualTo(1);
    }

    @Test
    void onlyCreatorOrOrganizerCanDeleteDraftAndConfirmedExpenseCannotBeDeleted() throws Exception {
        createDraft("member-a", "{\"amount\":100,\"paidAt\":\"2030-08-01T12:00:00+09:00\"}");

        mockMvc.perform(delete("/api/trips/1/expenses/1?version=0")
                        .with(principal("owner-a")))
                .andExpect(status().isNoContent());
        assertThat(singleLong("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE action = 'EXPENSE_DRAFT_DELETED'
                        """)).isEqualTo(1);
        assertThat(singleLong("""
                        SELECT COUNT(*) FROM outbox_event
                        WHERE event_type = 'EXPENSE_DRAFT_DELETED'
                        """)).isEqualTo(1);

        createDraft("member-a", "{\"amount\":100,\"paidAt\":\"2030-08-01T12:00:00+09:00\"}");
        mockMvc.perform(delete("/api/trips/1/expenses/2?version=0")
                        .with(principal("owner-b")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/trips/1/expenses/2?version=0")
                        .with(principal("member-a")))
                .andExpect(status().isNoContent());

        createDraft("member-a", "{\"amount\":100,\"paidAt\":\"2030-08-01T12:00:00+09:00\"}");
        mockMvc.perform(patch("/api/trips/1/expenses/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":0,
                                  "payerId":2,
                                  "allocationType":"EQUAL",
                                  "shares":[{"memberId":1},{"memberId":2}],
                                  "status":"CONFIRMED"
                                }
                                """)
                        .with(principal("member-a")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/trips/1/expenses/3?version=1")
                        .with(principal("organizer-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    void listsExpensesWithOpaqueCursorStatusFilterAndTripAuthorization() throws Exception {
        createDraft("member-a", "{\"amount\":100}");
        createDraft("member-a", "{\"amount\":200,\"paidAt\":\"2030-08-01T12:00:00+09:00\"}");
        mockMvc.perform(patch("/api/trips/1/expenses/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":0,
                                  "payerId":2,
                                  "allocationType":"EQUAL",
                                  "shares":[{"memberId":1},{"memberId":2}],
                                  "status":"CONFIRMED"
                                }
                                """)
                        .with(principal("member-a")))
                .andExpect(status().isOk());
        createDraft("member-a", "{\"amount\":300}");

        MvcResult firstPage = mockMvc.perform(get("/api/trips/1/expenses?limit=2")
                        .with(principal("member-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(3))
                .andExpect(jsonPath("$.items[1].id").value(2))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String cursor = com.jayway.jsonpath.JsonPath.read(
                firstPage.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(get("/api/trips/1/expenses")
                        .queryParam("cursor", cursor)
                        .queryParam("limit", "2")
                        .with(principal("member-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mockMvc.perform(get("/api/trips/1/expenses?status=CONFIRMED")
                        .with(principal("member-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(2));

        mockMvc.perform(get("/api/trips/1/expenses?cursor=not-a-cursor")
                        .with(principal("member-a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/trips/1/expenses").with(principal("owner-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsSharesFromLatestConfirmedExpenseAsPreviousPreset() throws Exception {
        mockMvc.perform(get("/api/trips/1/expenses/share-preset")
                        .with(principal("member-a")))
                .andExpect(status().isNoContent());

        createDraft("member-a", "{\"amount\":100,\"paidAt\":\"2030-08-01T12:00:00+09:00\"}");
        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":0,
                                  "payerId":2,
                                  "allocationType":"WEIGHT",
                                  "shares":[
                                    {"memberId":1,"weight":2},
                                    {"memberId":2,"weight":1}
                                  ],
                                  "status":"CONFIRMED"
                                }
                                """)
                        .with(principal("member-a")))
                .andExpect(status().isOk());
        createDraft("member-a", "{\"amount\":999}");

        mockMvc.perform(get("/api/trips/1/expenses/share-preset")
                        .with(principal("member-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceExpenseId").value(1))
                .andExpect(jsonPath("$.allocationType").value("WEIGHT"))
                .andExpect(jsonPath("$.shares.length()").value(2))
                .andExpect(jsonPath("$.shares[0].memberId").value(1))
                .andExpect(jsonPath("$.shares[0].weight").value(2))
                .andExpect(jsonPath("$.shares[0].fixedAmount").doesNotExist())
                .andExpect(jsonPath("$.shares[1].memberId").value(2));

        mockMvc.perform(get("/api/trips/1/expenses/share-preset")
                        .with(principal("owner-b")))
                .andExpect(status().isNotFound());
    }

    private void confirmAsMember() throws Exception {
        mockMvc.perform(patch("/api/trips/1/expenses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":0,
                                  "payerId":2,
                                  "allocationType":"EQUAL",
                                  "shares":[{"memberId":1},{"memberId":2}],
                                  "status":"CONFIRMED"
                                }
                                """)
                        .with(principal("member-a")))
                .andExpect(status().isOk());
    }

    private void createDraft(String uid, String body) throws Exception {
        mockMvc.perform(post("/api/trips/1/expenses")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(principal(uid)))
                .andExpect(status().isCreated());
    }

    private void insertTrip(long tripId, String ownerUid, String ownerRole) {
        jdbcClient.sql("""
                        INSERT INTO trip (
                            id, title, destination, starts_on, ends_on,
                            timezone, expected_member_count
                        ) VALUES (
                            :tripId, '旅行', '東京', DATE '2030-08-01',
                            DATE '2030-08-03', 'Asia/Tokyo', 3
                        )
                        """).param("tripId", tripId).update();
        insertMember(tripId == 1 ? 1 : 4, tripId, ownerUid, ownerRole);
        jdbcClient.sql("UPDATE trip SET owner_member_id = :ownerId WHERE id = :tripId")
                .param("ownerId", tripId == 1 ? 1 : 4)
                .param("tripId", tripId)
                .update();
    }

    private void insertMember(long memberId, long tripId, String uid, String role) {
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            id, trip_id, firebase_uid, name, role, status
                        ) VALUES (:id, :tripId, :uid, :uid, :role, 'ACTIVE')
                        """)
                .param("id", memberId)
                .param("tripId", tripId)
                .param("uid", uid)
                .param("role", role)
                .update();
    }

    private long singleLong(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor principal(String uid) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new AppPrincipal(uid), "token", List.of()));
    }
}
