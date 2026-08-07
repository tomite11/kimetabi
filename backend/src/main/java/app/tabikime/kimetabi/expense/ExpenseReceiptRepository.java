package app.tabikime.kimetabi.expense;

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

    record PendingReceipt(
            UUID id,
            String objectKey,
            String contentType,
            long byteSize,
            String status
    ) {
    }
}
