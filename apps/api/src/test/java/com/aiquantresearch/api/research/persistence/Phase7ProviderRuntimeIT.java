package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.provider.runtime.ProviderCall;
import com.aiquantresearch.api.research.provider.runtime.ProviderRuntime;
import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class Phase7ProviderRuntimeIT extends PostgresRedisIntegrationTestSupport {

    @Autowired
    private ProviderRuntime runtime;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meters;

    @Test
    void redisCacheRetainsBoundedSnapshotWithTtlAndEmitsHitMetric() {
        String subject = "runtime-it-" + UUID.randomUUID();
        ProviderCall<JsonNode> call = new ProviderCall<>(
                "INTEGRATION_TEST",
                "provider-runtime-it",
                "it-v1",
                subject,
                JsonNode.class
        );
        AtomicInteger loads = new AtomicInteger();

        JsonNode first = runtime.execute(call, () -> {
            loads.incrementAndGet();
            return objectMapper.createObjectNode().put("value", "immutable");
        });
        JsonNode second = runtime.execute(call, () -> {
            loads.incrementAndGet();
            return objectMapper.createObjectNode().put("value", "unexpected");
        });

        assertThat(first).isEqualTo(second);
        assertThat(loads).hasValue(1);
        Set<String> keys = redis.keys("provider:v1:integration_test:it-v1:*");
        assertThat(keys).hasSize(1);
        String key = keys.iterator().next();
        assertThat(key).doesNotContain(subject);
        Long ttlSeconds = redis.getExpire(key);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(21_600L);
        assertThat(meters.counter(
                "provider.cache",
                "provider", "INTEGRATION_TEST",
                "outcome", "hit"
        ).count()).isGreaterThanOrEqualTo(1);
        redis.delete(key);
    }
}
