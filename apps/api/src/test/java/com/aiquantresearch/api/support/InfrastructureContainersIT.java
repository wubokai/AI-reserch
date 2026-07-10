package com.aiquantresearch.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class InfrastructureContainersIT extends PostgresRedisIntegrationTestSupport {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private Flyway flyway;

    @Test
    void applicationCanReachPostgresAfterFlywayStartup() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void applicationCanReachRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @Test
    void repeatedFlywayValidationAndMigrationAreChecksumStableNoOps() {
        List<Map<String, Object>> historyBefore = migrationHistory();

        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        assertThat(migrationHistory()).containsExactlyElementsOf(historyBefore);
    }

    private List<Map<String, Object>> migrationHistory() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     select installed_rank, version, description, type, script, checksum, success
                       from flyway_schema_history
                      order by installed_rank
                     """)) {
            var history = new java.util.ArrayList<Map<String, Object>>();
            while (result.next()) {
                history.add(Map.of(
                        "installedRank", result.getInt("installed_rank"),
                        "version", result.getString("version"),
                        "description", result.getString("description"),
                        "type", result.getString("type"),
                        "script", result.getString("script"),
                        "checksum", result.getInt("checksum"),
                        "success", result.getBoolean("success")
                ));
            }
            return history;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not read Flyway migration history", exception);
        }
    }
}
