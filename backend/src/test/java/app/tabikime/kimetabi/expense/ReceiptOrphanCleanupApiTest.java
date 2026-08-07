package app.tabikime.kimetabi.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import app.tabikime.kimetabi.internal.InternalCaller;
import app.tabikime.kimetabi.internal.InternalOidcVerificationException;
import app.tabikime.kimetabi.internal.InternalOidcVerifier;
import app.tabikime.kimetabi.storage.ReceiptStorageGateway;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReceiptOrphanCleanupApiTest.Configuration.class)
@ExtendWith(OutputCaptureExtension.class)
class ReceiptOrphanCleanupApiTest {

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
    private RecordingStorageGateway storage;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("""
                        TRUNCATE idempotency_request, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO trip (
                            id, title, destination, starts_on, ends_on,
                            timezone, expected_member_count, revision
                        ) VALUES (
                            1, '旅行', '東京', DATE '2030-08-01', DATE '2030-08-02',
                            'Asia/Tokyo', 2, 0
                        )
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            id, trip_id, firebase_uid, name, role, status
                        ) VALUES (1, 1, 'owner-a', 'Owner', 'OWNER', 'ACTIVE')
                        """).update();
        jdbcClient.sql("UPDATE trip SET owner_member_id = 1 WHERE id = 1").update();
        jdbcClient.sql("""
                        INSERT INTO expense (
                            id, trip_id, created_by_member_id, amount, source, status
                        ) VALUES (1, 1, 1, 100, 'MANUAL', 'DRAFT')
                        """).update();
        storage.deleted.clear();
        storage.fail = false;
    }

    @Test
    void schedulerDeletesOnlyExpiredPendingAndFailedReceiptsWithoutLoggingObjectKeys(
            CapturedOutput output
    ) throws Exception {
        insertReceipt("00000000-0000-0000-0000-000000000001", "old-pending", "PENDING", "25 hours");
        insertReceipt("00000000-0000-0000-0000-000000000002", "old-failed", "FAILED", "48 hours");
        insertReceipt("00000000-0000-0000-0000-000000000003", "old-uploaded", "UPLOADED", "48 hours");
        insertReceipt("00000000-0000-0000-0000-000000000004", "recent-pending", "PENDING", "23 hours");

        mockMvc.perform(post("/internal/receipts/orphans/cleanup"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/receipts/orphans/cleanup")
                        .header("Authorization", "Bearer tasks-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/receipts/orphans/cleanup")
                        .header("Authorization", "Bearer scheduler-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(2))
                .andExpect(jsonPath("$.deleted").value(2));

        assertThat(storage.deleted).containsExactly("old-failed", "old-pending");
        assertThat(jdbcClient.sql("SELECT object_key FROM expense_receipt ORDER BY object_key")
                .query(String.class).list()).containsExactly("old-uploaded", "recent-pending");
        assertThat(jdbcClient.sql("SELECT version FROM expense WHERE id = 1")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM outbox_event
                        WHERE event_type = 'EXPENSE_RECEIPT_ORPHAN_CLEANED'
                        """).query(Long.class).single()).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain(
                "old-pending", "old-failed", "old-uploaded", "recent-pending");
    }

    @Test
    void storageFailureKeepsDatabaseRowForNextSchedulerRetry() throws Exception {
        insertReceipt("00000000-0000-0000-0000-000000000001", "retry-object", "PENDING", "25 hours");
        storage.fail = true;

        mockMvc.perform(post("/internal/receipts/orphans/cleanup")
                        .header("Authorization", "Bearer scheduler-token"))
                .andExpect(status().isInternalServerError());
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM expense_receipt")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT version FROM expense WHERE id = 1")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM outbox_event")
                .query(Long.class).single()).isZero();

        storage.fail = false;
        mockMvc.perform(post("/internal/receipts/orphans/cleanup")
                        .header("Authorization", "Bearer scheduler-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM expense_receipt")
                .query(Long.class).single()).isZero();
    }

    private void insertReceipt(String id, String objectKey, String status, String age) {
        jdbcClient.sql("""
                        INSERT INTO expense_receipt (
                            id, expense_id, trip_id, object_key, content_type,
                            byte_size, upload_status, created_at, uploaded_at
                        ) VALUES (
                            CAST(:id AS UUID), 1, 1, :objectKey, 'image/jpeg', 100, :status,
                            CURRENT_TIMESTAMP - CAST(:age AS INTERVAL),
                            CASE WHEN :status = 'UPLOADED' THEN CURRENT_TIMESTAMP ELSE NULL END
                        )
                        """)
                .param("id", id)
                .param("objectKey", objectKey)
                .param("status", status)
                .param("age", age)
                .update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        RecordingStorageGateway recordingStorageGateway() {
            return new RecordingStorageGateway();
        }

        @Bean
        @Primary
        InternalOidcVerifier internalOidcVerifier() {
            return (token, caller) -> {
                if (caller != InternalCaller.CLOUD_SCHEDULER
                        || !"scheduler-token".equals(token)) {
                    throw new InternalOidcVerificationException("fixture denied");
                }
            };
        }
    }

    static final class RecordingStorageGateway implements ReceiptStorageGateway {
        final List<String> deleted = new ArrayList<>();
        boolean fail;

        @Override
        public UploadCapability createUploadCapability(
                String objectKey, String contentType, long byteSize, java.time.Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredObject> find(String objectKey) {
            return Optional.empty();
        }

        @Override
        public void delete(String objectKey) {
            if (fail) throw new IllegalStateException("fixture storage failure");
            deleted.add(objectKey);
        }
    }
}
