package app.tabikime.kimetabi.support.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
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
class DatabaseMigrationReleaseTest {

    private static final List<String> EXPECTED_VERSIONS = List.of(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");

    private static final Set<String> DOMAIN_TABLES = Set.of(
            "audit_event",
            "candidate",
            "candidate_tag",
            "candidate_vote",
            "expense",
            "expense_receipt",
            "expense_share",
            "idempotency_request",
            "invite_token",
            "outbox_event",
            "plan_item",
            "recovery_token",
            "settlement",
            "settlement_expense",
            "settlement_share",
            "settlement_source_transfer",
            "settlement_transfer",
            "slot",
            "token_rate_limit",
            "trip",
            "trip_member",
            "trip_member_unsettled_balance");

    private static final Set<String> RELEASE_CRITICAL_CONSTRAINTS = Set.of(
            "fk_trip_owner",
            "fk_candidate_slot",
            "fk_slot_adopted_candidate",
            "fk_expense_creator",
            "fk_expense_payer",
            "fk_share_expense",
            "fk_share_member",
            "fk_settlement_expense_expense",
            "fk_settlement_share_expense",
            "fk_source_transfer_transfer",
            "ck_expense_confirmed",
            "ck_expense_mvp_currency",
            "ck_settlement_status_time",
            "ck_transfer_status_time");

    private static final Set<String> RELEASE_CRITICAL_INDEXES = Set.of(
            "uq_trip_active_owner",
            "ix_trip_active_updated",
            "ix_candidate_slot",
            "ix_candidate_metadata_pending",
            "ix_expense_trip_page",
            "ix_expense_receipt_expense",
            "ix_outbox_unpublished",
            "ix_settlement_trip_created",
            "ix_transfer_settlement");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("kimetabi")
                    .withUsername("kimetabi")
                    .withPassword("kimetabi");

    private final Flyway flyway;
    private final JdbcClient jdbcClient;

    @Autowired
    DatabaseMigrationReleaseTest(Flyway flyway, JdbcClient jdbcClient) {
        this.flyway = flyway;
        this.jdbcClient = jdbcClient;
    }

    @Test
    void freshDatabaseAppliesAndValidatesTheCompleteMigrationChain() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(successfulVersions()).containsExactlyElementsOf(EXPECTED_VERSIONS);

        flyway.migrate();

        assertThat(successfulVersions()).containsExactlyElementsOf(EXPECTED_VERSIONS);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void migratedSchemaContainsReleaseCriticalDomainBoundaries() {
        Set<String> tables = jdbcClient.sql("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                        """)
                .query(String.class)
                .set();
        Set<String> constraints = jdbcClient.sql("""
                        SELECT constraint_name
                        FROM information_schema.table_constraints
                        WHERE constraint_schema = 'public'
                        """)
                .query(String.class)
                .set();
        Set<String> indexes = jdbcClient.sql("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                        """)
                .query(String.class)
                .set();

        assertThat(tables).containsAll(DOMAIN_TABLES);
        assertThat(constraints).containsAll(RELEASE_CRITICAL_CONSTRAINTS);
        assertThat(indexes).containsAll(RELEASE_CRITICAL_INDEXES);
    }

    private List<String> successfulVersions() {
        return jdbcClient.sql("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success
                        ORDER BY installed_rank
                        """)
                .query(String.class)
                .list();
    }
}
