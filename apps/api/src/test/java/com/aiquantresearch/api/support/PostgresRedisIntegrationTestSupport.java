package com.aiquantresearch.api.support;

import java.util.stream.Stream;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL and Redis infrastructure for Docker-backed Spring integration tests.
 *
 * <p>Concrete tests must use the {@code *IT} suffix. Surefire excludes that suffix, while
 * Failsafe runs it during {@code mvn verify}. As a result, ordinary {@code mvn test} runs never
 * inspect Docker or start these containers. The containers use the Testcontainers singleton
 * pattern so every integration-test class shares one stable set of mapped ports and one cached
 * Spring context for the complete Failsafe JVM.</p>
 */
@ActiveProfiles({"test", "integration-test"})
public abstract class PostgresRedisIntegrationTestSupport {

    private static final int REDIS_PORT = 6379;

    protected static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("ai_quant_research_test")
                    .withUsername("ai_quant_test")
                    .withPassword("ai_quant_test");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .waitingFor(Wait.forListeningPort());

    static {
        Startables.deepStart(Stream.of(POSTGRESQL, REDIS)).join();
    }

    @DynamicPropertySource
    protected static void registerInfrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.password", () -> "");
    }
}
