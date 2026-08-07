package app.tabikime.kimetabi.expense;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ExpenseReceiptRepository {

    private final JdbcClient jdbcClient;

    ExpenseReceiptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insert(
            UUID receiptId,
            long tripId,
            long expenseId,
            String objectKey,
            String contentType,
            long byteSize
    ) {
        jdbcClient.sql("""
                        INSERT INTO expense_receipt (
                            id, expense_id, trip_id, object_key, content_type, byte_size
                        ) VALUES (
                            :id, :expenseId, :tripId, :objectKey, :contentType, :byteSize
                        )
                        """)
                .param("id", receiptId)
                .param("expenseId", expenseId)
                .param("tripId", tripId)
                .param("objectKey", objectKey)
                .param("contentType", contentType)
                .param("byteSize", byteSize)
                .update();
    }

    Optional<PendingReceipt> lock(long tripId, long expenseId, UUID receiptId) {
        return jdbcClient.sql("""
                        SELECT id, object_key, content_type, byte_size, upload_status
                        FROM expense_receipt
                        WHERE trip_id = :tripId AND expense_id = :expenseId AND id = :receiptId
                        FOR UPDATE
                        """)
                .param("tripId", tripId)
                .param("expenseId", expenseId)
                .param("receiptId", receiptId)
                .query((resultSet, rowNumber) -> new PendingReceipt(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("object_key"),
                        resultSet.getString("content_type"),
                        resultSet.getLong("byte_size"),
                        resultSet.getString("upload_status")))
                .optional();
    }

    void markUploaded(UUID receiptId) {
        jdbcClient.sql("""
                        UPDATE expense_receipt
                        SET upload_status = 'UPLOADED', uploaded_at = CURRENT_TIMESTAMP
                        WHERE id = :receiptId AND upload_status = 'PENDING'
                        """)
                .param("receiptId", receiptId)
                .update();
    }

    List<OrphanCandidate> findOrphanCandidates(Instant cutoff, int limit) {
        return jdbcClient.sql("""
                        SELECT id, trip_id, expense_id
                        FROM expense_receipt
                        WHERE upload_status IN ('PENDING', 'FAILED')
                          AND created_at <= :cutoff
                        ORDER BY created_at, id
                        LIMIT :limit
                        """)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new OrphanCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("trip_id"),
                        resultSet.getLong("expense_id")))
                .list();
    }

    Optional<OrphanReceipt> lockOrphan(UUID receiptId, Instant cutoff) {
        return jdbcClient.sql("""
                        SELECT id, trip_id, expense_id, object_key
                        FROM expense_receipt
                        WHERE id = :receiptId
                          AND upload_status IN ('PENDING', 'FAILED')
                          AND created_at <= :cutoff
                        FOR UPDATE
                        """)
                .param("receiptId", receiptId)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
                .query((resultSet, rowNumber) -> new OrphanReceipt(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("trip_id"),
                        resultSet.getLong("expense_id"),
                        resultSet.getString("object_key")))
                .optional();
    }

    boolean deleteOrphan(UUID receiptId) {
        return jdbcClient.sql("""
                        DELETE FROM expense_receipt
                        WHERE id = :receiptId
                          AND upload_status IN ('PENDING', 'FAILED')
                        """)
                .param("receiptId", receiptId)
                .update() == 1;
    }

    record PendingReceipt(
            UUID id,
            String objectKey,
            String contentType,
            long byteSize,
            String status
    ) {
    }

    record OrphanReceipt(UUID id, long tripId, long expenseId, String objectKey) {
    }

    record OrphanCandidate(UUID id, long tripId, long expenseId) {
    }
}
