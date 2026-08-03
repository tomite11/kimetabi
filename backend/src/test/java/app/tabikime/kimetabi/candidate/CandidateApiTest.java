package app.tabikime.kimetabi.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import app.tabikime.kimetabi.identity.AppPrincipal;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CandidateApiTest {

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
        insertTrip(1, "owner-a");
        insertTrip(2, "owner-b");
        jdbcClient.sql("SELECT setval(pg_get_serial_sequence('slot', 'id'), 2, true)")
                .query(Long.class).single();
        jdbcClient.sql("SELECT setval(pg_get_serial_sequence('trip_member', 'id'), 2, true)")
                .query(Long.class).single();
    }

    @Test
    void activeMemberCreatesManualCandidateAndReadsSlotDetail() throws Exception {
        mockMvc.perform(post("/api/trips/1/slots/1/candidates")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "海辺のホテル",
                                  "note": "駅から徒歩5分",
                                  "tags": ["温泉", "駅近"],
                                  "estAmount": 18000,
                                  "estBasis": "PER_PERSON"
                                }
                                """)
                        .with(principal("owner-a")))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", "/api/trips/1/candidates/1"))
                .andExpect(jsonPath("$.slotId").value(1))
                .andExpect(jsonPath("$.createdByMemberId").value(1))
                .andExpect(jsonPath("$.tags[0]").value("温泉"))
                .andExpect(jsonPath("$.metadataStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(get("/api/trips/1/slots/1")
                        .with(principal("owner-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot.id").value(1))
                .andExpect(jsonPath("$.candidates[0].title").value("海辺のホテル"))
                .andExpect(jsonPath("$.votesByCandidate").isMap());
    }

    @Test
    void urlCandidateStartsPendingWithoutFetchingRemoteUrl() throws Exception {
        mockMvc.perform(post("/api/trips/1/slots/1/candidates")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/hotel"}
                                """)
                        .with(principal("owner-a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://example.com/hotel"))
                .andExpect(jsonPath("$.metadataStatus").value("PENDING"));
    }

    @Test
    void rejectsCrossTripSlotAndCandidateAccessWithoutLeakingResource() throws Exception {
        createCandidate();

        mockMvc.perform(post("/api/trips/1/slots/2/candidates")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"別旅行の枠\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/trips/2/candidates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"横取り\"}")
                        .with(principal("owner-b")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/trips/1/slots/1")
                        .with(principal("owner-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void validatesSourceEstimatePairUrlAndUniqueTags() throws Exception {
        candidateRequest("{}")
                .andExpect(status().isUnprocessableEntity());
        candidateRequest("{\"title\":\"宿\",\"estAmount\":1000}")
                .andExpect(status().isUnprocessableEntity());
        candidateRequest("{\"url\":\"file:///etc/passwd\"}")
                .andExpect(status().isUnprocessableEntity());
        candidateRequest("{\"title\":\"宿\",\"tags\":[\"温泉\",\"温泉\"]}")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updatesCandidateWithOptimisticVersionAndReturnsCurrentValueOnConflict()
            throws Exception {
        createCandidate();

        mockMvc.perform(patch("/api/trips/1/candidates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"title":"更新後","status":"REJECTED"}
                                """)
                        .with(principal("owner-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("更新後"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch("/api/trips/1/candidates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"古い更新\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.current.title").value("更新後"));
    }

    @Test
    void patchCanExplicitlyClearNullableCandidateAndSlotFields() throws Exception {
        candidateRequest("""
                {
                  "title":"候補","url":"https://example.com",
                  "note":"メモ","estAmount":1000,"estBasis":"TOTAL"
                }
                """).andExpect(status().isCreated());

        mockMvc.perform(patch("/api/trips/1/candidates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":0,"url":null,"note":null,
                                  "estAmount":null,"estBasis":null
                                }
                                """)
                        .with(principal("owner-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("候補"))
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.estAmount").doesNotExist())
                .andExpect(jsonPath("$.estBasis").doesNotExist());

        jdbcClient.sql("""
                        UPDATE slot SET deadline = DATE '2030-07-20', est_per_person = 5000
                        WHERE id = 1
                        """).update();
        mockMvc.perform(patch("/api/trips/1/slots/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"deadline\":null,\"estPerPerson\":null}")
                        .with(principal("owner-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadline").doesNotExist())
                .andExpect(jsonPath("$.estPerPerson").doesNotExist());
    }

    @Test
    void databaseRejectsCrossTripCreatorAndNoReasonVote() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO candidate (
                            trip_id, slot_id, created_by_member_id, title, metadata_status
                        ) VALUES (1, 1, 2, '不正候補', 'COMPLETED')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        createCandidateDirectly();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO candidate_vote (
                            candidate_id, trip_id, member_id, choice, reason
                        ) VALUES (1, 1, 1, 'NO', NULL)
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM plan_item")
                .query(Long.class).single()).isZero();
    }

    @Test
    void databaseRejectsPlanItemWhoseCandidateBelongsToAnotherSlot() {
        createCandidateDirectly();
        jdbcClient.sql("""
                        INSERT INTO slot (
                            id, trip_id, category, title, day_from, day_to,
                            units, sort_order, status
                        ) VALUES (10, 1, 'ACTIVITY', '観光', 2, 2, 1, 1, 'OPEN')
                        """).update();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO plan_item (
                            trip_id, slot_id, from_candidate_id, title
                        ) VALUES (1, 10, 1, '不整合な予定')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void organizerCreatesReordersAndDeletesSlotWhileMemberIsForbidden() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status
                        ) VALUES
                            (1, 'organizer', 'Organizer', 'ORGANIZER', 'ACTIVE'),
                            (1, 'member', 'Member', 'MEMBER', 'ACTIVE')
                        """).update();

        mockMvc.perform(post("/api/trips/1/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"ACTIVITY","title":"観光",
                                  "dayFrom":2,"dayTo":2,"units":1,"sortOrder":0
                                }
                                """)
                        .with(principal("organizer")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.deadline").value("2030-07-25"));

        assertThat(jdbcClient.sql("""
                        SELECT sort_order FROM slot WHERE id = 1
                        """).query(Integer.class).single()).isEqualTo(1);

        mockMvc.perform(patch("/api/trips/1/slots/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"sortOrder\":1,\"title\":\"市内観光\"}")
                        .with(principal("organizer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").value(1))
                .andExpect(jsonPath("$.title").value("市内観光"))
                .andExpect(jsonPath("$.autoGenerated").value(false))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(delete("/api/trips/1/slots/3")
                        .queryParam("version", "1")
                        .with(principal("organizer")))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/trips/1/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"MEAL","title":"夕食",
                                  "dayFrom":2,"dayTo":2,"units":1,"sortOrder":0
                                }
                                """)
                        .with(principal("member")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsSlotOutsideTripDaysAndStaleUpdate() throws Exception {
        mockMvc.perform(post("/api/trips/1/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category":"ACTIVITY","title":"旅行後",
                                  "dayFrom":4,"dayTo":4,"units":1,"sortOrder":1
                                }
                                """)
                        .with(principal("owner-a")))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(patch("/api/trips/1/slots/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":9,\"title\":\"古い更新\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions candidateRequest(String body)
            throws Exception {
        return mockMvc.perform(post("/api/trips/1/slots/1/candidates")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(principal("owner-a")));
    }

    private void createCandidate() throws Exception {
        candidateRequest("{\"title\":\"元の候補\"}")
                .andExpect(status().isCreated());
    }

    private void createCandidateDirectly() {
        jdbcClient.sql("""
                        INSERT INTO candidate (
                            trip_id, slot_id, created_by_member_id, title, metadata_status
                        ) VALUES (1, 1, 1, '候補', 'COMPLETED')
                        """).update();
    }

    private void insertTrip(long id, String uid) {
        jdbcClient.sql("""
                        INSERT INTO trip (
                            id, title, destination, starts_on, ends_on, timezone,
                            expected_member_count
                        ) VALUES (
                            :id, '旅行', '東京', DATE '2030-08-01', DATE '2030-08-03',
                            'Asia/Tokyo', 3
                        )
                        """).param("id", id).update();
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            id, trip_id, firebase_uid, name, role, status
                        ) VALUES (:id, :id, :uid, 'Owner', 'OWNER', 'ACTIVE')
                        """).param("id", id).param("uid", uid).update();
        jdbcClient.sql("UPDATE trip SET owner_member_id = :id WHERE id = :id")
                .param("id", id).update();
        jdbcClient.sql("""
                        INSERT INTO slot (
                            id, trip_id, category, title, day_from, day_to,
                            units, sort_order, status
                        ) VALUES (:id, :id, 'LODGING', '宿', 1, 2, 2, 0, 'OPEN')
                        """).param("id", id).update();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor principal(
            String firebaseUid
    ) {
        AppPrincipal principal = new AppPrincipal(firebaseUid);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, "token", java.util.List.of()));
    }
}
