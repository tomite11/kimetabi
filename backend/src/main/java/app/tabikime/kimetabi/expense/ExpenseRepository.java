package app.tabikime.kimetabi.expense;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ExpenseRepository {

    private final JdbcClient jdbcClient;

    ExpenseRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    long insert(
            long tripId,
            long createdByMemberId,
            CreateExpenseDraftRequest request,
            ExpenseSource source
    ) {
        return jdbcClient.sql("""
                        INSERT INTO expense (
                            trip_id, plan_item_id, created_by_member_id,
                            amount, paid_at, source, status
                        ) VALUES (
                            :tripId, :planItemId, :createdByMemberId,
                            :amount, :paidAt, :source, 'DRAFT'
                        )
                        RETURNING id
                        """)
                .param("tripId", tripId)
                .param("planItemId", request.planItemId())
                .param("createdByMemberId", createdByMemberId)
                .param("amount", request.amount())
                .param("paidAt", request.paidAt())
                .param("source", source.name())
                .query(Long.class)
                .single();
    }

    Optional<ExpenseResource> find(long tripId, long expenseId) {
        return find(tripId, expenseId, false);
    }

    Optional<ExpenseResource> lock(long tripId, long expenseId) {
        return find(tripId, expenseId, true);
    }

    private Optional<ExpenseResource> find(long tripId, long expenseId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbcClient.sql("""
                        SELECT id, plan_item_id, created_by_member_id, payer_id,
                               amount, currency, base_amount, paid_at, source,
                               status, allocation_type, version
                        FROM expense
                        WHERE trip_id = :tripId AND id = :expenseId
                        """ + suffix)
                .param("tripId", tripId)
                .param("expenseId", expenseId)
                .query((resultSet, rowNumber) -> new ExpenseResource(
                        resultSet.getLong("id"),
                        resultSet.getObject("plan_item_id", Long.class),
                        resultSet.getLong("created_by_member_id"),
                        resultSet.getObject("payer_id", Long.class),
                        resultSet.getObject("amount", Long.class),
                        resultSet.getString("currency"),
                        resultSet.getObject("base_amount", Long.class),
                        resultSet.getObject("paid_at", OffsetDateTime.class),
                        ExpenseSource.valueOf(resultSet.getString("source")),
                        ExpenseStatus.valueOf(resultSet.getString("status")),
                        enumValue(AllocationType.class, resultSet.getString("allocation_type")),
                        List.of(),
                        shares(expenseId),
                        resultSet.getLong("version")))
                .optional();
    }

    boolean update(
            long tripId,
            long expenseId,
            long expectedVersion,
            Long payerId,
            Long amount,
            OffsetDateTime paidAt,
            AllocationType allocationType,
            ExpenseStatus status
    ) {
        return jdbcClient.sql("""
                        UPDATE expense
                        SET payer_id = :payerId,
                            amount = :amount,
                            currency = CASE WHEN :confirmed THEN 'JPY' ELSE NULL END,
                            base_amount = CASE WHEN :confirmed THEN :amount ELSE NULL END,
                            paid_at = :paidAt,
                            allocation_type = :allocationType,
                            status = :status,
                            confirmed_at = CASE
                                WHEN :confirmed THEN COALESCE(confirmed_at, CURRENT_TIMESTAMP)
                                ELSE NULL
                            END,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE trip_id = :tripId AND id = :expenseId AND version = :version
                        """)
                .param("payerId", payerId)
                .param("amount", amount)
                .param("confirmed", status == ExpenseStatus.CONFIRMED)
                .param("paidAt", paidAt)
                .param("allocationType", allocationType == null ? null : allocationType.name())
                .param("status", status.name())
                .param("tripId", tripId)
                .param("expenseId", expenseId)
                .param("version", expectedVersion)
                .update() == 1;
    }

    void replaceShares(long tripId, long expenseId, List<ExpenseShareResource> shares) {
        jdbcClient.sql("DELETE FROM expense_share WHERE expense_id = :expenseId")
                .param("expenseId", expenseId)
                .update();
        for (ExpenseShareResource share : shares) {
            jdbcClient.sql("""
                            INSERT INTO expense_share (
                                expense_id, trip_id, member_id, weight,
                                fixed_amount, final_amount
                            ) VALUES (
                                :expenseId, :tripId, :memberId, :weight,
                                :fixedAmount, :finalAmount
                            )
                            """)
                    .param("expenseId", expenseId)
                    .param("tripId", tripId)
                    .param("memberId", share.memberId())
                    .param("weight", share.weight())
                    .param("fixedAmount", share.fixedAmount())
                    .param("finalAmount", share.finalAmount())
                    .update();
        }
    }

    boolean deleteDraft(long tripId, long expenseId, long version) {
        return jdbcClient.sql("""
                        DELETE FROM expense
                        WHERE trip_id = :tripId AND id = :expenseId
                          AND status = 'DRAFT' AND version = :version
                        """)
                .param("tripId", tripId)
                .param("expenseId", expenseId)
                .param("version", version)
                .update() == 1;
    }

    boolean planItemBelongsToTrip(long tripId, long planItemId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM plan_item
                        WHERE trip_id = :tripId AND id = :planItemId
                        """)
                .param("tripId", tripId)
                .param("planItemId", planItemId)
                .query(Long.class)
                .single() == 1;
    }

    boolean membersBelongToTrip(long tripId, List<Long> memberIds) {
        if (memberIds.isEmpty()) return false;
        long count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM trip_member
                        WHERE trip_id = :tripId AND id IN (:memberIds)
                        """)
                .param("tripId", tripId)
                .param("memberIds", memberIds)
                .query(Long.class)
                .single();
        return count == memberIds.stream().distinct().count();
    }

    private List<ExpenseShareResource> shares(long expenseId) {
        return jdbcClient.sql("""
                        SELECT member_id, weight, fixed_amount, final_amount
                        FROM expense_share
                        WHERE expense_id = :expenseId
                        ORDER BY member_id
                        """)
                .param("expenseId", expenseId)
                .query((resultSet, rowNumber) -> new ExpenseShareResource(
                        resultSet.getLong("member_id"),
                        resultSet.getObject("weight", BigDecimal.class),
                        resultSet.getObject("fixed_amount", Long.class),
                        resultSet.getObject("final_amount", Long.class)))
                .list();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
