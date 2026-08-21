package app.tabikime.kimetabi.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class SettlementSchemaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("kimetabi")
                    .withUsername("kimetabi")
                    .withPassword("kimetabi");

    private final JdbcClient jdbcClient;

    @Autowired
    SettlementSchemaTest(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Test
    void persistsImmutableCalculationInputsAndTransferDrafts() {
        seedTripExpense();
        long settlementId = insertSettlement(1, 1);
        jdbcClient.sql("""
                INSERT INTO settlement_expense
                    (settlement_id, trip_id, expense_id, expense_version, payer_member_id, base_amount)
                VALUES (:settlementId, 1, 10, 4, 1, 300)
                """).param("settlementId", settlementId).update();
        jdbcClient.sql("""
                INSERT INTO settlement_share
                    (settlement_id, trip_id, expense_id, member_id, final_amount)
                VALUES (:settlementId, 1, 10, 1, 100), (:settlementId, 1, 10, 2, 200)
                """).param("settlementId", settlementId).update();
        jdbcClient.sql("""
                INSERT INTO settlement_transfer
                    (settlement_id, trip_id, from_member_id, to_member_id, amount)
                VALUES (:settlementId, 1, 2, 1, 100)
                """).param("settlementId", settlementId).update();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM settlement_share")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT amount FROM settlement_transfer")
                .query(Long.class).single()).isEqualTo(100L);
    }

    @Test
    void rejectsCrossTripSnapshotsAndInvalidTransfers() {
        seedTripExpense();
        long settlementId = insertSettlement(1, 1);

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO settlement_expense
                    (settlement_id, trip_id, expense_id, expense_version, payer_member_id, base_amount)
                VALUES (:settlementId, 2, 10, 0, 1, 300)
                """).param("settlementId", settlementId).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO settlement_transfer
                    (settlement_id, trip_id, from_member_id, to_member_id, amount)
                VALUES (:settlementId, 1, 1, 1, 100)
                """).param("settlementId", settlementId).update()).isInstanceOf(Exception.class);
    }

    private void seedTripExpense() {
        jdbcClient.sql("""
                TRUNCATE trip RESTART IDENTITY CASCADE
                """).update();
        jdbcClient.sql("""
                INSERT INTO trip
                    (id, title, destination, starts_on, ends_on, timezone, expected_member_count)
                VALUES (1, 'one', 'Tokyo', DATE '2026-09-01', DATE '2026-09-02', 'Asia/Tokyo', 2),
                       (2, 'two', 'Kyoto', DATE '2026-09-01', DATE '2026-09-02', 'Asia/Tokyo', 1)
                """).update();
        jdbcClient.sql("""
                INSERT INTO trip_member (id, trip_id, firebase_uid, name, role, status)
                VALUES (1, 1, 'owner-one', 'A', 'OWNER', 'ACTIVE'),
                       (2, 1, 'member-one', 'B', 'MEMBER', 'ACTIVE'),
                       (3, 2, 'owner-two', 'C', 'OWNER', 'ACTIVE')
                """).update();
        jdbcClient.sql("UPDATE trip SET owner_member_id = CASE id WHEN 1 THEN 1 ELSE 3 END")
                .update();
        jdbcClient.sql("""
                INSERT INTO expense
                    (id, trip_id, created_by_member_id, payer_id, amount, currency, base_amount,
                     paid_at, source, status, allocation_type, version, confirmed_at)
                VALUES (10, 1, 1, 1, 300, 'JPY', 300, CURRENT_TIMESTAMP,
                        'MANUAL', 'CONFIRMED', 'EQUAL', 4, CURRENT_TIMESTAMP)
                """).update();
    }

    private long insertSettlement(long tripId, long creatorId) {
        return jdbcClient.sql("""
                INSERT INTO settlement (trip_id, created_by_member_id)
                VALUES (:tripId, :creatorId)
                RETURNING id
                """).param("tripId", tripId).param("creatorId", creatorId)
                .query(Long.class).single();
    }
}
