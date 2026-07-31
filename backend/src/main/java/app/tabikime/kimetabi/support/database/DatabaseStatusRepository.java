package app.tabikime.kimetabi.support.database;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DatabaseStatusRepository {

    private final JdbcClient jdbcClient;

    public DatabaseStatusRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DatabaseIdentity currentIdentity() {
        return jdbcClient.sql("""
                        SELECT current_database() AS database_name,
                               current_schema() AS schema_name
                        """)
                .query(DatabaseIdentity.class)
                .single();
    }
}
