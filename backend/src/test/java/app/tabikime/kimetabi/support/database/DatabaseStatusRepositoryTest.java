package app.tabikime.kimetabi.support.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/testmigration")
class DatabaseStatusRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("kimetabi")
                    .withUsername("kimetabi")
                    .withPassword("kimetabi");

    @Autowired
    private DatabaseStatusRepository repository;

    @Autowired
    private Flyway flyway;

    @Test
    void connectsToPostgresqlAndAppliesFlywayMigration() {
        DatabaseIdentity identity = repository.currentIdentity();

        assertThat(identity.databaseName()).isEqualTo("kimetabi");
        assertThat(identity.schemaName()).isEqualTo("public");
        assertThat(flyway.info().applied())
                .singleElement()
                .satisfies(migration -> {
                    assertThat(migration.getVersion().getVersion()).isEqualTo("1");
                    assertThat(migration.getDescription()).isEqualTo("create repository probe");
                });
    }
}
