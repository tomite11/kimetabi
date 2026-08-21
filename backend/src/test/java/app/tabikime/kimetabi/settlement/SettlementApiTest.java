package app.tabikime.kimetabi.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class SettlementApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
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
        jdbcClient.sql("TRUNCATE trip RESTART IDENTITY CASCADE").update();
        jdbcClient.sql("""
                INSERT INTO trip
                    (id, title, destination, starts_on, ends_on, timezone, expected_member_count)
                VALUES (1, '精算旅行', '東京', DATE '2026-09-01', DATE '2026-09-02',
                        'Asia/Tokyo', 3),
                       (2, '別旅行', '大阪', DATE '2026-09-01', DATE '2026-09-02',
                        'Asia/Tokyo', 1)
                """).update();
        jdbcClient.sql("""
                INSERT INTO trip_member (id, trip_id, firebase_uid, name, role, status)
                VALUES (1, 1, 'owner', 'A', 'OWNER', 'ACTIVE'),
                       (2, 1, 'member-b', 'B', 'MEMBER', 'ACTIVE'),
                       (3, 1, 'member-c', 'C', 'MEMBER', 'ACTIVE'),
                       (5, 1, 'organizer', 'E', 'ORGANIZER', 'ACTIVE'),
                       (4, 2, 'other-owner', 'D', 'OWNER', 'ACTIVE')
                """).update();
        jdbcClient.sql("UPDATE trip SET owner_member_id = CASE id WHEN 1 THEN 1 ELSE 4 END")
                .update();
        insertExpense(10, 1, 0, 900, 1, 1, 300, 2, 300, 3, 300);
    }

    @Test
    void createsListsGetsAndConfirmsImmutableDraft() throws Exception {
        long settlementId = createDraft("owner", UUID.randomUUID());

        mockMvc.perform(get("/api/trips/1/settlements/{id}", settlementId)
                        .with(actor("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.expenseTotal").value(900))
                .andExpect(jsonPath("$.expenseVersions[0].expenseId").value(10))
                .andExpect(jsonPath("$.transfers.length()").value(2))
                .andExpect(jsonPath("$.hasUnappliedChanges").value(false));
        mockMvc.perform(get("/api/trips/1/settlements?limit=1").with(actor("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(settlementId));
        mockMvc.perform(post("/api/trips/1/settlements/{id}/confirmation", settlementId)
                        .with(actor("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_event
                WHERE event_type = 'SETTLEMENT_CONFIRMED'
                """).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void detectsLaterExpenseAndRecalculatesWithoutChangingConfirmedSettlement() throws Exception {
        long first = createDraft("owner", UUID.randomUUID());
        confirm(first, 0);
        insertExpense(11, 1, 0, 300, 2, 1, 100, 2, 100, 3, 100);

        mockMvc.perform(get("/api/trips/1/settlements/{id}", first).with(actor("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.hasUnappliedChanges").value(true));

        long second = createDraft("owner", UUID.randomUUID());
        confirm(second, 0);
        assertThat(jdbcClient.sql("SELECT status FROM settlement WHERE id = :id")
                .param("id", first).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM settlement_expense WHERE settlement_id = :id")
                .param("id", second).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void includesPaidTransferSnapshotInRecalculation() throws Exception {
        long first = createDraft("owner", UUID.randomUUID());
        confirm(first, 0);
        long transferId = jdbcClient.sql("""
                SELECT id FROM settlement_transfer WHERE settlement_id = :id ORDER BY id LIMIT 1
                """).param("id", first).query(Long.class).single();
        jdbcClient.sql("""
                UPDATE settlement_transfer SET status = 'PAID', paid_at = CURRENT_TIMESTAMP,
                    version = 1 WHERE id = :id
                """).param("id", transferId).update();

        mockMvc.perform(get("/api/trips/1/settlements/{id}", first).with(actor("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasUnappliedChanges").value(false));

        long second = createDraft("owner", UUID.randomUUID());
        assertThat(jdbcClient.sql("""
                SELECT source_transfer_version FROM settlement_source_transfer
                WHERE settlement_id = :settlementId AND source_transfer_id = :transferId
                """).param("settlementId", second).param("transferId", transferId)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void rejectsMemberCreationCrossTripLookupStaleVersionAndChangedDraft() throws Exception {
        mockMvc.perform(post("/api/trips/1/settlements").with(actor("member-b"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        long id = createDraft("owner", UUID.randomUUID());
        mockMvc.perform(get("/api/trips/2/settlements/{id}", id).with(actor("other-owner")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/trips/1/settlements/{id}/confirmation", id)
                        .with(actor("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(0));
        insertExpense(11, 1, 0, 30, 1, 1, 10, 2, 10, 3, 10);
        mockMvc.perform(post("/api/trips/1/settlements/{id}/confirmation", id)
                        .with(actor("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void replaysCreationWithSameIdempotencyKey() throws Exception {
        UUID key = UUID.randomUUID();
        long first = createDraft("owner", key);
        long replay = createDraft("owner", key);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM settlement")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void completesImmediatelyWhenNoTransferIsRequired() throws Exception {
        jdbcClient.sql("TRUNCATE expense CASCADE").update();
        insertExpense(20, 1, 0, 100, 1, 1, 100, 2, 0, 3, 0);
        long id = createDraft("owner", UUID.randomUUID());

        mockMvc.perform(post("/api/trips/1/settlements/{id}/confirmation", id)
                        .with(actor("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void sourceMarksPaidAndDestinationConfirmsThenSettlementCompletes() throws Exception {
        long settlementId = createDraft("owner", UUID.randomUUID());
        confirm(settlementId, 0);
        var transfers = transferIds(settlementId);

        for (TransferRow transfer : transfers) {
            updateTransfer(transfer.fromUid(), settlementId, transfer.id(), "PAID", 0)
                    .andExpect(status().isOk());
            updateTransfer(transfer.toUid(), settlementId, transfer.id(), "CONFIRMED", 1)
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/trips/1/settlements/{id}", settlementId).with(actor("owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM outbox_event
                WHERE trip_id = 1 AND event_type = 'SETTLEMENT_TRANSFER_UPDATED'
                """).query(Integer.class).single()).isEqualTo(transfers.size() * 2);
    }

    @Test
    void administratorMayActAsProxyAndEveryProxyOperationIsAudited() throws Exception {
        long settlementId = createDraft("owner", UUID.randomUUID());
        confirm(settlementId, 0);
        TransferRow transfer = transferIds(settlementId).getFirst();

        updateTransfer("organizer", settlementId, transfer.id(), "PAID", 0)
                .andExpect(status().isOk());
        updateTransfer("organizer", settlementId, transfer.id(), "CONFIRMED", 1)
                .andExpect(status().isOk());

        assertThat(jdbcClient.sql("""
                SELECT COUNT(*) FROM audit_event
                WHERE resource_type = 'settlementTransfer' AND resource_id = :id
                  AND action = 'SETTLEMENT_TRANSFER_PROXY_UPDATED'
                """).param("id", transfer.id()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void organizerMayCreateAndConfirmSettlement() throws Exception {
        long settlementId = createDraft("organizer", UUID.randomUUID());

        mockMvc.perform(post("/api/trips/1/settlements/{id}/confirmation", settlementId)
                        .with(actor("organizer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void rejectsUnrelatedMemberWrongOrderStaleVersionAndCrossTripTransfer() throws Exception {
        long settlementId = createDraft("owner", UUID.randomUUID());
        confirm(settlementId, 0);
        TransferRow transfer = transferIds(settlementId).getFirst();
        String unrelated = transfer.fromUid().equals("member-b") ? "member-c" : "member-b";

        updateTransfer(unrelated, settlementId, transfer.id(), "PAID", 0)
                .andExpect(status().isForbidden());
        updateTransfer(transfer.toUid(), settlementId, transfer.id(), "CONFIRMED", 0)
                .andExpect(status().isConflict());
        updateTransfer(transfer.fromUid(), settlementId, transfer.id(), "PAID", 0)
                .andExpect(status().isOk());
        updateTransfer(transfer.fromUid(), settlementId, transfer.id(), "PAID", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        mockMvc.perform(patch("/api/trips/2/settlements/{id}/transfers/{transferId}",
                        settlementId, transfer.id()).with(actor("other-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\",\"version\":0}"))
                .andExpect(status().isNotFound());
    }

    private long createDraft(String uid, UUID key) throws Exception {
        String location = mockMvc.perform(post("/api/trips/1/settlements")
                        .with(actor(uid)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void confirm(long settlementId, long version) throws Exception {
        mockMvc.perform(post("/api/trips/1/settlements/{id}/confirmation", settlementId)
                        .with(actor("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions updateTransfer(
            String uid, long settlementId, long transferId, String status, long version)
            throws Exception {
        return mockMvc.perform(patch(
                        "/api/trips/1/settlements/{id}/transfers/{transferId}",
                        settlementId, transferId).with(actor(uid))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\",\"version\":" + version + "}"));
    }

    private java.util.List<TransferRow> transferIds(long settlementId) {
        return jdbcClient.sql("""
                SELECT st.id, source.firebase_uid AS source_uid, destination.firebase_uid AS destination_uid
                FROM settlement_transfer st
                JOIN trip_member source ON source.id = st.from_member_id AND source.trip_id = st.trip_id
                JOIN trip_member destination ON destination.id = st.to_member_id AND destination.trip_id = st.trip_id
                WHERE st.settlement_id = :id ORDER BY st.id
                """).param("id", settlementId).query((rs, row) -> new TransferRow(
                        rs.getLong("id"), rs.getString("source_uid"),
                        rs.getString("destination_uid"))).list();
    }

    private record TransferRow(long id, String fromUid, String toUid) {
    }

    private void insertExpense(
            long id, long tripId, long version, long amount, long payer,
            long member1, long burden1, long member2, long burden2, long member3, long burden3
    ) {
        jdbcClient.sql("""
                INSERT INTO expense
                    (id, trip_id, created_by_member_id, payer_id, amount, currency, base_amount,
                     paid_at, source, status, allocation_type, version, confirmed_at)
                VALUES (:id, :tripId, :payer, :payer, :amount, 'JPY', :amount,
                        CURRENT_TIMESTAMP, 'MANUAL', 'CONFIRMED', 'EQUAL', :version,
                        CURRENT_TIMESTAMP)
                """).param("id", id).param("tripId", tripId).param("payer", payer)
                .param("amount", amount).param("version", version).update();
        insertShare(id, tripId, member1, burden1);
        insertShare(id, tripId, member2, burden2);
        insertShare(id, tripId, member3, burden3);
    }

    private void insertShare(long expenseId, long tripId, long memberId, long amount) {
        jdbcClient.sql("""
                INSERT INTO expense_share
                    (expense_id, trip_id, member_id, weight, final_amount)
                VALUES (:expenseId, :tripId, :memberId, 1, :amount)
                """).param("expenseId", expenseId).param("tripId", tripId)
                .param("memberId", memberId).param("amount", amount).update();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor actor(String uid) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new AppPrincipal(uid), "token", java.util.List.of()));
    }
}
