package com.aiquantresearch.api.research.provider.runtime;

import com.aiquantresearch.api.research.provider.ProviderAccessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisProviderRuntime implements ProviderRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisProviderRuntime.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerRegistry circuits;
    private final MeterRegistry meters;
    private final ProviderRuntimeProperties properties;

    public RedisProviderRuntime(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuits,
            MeterRegistry meters,
            ProviderRuntimeProperties properties
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.circuits = circuits;
        this.meters = meters;
        this.properties = properties;
    }

    @Override
    public <T> T execute(ProviderCall<T> call, Supplier<T> loader) {
        String cacheKey = cacheKey(call);
        Optional<T> cached = read(cacheKey, call);
        if (cached.isPresent()) {
            cacheMetric(call.provider(), "hit");
            return cached.get();
        }
        cacheMetric(call.provider(), "miss");
        Timer.Sample timer = Timer.start(meters);
        try {
            T result = circuits.circuitBreaker(call.circuitName()).executeSupplier(loader);
            timer.stop(requestTimer(call.provider(), "success"));
            write(cacheKey, call, result);
            return result;
        } catch (CallNotPermittedException exception) {
            timer.stop(requestTimer(call.provider(), "circuit_open"));
            throw new ProviderAccessException(
                    "PROVIDER_CIRCUIT_OPEN",
                    "The configured provider circuit is temporarily open",
                    true
            );
        } catch (RuntimeException exception) {
            timer.stop(requestTimer(call.provider(), "failure"));
            throw exception;
        }
    }

    @Override
    public void recordRetry(String provider, String reason) {
        meters.counter("provider.retries", "provider", provider, "reason", safeTag(reason))
                .increment();
    }

    private <T> Optional<T> read(String key, ProviderCall<T> call) {
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, call.resultType()));
        } catch (Exception exception) {
            cacheMetric(call.provider(), "error");
            LOGGER.warn("Provider cache read failed provider={} errorType={}",
                    call.provider(), exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private <T> void write(String key, ProviderCall<T> call, T result) {
        try {
            String value = objectMapper.writeValueAsString(result);
            if (value.getBytes(StandardCharsets.UTF_8).length > properties.maxCacheEntryBytes()) {
                cacheMetric(call.provider(), "oversize");
                return;
            }
            redis.opsForValue().set(key, value, properties.cacheTtl());
            cacheMetric(call.provider(), "write");
        } catch (JsonProcessingException exception) {
            cacheMetric(call.provider(), "error");
            LOGGER.warn("Provider cache serialization failed provider={} errorType={}",
                    call.provider(), exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            cacheMetric(call.provider(), "error");
            LOGGER.warn("Provider cache write failed provider={} errorType={}",
                    call.provider(), exception.getClass().getSimpleName());
        }
    }

    private Timer requestTimer(String provider, String outcome) {
        return Timer.builder("provider.requests")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meters);
    }

    private void cacheMetric(String provider, String outcome) {
        meters.counter("provider.cache", "provider", provider, "outcome", outcome)
                .increment();
    }

    private static String cacheKey(ProviderCall<?> call) {
        return "provider:v1:" + call.provider().toLowerCase(java.util.Locale.ROOT)
                + ":" + call.schemaVersion() + ":" + sha256(call.subject());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String safeTag(String value) {
        if (value == null || !value.matches("^[A-Z0-9_]{1,80}$")) {
            return "UNKNOWN";
        }
        return value;
    }
}
