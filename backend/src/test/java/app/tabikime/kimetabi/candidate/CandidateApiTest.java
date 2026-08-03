package app.tabikime.kimetabi.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

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

        assertThat(jdbcClient.sql("SELECT revision FROM trip WHERE id = 1")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM outbox_event")
                .query(Long.class).single()).isEqualTo(2);
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

    @Test
    void organizerReordersEverySlotAtomicallyAndRejectsMember() throws Exception {
        addPlanningMembers();
        jdbcClient.sql("""
                        INSERT INTO slot (
                            id, trip_id, category, title, day_from, day_to,
                            units, sort_order, status
                        ) VALUES (10, 1, 'MEAL', '夕食', 1, 1, 1, 1, 'OPEN')
                        """).update();
        String body = """
                {"tripVersion":0,"items":[
                  {"slotId":10,"version":0,"sortOrder":0},
                  {"slotId":1,"version":0,"sortOrder":1}
                ]}
                """;

        mockMvc.perform(put("/api/trips/1/slots/order")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(principal("member")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/trips/1/slots/order")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(principal("organizer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[1].id").value(1));
    }

    @Test
    void organizerSplitsEmptyMultiDaySlotAndStaleVersionConflicts() throws Exception {
        addPlanningMembers();
        jdbcClient.sql("UPDATE slot SET est_per_person = 20001 WHERE trip_id = 1 AND id = 1")
                .update();
        mockMvc.perform(post("/api/trips/1/slots/1/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":9,\"splitAfterDay\":1}")
                        .with(principal("organizer")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/trips/1/slots/1/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"splitAfterDay\":1,\"secondTitle\":\"2日目の宿\"}")
                        .with(principal("organizer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayTo").value(1))
                .andExpect(jsonPath("$[0].estPerPerson").value(10000))
                .andExpect(jsonPath("$[1].dayFrom").value(2))
                .andExpect(jsonPath("$[1].estPerPerson").value(10001))
                .andExpect(jsonPath("$[1].title").value("2日目の宿"));
    }

    @Test
    void activeMemberVotesAndStaleVoteReturnsCurrentValue() throws Exception {
        createCandidateDirectly();

        mockMvc.perform(put("/api/trips/1/candidates/1/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"YES\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("NAMED"))
                .andExpect(jsonPath("$.yesCount").value(1))
                .andExpect(jsonPath("$.myVote.choice").value("YES"))
                .andExpect(jsonPath("$.myVote.version").value(0))
                .andExpect(jsonPath("$.namedVotes[0].memberId").value(1));

        mockMvc.perform(put("/api/trips/1/candidates/1/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"NO\",\"reason\":\"予算超過\",\"version\":0}")
                        .with(principal("owner-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noCount").value(1))
                .andExpect(jsonPath("$.myVote.reason").value("予算超過"))
                .andExpect(jsonPath("$.myVote.version").value(1));

        mockMvc.perform(put("/api/trips/1/candidates/1/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"ANY\",\"version\":0}")
                        .with(principal("owner-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.current.choice").value("NO"));
    }

    @Test
    void noVoteRequiresReasonAndAnonymousVoteHidesVoterFromEveryone() throws Exception {
        createCandidateDirectly();
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status
                        ) VALUES (1, 'member', 'Member', 'MEMBER', 'ACTIVE')
                        """).update();

        mockMvc.perform(put("/api/trips/1/candidates/1/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"NO\",\"reason\":\"   \"}")
                        .with(principal("owner-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));

        jdbcClient.sql("UPDATE trip SET vote_visibility = 'ANONYMOUS' WHERE id = 1").update();
        mockMvc.perform(put("/api/trips/1/candidates/1/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"YES\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("ANONYMOUS"))
                .andExpect(jsonPath("$.myVote.memberId").value(1))
                .andExpect(jsonPath("$.unvotedMemberIds[0]").value(3))
                .andExpect(jsonPath("$.namedVotes").doesNotExist());

        mockMvc.perform(get("/api/trips/1/slots/1")
                        .with(principal("member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.votesByCandidate.1.yesCount").value(1))
                .andExpect(jsonPath("$.votesByCandidate.1.unvotedMemberIds[0]").value(3))
                .andExpect(jsonPath("$.votesByCandidate.1.myVote").doesNotExist())
                .andExpect(jsonPath("$.votesByCandidate.1.namedVotes").doesNotExist());
    }

    @Test
    void concurrentFirstVotesHaveOneWinnerAndReturnConflictToTheOther() throws Exception {
        createCandidateDirectly();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("YES", "ANY").stream()
                    .map(choice -> executor.submit(() -> {
                        start.await();
                        return mockMvc.perform(put("/api/trips/1/candidates/1/vote")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"choice\":\"" + choice + "\"}")
                                        .with(principal("owner-a")))
                                .andReturn().getResponse().getStatus();
                    }))
                    .toList();
            start.countDown();
            assertThat(futures).extracting(future -> future.get())
                    .containsExactlyInAnyOrder(200, 409);
        }

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM candidate_vote
                        WHERE candidate_id = 1 AND member_id = 1
                        """).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void organizerAdoptsChangesAndClearsCandidateAtomicallyWhileMemberIsForbidden()
            throws Exception {
        addPlanningMembers();
        createCandidateDirectly();
        jdbcClient.sql("""
                        INSERT INTO candidate (
                            trip_id, slot_id, created_by_member_id, title, url, metadata_status
                        ) VALUES (1, 1, 1, '新しい候補', 'https://example.com/new', 'COMPLETED')
                        """).update();

        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":1,\"version\":0}")
                        .with(principal("member")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":1,\"version\":0}")
                        .with(principal("organizer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot.status").value("DECIDED"))
                .andExpect(jsonPath("$.slot.adoptedCandidateId").value(1))
                .andExpect(jsonPath("$.slot.version").value(1))
                .andExpect(jsonPath("$.planItem.fromCandidateId").value(1))
                .andExpect(jsonPath("$.planItem.version").value(0));

        long planItemId = jdbcClient.sql(
                        "SELECT id FROM plan_item WHERE trip_id = 1 AND slot_id = 1")
                .query(Long.class).single();
        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":2,\"version\":1}")
                        .with(principal("organizer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slot.adoptedCandidateId").value(2))
                .andExpect(jsonPath("$.planItem.id").value(planItemId))
                .andExpect(jsonPath("$.planItem.fromCandidateId").value(2))
                .andExpect(jsonPath("$.planItem.placeRef").value("https://example.com/new"))
                .andExpect(jsonPath("$.planItem.version").value(1));

        mockMvc.perform(delete("/api/trips/1/slots/1/adoption")
                        .queryParam("version", "2")
                        .with(principal("organizer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.adoptedCandidateId").doesNotExist())
                .andExpect(jsonPath("$.version").value(3));

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM plan_item WHERE slot_id = 1")
                .query(Long.class).single()).isZero();
    }

    @Test
    void adoptionRejectsCrossSlotRejectedAndStaleCandidates() throws Exception {
        createCandidateDirectly();
        jdbcClient.sql("""
                        INSERT INTO slot (
                            id, trip_id, category, title, day_from, day_to,
                            units, sort_order, status
                        ) VALUES (10, 1, 'ACTIVITY', '観光', 2, 2, 1, 1, 'OPEN')
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO candidate (
                            trip_id, slot_id, created_by_member_id, title, metadata_status
                        ) VALUES (1, 10, 1, '別枠候補', 'COMPLETED')
                        """).update();

        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":2,\"version\":0}")
                        .with(principal("owner-a")))
                .andExpect(status().isUnprocessableEntity());

        jdbcClient.sql("UPDATE candidate SET status = 'REJECTED' WHERE id = 1").update();
        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":1,\"version\":0}")
                        .with(principal("owner-a")))
                .andExpect(status().isUnprocessableEntity());

        jdbcClient.sql("UPDATE candidate SET status = 'OPEN' WHERE id = 1").update();
        jdbcClient.sql("UPDATE slot SET version = 1 WHERE id = 1").update();
        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":1,\"version\":0}")
                        .with(principal("owner-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
    }

    @Test
    void voteAndAdoptionHideCandidatesFromAnotherTrip() throws Exception {
        jdbcClient.sql("""
                        INSERT INTO candidate (
                            id, trip_id, slot_id, created_by_member_id, title, metadata_status
                        ) VALUES (20, 2, 2, 2, '別旅行候補', 'COMPLETED')
                        """).update();

        mockMvc.perform(put("/api/trips/1/candidates/20/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"YES\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":20,\"version\":0}")
                        .with(principal("owner-a")))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentAdoptionsWithSameVersionHaveOneWinner() throws Exception {
        createCandidateDirectly();
        jdbcClient.sql("""
                        INSERT INTO candidate (
                            trip_id, slot_id, created_by_member_id, title, metadata_status
                        ) VALUES (1, 1, 1, '候補2', 'COMPLETED')
                        """).update();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(1L, 2L).stream()
                    .map(candidateId -> executor.submit(() -> {
                        start.await();
                        return mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"candidateId\":" + candidateId
                                                + ",\"version\":0}")
                                        .with(principal("owner-a")))
                                .andReturn().getResponse().getStatus();
                    }))
                    .toList();
            start.countDown();
            assertThat(futures).extracting(future -> future.get())
                    .containsExactlyInAnyOrder(200, 409);
        }

        Long adoptedCandidateId = jdbcClient.sql(
                        "SELECT adopted_candidate_id FROM slot WHERE id = 1")
                .query(Long.class).single();
        assertThat(jdbcClient.sql("SELECT from_candidate_id FROM plan_item WHERE slot_id = 1")
                .query(Long.class).single()).isEqualTo(adoptedCandidateId);
    }

    @Test
    void adoptedCandidateCannotBeRejected() throws Exception {
        createCandidateDirectly();
        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":1,\"version\":0}")
                        .with(principal("owner-a")))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/trips/1/candidates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"status\":\"REJECTED\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void candidateCreationIsIdempotentAndRejectsKeyReuseWithDifferentRequest()
            throws Exception {
        UUID key = UUID.randomUUID();
        var request = post("/api/trips/1/slots/1/candidates")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"同じ候補\"}")
                .with(principal("owner-a"));

        mockMvc.perform(request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
        mockMvc.perform(post("/api/trips/1/slots/1/candidates")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"同じ候補\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM candidate")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT revision FROM trip WHERE id = 1")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM outbox_event")
                .query(Long.class).single()).isEqualTo(1);

        mockMvc.perform(post("/api/trips/1/slots/1/candidates")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"別の候補\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void candidateMutationsIncrementRevisionAndWriteContractEvents() throws Exception {
        createCandidate();
        mockMvc.perform(patch("/api/trips/1/candidates/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"title\":\"更新\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/trips/1/candidates/1/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"YES\"}")
                        .with(principal("owner-a")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":1,\"version\":0}")
                        .with(principal("owner-a")))
                .andExpect(status().isOk());

        assertThat(jdbcClient.sql("SELECT revision FROM trip WHERE id = 1")
                .query(Long.class).single()).isEqualTo(4);
        assertThat(jdbcClient.sql("""
                        SELECT event_type FROM outbox_event
                        WHERE trip_id = 1 ORDER BY trip_revision
                        """).query(String.class).list()).containsExactly(
                                "CANDIDATE_CREATED",
                                "CANDIDATE_UPDATED",
                                "CANDIDATE_VOTE_CHANGED",
                                "SLOT_ADOPTION_CHANGED");
        assertThat(jdbcClient.sql("""
                        SELECT payload->>'type' FROM outbox_event
                        WHERE trip_id = 1 ORDER BY trip_revision
                        """).query(String.class).list()).containsExactly(
                                "CANDIDATE_CREATED",
                                "CANDIDATE_UPDATED",
                                "CANDIDATE_VOTE_CHANGED",
                                "SLOT_ADOPTION_CHANGED");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM outbox_event
                        WHERE jsonb_exists(payload, 'eventId')
                          AND jsonb_exists(payload, 'tripId')
                          AND jsonb_exists(payload, 'tripRevision')
                          AND jsonb_exists(payload, 'occurredAt')
                        """).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void inactiveAndNonMembersCannotReadOrMutateCandidateResources() throws Exception {
        createCandidateDirectly();
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status, left_at
                        ) VALUES (1, 'left-member', 'Left', 'MEMBER', 'LEFT', CURRENT_TIMESTAMP)
                        """).update();

        for (String uid : List.of("left-member", "outsider")) {
            mockMvc.perform(get("/api/trips/1/slots/1").with(principal(uid)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(put("/api/trips/1/candidates/1/vote")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"choice\":\"YES\"}")
                            .with(principal(uid)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(patch("/api/trips/1/candidates/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0,\"title\":\"不正更新\"}")
                    .with(principal(uid)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/trips/1/slots/1/candidates")
                            .header("Idempotency-Key", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"不正作成\"}")
                            .with(principal(uid)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(put("/api/trips/1/slots/1/adoption")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":1,\"version\":0}")
                            .with(principal(uid)))
                    .andExpect(status().isNotFound());
        }
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

    private void addPlanningMembers() {
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            trip_id, firebase_uid, name, role, status
                        ) VALUES
                            (1, 'organizer', 'Organizer', 'ORGANIZER', 'ACTIVE'),
                            (1, 'member', 'Member', 'MEMBER', 'ACTIVE')
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
