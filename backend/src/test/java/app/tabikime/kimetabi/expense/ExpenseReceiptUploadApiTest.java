package app.tabikime.kimetabi.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import app.tabikime.kimetabi.identity.AppPrincipal;
import app.tabikime.kimetabi.storage.ReceiptStorageGateway;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(ExpenseReceiptUploadApiTest.StorageTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ExpenseReceiptUploadApiTest {

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
    private FakeReceiptStorageGateway storage;

    @BeforeEach
    void setUp() {
        storage.signCount = 0;
        storage.storedObject = Optional.empty();
        jdbcClient.sql("""
                        TRUNCATE idempotency_request, trip_member, trip
                        RESTART IDENTITY CASCADE
                        """).update();
        insertTrip(1, "owner-a", "OWNER", "ACTIVE");
        insertMember(2, 1, "member-a", "MEMBER", "ACTIVE");
        insertMember(3, 1, "member-other", "MEMBER", "ACTIVE");
        insertMember(4, 1, "inactive-a", "MEMBER", "LEFT");
        insertTrip(2, "owner-b", "OWNER", "ACTIVE");
        insertExpense(1, 1, 2);
    }

    @Test
    void deniesUnauthenticatedInactiveNonMemberOtherCreatorAndCrossTripBeforeSigning()
            throws Exception {
        prepare(null, 1, 1, "image/jpeg", 100, 0).andExpect(status().isUnauthorized());
        prepare("inactive-a", 1, 1, "image/jpeg", 100, 0).andExpect(status().isNotFound());
        prepare("unknown", 1, 1, "image/jpeg", 100, 0).andExpect(status().isNotFound());
        prepare("member-other", 1, 1, "image/jpeg", 100, 0).andExpect(status().isForbidden());
        prepare("owner-b", 2, 1, "image/jpeg", 100, 0).andExpect(status().isNotFound());

        assertThat(storage.signCount).isZero();
        assertThat(count("expense_receipt")).isZero();
    }

    @Test
    void rejectsUnsupportedMimeEmptyAndOversizedImages() throws Exception {
        prepare("member-a", 1, 1, "image/svg+xml", 100, 0)
                .andExpect(status().isUnprocessableEntity());
        prepare("member-a", 1, 1, "image/png", 0, 0)
                .andExpect(status().isUnprocessableEntity());
        prepare("member-a", 1, 1, "image/webp", 10_485_761, 0)
                .andExpect(status().isUnprocessableEntity());

        assertThat(storage.signCount).isZero();
    }

    @Test
    void issuesShortLivedCapabilityStoresOnlyObjectKeyAndDoesNotLogSecret(CapturedOutput output)
            throws Exception {
        prepare("member-a", 1, 1, "image/webp", 321, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptId").isNotEmpty())
                .andExpect(jsonPath("$.uploadUrl").value("https://storage.invalid/upload?secret=redacted"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.requiredHeaders.Content-Type").value("image/webp"))
                .andExpect(jsonPath("$.requiredHeaders.Content-Length").value("321"))
                .andExpect(jsonPath("$.requiredHeaders.x-goog-if-generation-match").value("0"))
                .andExpect(jsonPath("$.expenseVersion").value(1));

        String objectKey = jdbcClient.sql("SELECT object_key FROM expense_receipt")
                .query(String.class).single();
        assertThat(objectKey).matches("receipts/1/1/[0-9a-f-]{36}");
        assertThat(objectKey).doesNotContain("secret", "http", "webp");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_name = 'expense_receipt'
                          AND column_name IN ('url', 'upload_url', 'original_filename')
                        """).query(Long.class).single()).isZero();
        assertThat(output.getAll()).doesNotContain(
                "https://storage.invalid/upload", "secret=redacted");
    }

    @Test
    void completionVerifiesStorageMetadataBeforeMarkingUploaded() throws Exception {
        String response = prepare("member-a", 1, 1, "image/jpeg", 512, 0)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String receiptId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("receiptId").asText();

        storage.storedObject = Optional.of(new ReceiptStorageGateway.StoredObject("image/png", 512));
        complete("member-a", 1, 1, receiptId, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("contentType"));
        assertThat(receiptStatus(receiptId)).isEqualTo("PENDING");

        storage.storedObject = Optional.of(new ReceiptStorageGateway.StoredObject("image/jpeg", 513));
        complete("member-a", 1, 1, receiptId, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("byteSize"));
        assertThat(receiptStatus(receiptId)).isEqualTo("PENDING");

        storage.storedObject = Optional.of(new ReceiptStorageGateway.StoredObject("image/jpeg", 512));
        complete("member-a", 1, 1, receiptId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipts[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.receipts[0].contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.receipts[0].byteSize").value(512))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(countWhere("audit_event", "action IN ('EXPENSE_RECEIPT_UPLOAD_PREPARED', 'EXPENSE_RECEIPT_UPLOADED')"))
                .isEqualTo(2);
        assertThat(countWhere(
                "outbox_event",
                "event_type IN ('EXPENSE_RECEIPT_UPLOAD_PREPARED', 'EXPENSE_RECEIPT_UPLOADED')"))
                .isEqualTo(2);
    }

    @Test
    void rejectsStaleVersionAndReceiptFromAnotherExpense() throws Exception {
        String response = prepare("member-a", 1, 1, "image/png", 100, 0)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String receiptId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("receiptId").asText();
        storage.storedObject = Optional.of(new ReceiptStorageGateway.StoredObject("image/png", 100));

        complete("member-a", 1, 1, receiptId, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        insertExpense(2, 1, 2);
        complete("member-a", 1, 2, receiptId, 0).andExpect(status().isNotFound());
        assertThat(receiptStatus(receiptId)).isEqualTo("PENDING");
    }

    private org.springframework.test.web.servlet.ResultActions prepare(
            String uid, long tripId, long expenseId,
            String contentType, long byteSize, long version
    ) throws Exception {
        var request = post("/api/trips/{tripId}/expenses/{expenseId}/receipt-upload", tripId, expenseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"contentType":"%s","byteSize":%d,"version":%d}
                        """.formatted(contentType, byteSize, version));
        if (uid != null) request.with(principal(uid));
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions complete(
            String uid, long tripId, long expenseId, String receiptId, long version
    ) throws Exception {
        return mockMvc.perform(post(
                        "/api/trips/{tripId}/expenses/{expenseId}/receipts/{receiptId}/completion",
                        tripId, expenseId, receiptId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}")
                .with(principal(uid)));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor principal(String uid) {
        AppPrincipal principal = new AppPrincipal(uid);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, "n/a", java.util.List.of()));
    }

    private void insertTrip(long id, String uid, String role, String status) {
        jdbcClient.sql("""
                        INSERT INTO trip (
                            id, title, destination, starts_on, ends_on,
                            timezone, expected_member_count
                        ) VALUES (
                            :id, '旅行', '東京', DATE '2030-08-01',
                            DATE '2030-08-02', 'Asia/Tokyo', 3
                        )
                        """).param("id", id).update();
        long ownerId = id == 1 ? 1 : 5;
        insertMember(ownerId, id, uid, role, status);
        jdbcClient.sql("UPDATE trip SET owner_member_id = :ownerId WHERE id = :tripId")
                .param("ownerId", ownerId).param("tripId", id).update();
    }

    private void insertMember(long id, long tripId, String uid, String role, String status) {
        jdbcClient.sql("""
                        INSERT INTO trip_member (
                            id, trip_id, firebase_uid, name, role, status, left_at
                        ) VALUES (
                            :id, :tripId, :uid, :uid, :role, :status,
                            CASE WHEN :status = 'ACTIVE' THEN NULL ELSE CURRENT_TIMESTAMP END
                        )
                        """).param("id", id).param("tripId", tripId).param("uid", uid)
                .param("role", role).param("status", status).update();
    }

    private void insertExpense(long id, long tripId, long creatorId) {
        jdbcClient.sql("""
                        INSERT INTO expense (id, trip_id, created_by_member_id, amount, source, status)
                        VALUES (:id, :tripId, :creatorId, 100, 'MANUAL', 'DRAFT')
                        """).param("id", id).param("tripId", tripId).param("creatorId", creatorId).update();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long countWhere(String table, String predicate) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)
                .query(Long.class).single();
    }

    private String receiptStatus(String receiptId) {
        return jdbcClient.sql("SELECT upload_status FROM expense_receipt WHERE id = :id")
                .param("id", java.util.UUID.fromString(receiptId)).query(String.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfiguration {
        @Bean
        @Primary
        FakeReceiptStorageGateway fakeReceiptStorageGateway() {
            return new FakeReceiptStorageGateway();
        }
    }

    static final class FakeReceiptStorageGateway implements ReceiptStorageGateway {
        int signCount;
        Optional<StoredObject> storedObject = Optional.empty();

        @Override
        public UploadCapability createUploadCapability(
                String objectKey, String contentType, long byteSize, java.time.Duration ttl) {
            signCount++;
            return new UploadCapability(
                    URI.create("https://storage.invalid/upload?secret=redacted"),
                    Map.of(
                            "Content-Type", contentType,
                            "Content-Length", Long.toString(byteSize),
                            "x-goog-if-generation-match", "0"));
        }

        @Override
        public Optional<StoredObject> find(String objectKey) {
            return storedObject;
        }

        @Override
        public void delete(String objectKey) {
        }
    }
}
