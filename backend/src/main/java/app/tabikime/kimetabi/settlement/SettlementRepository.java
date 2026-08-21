package app.tabikime.kimetabi.settlement;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SettlementRepository {

    private final JdbcClient jdbcClient;

    SettlementRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    long lockTripRevision(long tripId) {
        return jdbcClient.sql("SELECT revision FROM trip WHERE id = :tripId AND deleted_at IS NULL FOR UPDATE")
                .param("tripId", tripId).query(Long.class).optional()
                .orElseThrow(app.tabikime.kimetabi.trip.TripNotFoundException::new);
    }

    List<SettlementExpenseSnapshot> confirmedExpenses(long tripId) {
        Map<Long, ExpenseBuilder> expenses = new LinkedHashMap<>();
        jdbcClient.sql("""
                SELECT e.id, e.version, e.payer_id, e.base_amount,
                       es.member_id, es.final_amount
                FROM expense e
                JOIN expense_share es ON es.expense_id = e.id AND es.trip_id = e.trip_id
                WHERE e.trip_id = :tripId AND e.status = 'CONFIRMED'
                ORDER BY e.id, es.member_id
                """).param("tripId", tripId).query((rs, row) -> {
            long expenseId = rs.getLong("id");
            long expenseVersion = rs.getLong("version");
            long payerId = rs.getLong("payer_id");
            long baseAmount = rs.getLong("base_amount");
            expenses.computeIfAbsent(expenseId, ignored -> new ExpenseBuilder(
                    expenseId, expenseVersion, payerId, baseAmount))
                    .shares.add(new SettlementShareSnapshot(
                            rs.getLong("member_id"), rs.getLong("final_amount")));
            return expenseId;
        }).list();
        return expenses.values().stream().map(ExpenseBuilder::build).toList();
    }

    List<SettlementSourceTransferSnapshot> paidTransfers(long tripId) {
        return paidTransfers(tripId, null);
    }

    List<SettlementSourceTransferSnapshot> paidTransfersExcluding(
            long tripId,
            long settlementId
    ) {
        return paidTransfers(tripId, settlementId);
    }

    private List<SettlementSourceTransferSnapshot> paidTransfers(
            long tripId,
            Long excludedSettlementId
    ) {
        return jdbcClient.sql("""
                SELECT id, version, from_member_id, to_member_id, amount, status
                FROM settlement_transfer
                WHERE trip_id = :tripId
                  AND status IN ('PAID', 'CONFIRMED')
                  AND (CAST(:excludedSettlementId AS BIGINT) IS NULL
                       OR settlement_id <> :excludedSettlementId)
                ORDER BY id
                """).param("tripId", tripId)
                .param("excludedSettlementId", excludedSettlementId)
                .query((rs, row) -> new SettlementSourceTransferSnapshot(
                        rs.getLong("id"), rs.getLong("version"),
                        rs.getLong("from_member_id"), rs.getLong("to_member_id"),
                        rs.getLong("amount"), TransferStatus.valueOf(rs.getString("status"))))
                .list();
    }

    long insertDraft(long tripId, long actorMemberId) {
        return jdbcClient.sql("""
                INSERT INTO settlement (trip_id, created_by_member_id)
                VALUES (:tripId, :actorMemberId) RETURNING id
                """).param("tripId", tripId).param("actorMemberId", actorMemberId)
                .query(Long.class).single();
    }

    void insertSnapshots(
            long settlementId,
            long tripId,
            List<SettlementExpenseSnapshot> expenses,
            List<SettlementSourceTransferSnapshot> sources,
            List<SettlementTransferDraft> transfers
    ) {
        for (SettlementExpenseSnapshot expense : expenses) {
            jdbcClient.sql("""
                    INSERT INTO settlement_expense
                        (settlement_id, trip_id, expense_id, expense_version,
                         payer_member_id, base_amount)
                    VALUES (:settlementId, :tripId, :expenseId, :expenseVersion,
                            :payerId, :baseAmount)
                    """).param("settlementId", settlementId).param("tripId", tripId)
                    .param("expenseId", expense.expenseId())
                    .param("expenseVersion", expense.expenseVersion())
                    .param("payerId", expense.payerMemberId())
                    .param("baseAmount", expense.baseAmount()).update();
            for (SettlementShareSnapshot share : expense.shares()) {
                jdbcClient.sql("""
                        INSERT INTO settlement_share
                            (settlement_id, trip_id, expense_id, member_id, final_amount)
                        VALUES (:settlementId, :tripId, :expenseId, :memberId, :finalAmount)
                        """).param("settlementId", settlementId).param("tripId", tripId)
                        .param("expenseId", expense.expenseId()).param("memberId", share.memberId())
                        .param("finalAmount", share.finalAmount()).update();
            }
        }
        for (SettlementSourceTransferSnapshot source : sources) {
            jdbcClient.sql("""
                    INSERT INTO settlement_source_transfer
                        (settlement_id, trip_id, source_transfer_id, source_transfer_version,
                         from_member_id, to_member_id, amount, status)
                    VALUES (:settlementId, :tripId, :sourceId, :sourceVersion,
                            :fromId, :toId, :amount, :status)
                    """).param("settlementId", settlementId).param("tripId", tripId)
                    .param("sourceId", source.transferId()).param("sourceVersion", source.transferVersion())
                    .param("fromId", source.fromMemberId()).param("toId", source.toMemberId())
                    .param("amount", source.amount()).param("status", source.status().name()).update();
        }
        for (SettlementTransferDraft transfer : transfers) {
            jdbcClient.sql("""
                    INSERT INTO settlement_transfer
                        (settlement_id, trip_id, from_member_id, to_member_id, amount)
                    VALUES (:settlementId, :tripId, :fromId, :toId, :amount)
                    """).param("settlementId", settlementId).param("tripId", tripId)
                    .param("fromId", transfer.fromMemberId()).param("toId", transfer.toMemberId())
                    .param("amount", transfer.amount()).update();
        }
    }

    Optional<StoredSettlement> find(long tripId, long settlementId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbcClient.sql("""
                SELECT id, status, calculated_at, version
                FROM settlement WHERE id = :id AND trip_id = :tripId
                """ + suffix).param("id", settlementId).param("tripId", tripId)
                .query((rs, row) -> new StoredSettlement(
                        rs.getLong("id"), SettlementStatus.valueOf(rs.getString("status")),
                        rs.getObject("calculated_at", OffsetDateTime.class), rs.getLong("version")))
                .optional();
    }

    List<StoredSettlement> list(long tripId, long beforeId, int limit) {
        return jdbcClient.sql("""
                SELECT id, status, calculated_at, version FROM settlement
                WHERE trip_id = :tripId AND id < :beforeId
                ORDER BY id DESC LIMIT :limit
                """).param("tripId", tripId).param("beforeId", beforeId).param("limit", limit)
                .query((rs, row) -> new StoredSettlement(
                        rs.getLong("id"), SettlementStatus.valueOf(rs.getString("status")),
                        rs.getObject("calculated_at", OffsetDateTime.class), rs.getLong("version")))
                .list();
    }

    List<SettlementExpenseVersionResource> expenseVersions(long settlementId) {
        return jdbcClient.sql("""
                SELECT expense_id, expense_version FROM settlement_expense
                WHERE settlement_id = :id ORDER BY expense_id
                """).param("id", settlementId).query((rs, row) ->
                        new SettlementExpenseVersionResource(
                                rs.getLong("expense_id"), rs.getLong("expense_version"))).list();
    }

    long expenseTotal(long settlementId) {
        return jdbcClient.sql("""
                SELECT COALESCE(SUM(base_amount), 0) FROM settlement_expense
                WHERE settlement_id = :id
                """).param("id", settlementId).query(Long.class).single();
    }

    List<SettlementTransferResource> transfers(long settlementId) {
        return jdbcClient.sql("""
                SELECT id, from_member_id, to_member_id, amount, status, version
                FROM settlement_transfer WHERE settlement_id = :id ORDER BY id
                """).param("id", settlementId).query((rs, row) -> new SettlementTransferResource(
                        rs.getLong("id"), rs.getLong("from_member_id"), rs.getLong("to_member_id"),
                        rs.getLong("amount"), TransferStatus.valueOf(rs.getString("status")),
                        rs.getLong("version"))).list();
    }

    Optional<SettlementTransferResource> findTransfer(
            long tripId, long settlementId, long transferId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbcClient.sql("""
                SELECT id, from_member_id, to_member_id, amount, status, version
                FROM settlement_transfer
                WHERE id = :id AND settlement_id = :settlementId AND trip_id = :tripId
                """ + suffix)
                .param("id", transferId).param("settlementId", settlementId)
                .param("tripId", tripId)
                .query((rs, row) -> new SettlementTransferResource(
                        rs.getLong("id"), rs.getLong("from_member_id"),
                        rs.getLong("to_member_id"), rs.getLong("amount"),
                        TransferStatus.valueOf(rs.getString("status")), rs.getLong("version")))
                .optional();
    }

    boolean updateTransferStatus(
            long tripId, long settlementId, long transferId, long version,
            TransferStatus expectedStatus, TransferStatus targetStatus) {
        return jdbcClient.sql("""
                UPDATE settlement_transfer
                SET status = :targetStatus,
                    paid_at = CASE WHEN :targetStatus IN ('PAID', 'CONFIRMED')
                        THEN COALESCE(paid_at, CURRENT_TIMESTAMP) ELSE paid_at END,
                    confirmed_at = CASE WHEN :targetStatus = 'CONFIRMED'
                        THEN CURRENT_TIMESTAMP ELSE NULL END,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND settlement_id = :settlementId AND trip_id = :tripId
                  AND version = :version AND status = :expectedStatus
                """)
                .param("targetStatus", targetStatus.name()).param("id", transferId)
                .param("settlementId", settlementId).param("tripId", tripId)
                .param("version", version).param("expectedStatus", expectedStatus.name())
                .update() == 1;
    }

    boolean completeIfAllTransfersConfirmed(long tripId, long settlementId) {
        return jdbcClient.sql("""
                UPDATE settlement
                SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND trip_id = :tripId AND status = 'CONFIRMED'
                  AND NOT EXISTS (
                      SELECT 1 FROM settlement_transfer
                      WHERE settlement_id = :id AND status <> 'CONFIRMED'
                  )
                """).param("id", settlementId).param("tripId", tripId).update() == 1;
    }

    boolean hasUnappliedChanges(
            long settlementId,
            List<SettlementExpenseSnapshot> currentExpenses,
            List<SettlementSourceTransferSnapshot> currentSources
    ) {
        Set<String> currentExpenseKeys = currentExpenses.stream()
                .map(value -> value.expenseId() + ":" + value.expenseVersion())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> storedExpenseKeys = Set.copyOf(jdbcClient.sql("""
                SELECT expense_id || ':' || expense_version AS value
                FROM settlement_expense WHERE settlement_id = :id
                """).param("id", settlementId).query(String.class).list());
        Set<String> currentSourceKeys = currentSources.stream().map(value ->
                value.transferId() + ":" + value.transferVersion() + ":" + value.status())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> storedSourceKeys = Set.copyOf(jdbcClient.sql("""
                SELECT source_transfer_id || ':' || source_transfer_version || ':' || status AS value
                FROM settlement_source_transfer WHERE settlement_id = :id
                """).param("id", settlementId).query(String.class).list());
        return !currentExpenseKeys.equals(storedExpenseKeys) || !currentSourceKeys.equals(storedSourceKeys);
    }

    boolean confirm(long tripId, long settlementId, long version, boolean complete) {
        String status = complete ? "COMPLETED" : "CONFIRMED";
        return jdbcClient.sql("""
                UPDATE settlement SET status = :status, confirmed_at = CURRENT_TIMESTAMP,
                    completed_at = CASE WHEN :complete THEN CURRENT_TIMESTAMP ELSE NULL END,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND trip_id = :tripId AND version = :version AND status = 'DRAFT'
                """).param("status", status).param("complete", complete)
                .param("id", settlementId).param("tripId", tripId).param("version", version)
                .update() == 1;
    }

    void supersedeOthers(long tripId, long settlementId) {
        jdbcClient.sql("""
                UPDATE settlement SET status = 'SUPERSEDED', superseded_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE trip_id = :tripId AND id <> :id AND status IN ('DRAFT', 'CONFIRMED')
                """).param("tripId", tripId).param("id", settlementId).update();
    }

    record StoredSettlement(long id, SettlementStatus status, OffsetDateTime calculatedAt, long version) {
    }

    private static final class ExpenseBuilder {
        private final long id;
        private final long version;
        private final long payerId;
        private final long amount;
        private final List<SettlementShareSnapshot> shares = new ArrayList<>();

        private ExpenseBuilder(long id, long version, long payerId, long amount) {
            this.id = id; this.version = version; this.payerId = payerId; this.amount = amount;
        }

        private SettlementExpenseSnapshot build() {
            return new SettlementExpenseSnapshot(id, version, payerId, amount, shares);
        }
    }
}
