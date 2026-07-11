package com.aiquantresearch.api.research.provider.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.provider.ProviderAccessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class RedisProviderRuntimeTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        meters = new SimpleMeterRegistry();
    }

    @Test
    void cachesBoundedSnapshotAndServesSubsequentHit() throws Exception {
        var runtime = runtime(CircuitBreakerRegistry.ofDefaults(), 5_000);
        var call = new ProviderCall<>(
                "FRED", "fred-test", "fred-v1", "DFF|secret-shaped-subject",
                TestSnapshot.class
        );
        TestSnapshot expected = new TestSnapshot("FRED", "DFF", "5.33");
        when(values.get(anyString())).thenReturn(null)
                .thenReturn(objectMapper.writeValueAsString(expected));

        assertThat(runtime.execute(call, () -> expected)).isEqualTo(expected);
        assertThat(runtime.execute(call, () -> {
            throw new AssertionError("cache hit must not invoke loader");
        })).isEqualTo(expected);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), anyString(), any(Duration.class));
        assertThat(key.getValue())
                .startsWith("provider:v1:fred:fred-v1:")
                .doesNotContain("DFF")
                .doesNotContain("secret-shaped-subject");
        assertThat(meters.counter("provider.cache", "provider", "FRED", "outcome", "hit")
                .count()).isEqualTo(1);
    }

    @Test
    void skipsOversizeEntryWithoutFailingProviderCall() {
        var runtime = runtime(CircuitBreakerRegistry.ofDefaults(), 1_024);
        var call = new ProviderCall<>(
                "SEC_EDGAR", "sec-test", "filing-v1", "MU", TestSnapshot.class
        );
        when(values.get(anyString())).thenReturn(null);

        TestSnapshot result = runtime.execute(
                call,
                () -> new TestSnapshot("SEC_EDGAR", "MU", "x".repeat(2_000))
        );

        assertThat(result.value()).hasSize(2_000);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(meters.counter(
                "provider.cache", "provider", "SEC_EDGAR", "outcome", "oversize"
        ).count()).isEqualTo(1);
    }

    @Test
    void opensCircuitOnlyAfterRetryableProviderFailures() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordException(new ProviderCircuitFailurePredicate())
                .build();
        var runtime = runtime(CircuitBreakerRegistry.of(config), 5_000);
        var call = new ProviderCall<>(
                "SEC_EDGAR", "sec-test", "filing-v1", "MU", TestSnapshot.class
        );
        when(values.get(anyString())).thenReturn(null);
        AtomicInteger calls = new AtomicInteger();

        for (int index = 0; index < 2; index++) {
            assertThatThrownBy(() -> runtime.execute(call, () -> {
                calls.incrementAndGet();
                throw new ProviderAccessException("SEC_UNAVAILABLE", "unavailable", true);
            })).isInstanceOf(ProviderAccessException.class)
                    .hasMessage("unavailable");
        }
        assertThatThrownBy(() -> runtime.execute(call, () -> {
            calls.incrementAndGet();
            return new TestSnapshot("SEC_EDGAR", "MU", "unexpected");
        })).isInstanceOfSatisfying(ProviderAccessException.class, exception -> {
            assertThat(exception.code()).isEqualTo("PROVIDER_CIRCUIT_OPEN");
            assertThat(exception.retryable()).isTrue();
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    void permanentProviderErrorsNeverOpenCircuit() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordException(new ProviderCircuitFailurePredicate())
                .build();
        CircuitBreakerRegistry circuits = CircuitBreakerRegistry.of(config);
        var runtime = runtime(circuits, 5_000);
        var call = new ProviderCall<>(
                "SEC_EDGAR", "sec-permanent-test", "filing-v1", "MU",
                TestSnapshot.class
        );
        when(values.get(anyString())).thenReturn(null);

        for (int index = 0; index < 4; index++) {
            assertThatThrownBy(() -> runtime.execute(call, () -> {
                throw new ProviderAccessException("SEC_SCHEMA_INVALID", "invalid", false);
            })).isInstanceOf(ProviderAccessException.class)
                    .hasMessage("invalid");
        }
        assertThat(circuits.circuitBreaker("sec-permanent-test").getState().name())
                .isEqualTo("CLOSED");
        assertThat(runtime.execute(
                call,
                () -> new TestSnapshot("SEC_EDGAR", "MU", "available")
        ).value()).isEqualTo("available");
    }

    @Test
    void redisFailureDegradesToCacheMissWithoutBlockingProvider() {
        var runtime = runtime(CircuitBreakerRegistry.ofDefaults(), 5_000);
        var call = new ProviderCall<>(
                "FRED", "fred-cache-error", "fred-v1", "DFF", TestSnapshot.class
        );
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis unavailable"));

        TestSnapshot result = runtime.execute(
                call,
                () -> new TestSnapshot("FRED", "DFF", "5.33")
        );

        assertThat(result.value()).isEqualTo("5.33");
        assertThat(meters.counter("provider.cache", "provider", "FRED", "outcome", "error")
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cacheTtlCannotBeDisabledOrExtendedBeyondPolicyBoundary() {
        assertThatThrownBy(() -> new ProviderRuntimeProperties(Duration.ZERO, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> new ProviderRuntimeProperties(Duration.ofDays(8), 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seven days");
    }

    private RedisProviderRuntime runtime(
            CircuitBreakerRegistry circuits,
            int maxEntryBytes
    ) {
        return new RedisProviderRuntime(
                redis,
                objectMapper,
                circuits,
                meters,
                new ProviderRuntimeProperties(Duration.ofHours(6), maxEntryBytes)
        );
    }

    private record TestSnapshot(String provider, String symbol, String value) {
    }
}
