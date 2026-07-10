package com.aiquantresearch.api.research.worker;

import com.aiquantresearch.api.research.domain.StepType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcDurableQueueClient implements DurableQueueClient {

    private static final String CLAIM_SQL = "select * from queue_v1.claim_step(?, ?, ?)";
    private static final String HEARTBEAT_SQL = "select * from queue_v1.heartbeat(?, ?, ?)";
    private static final String COMPLETE_SQL = """
            select * from queue_v1.complete_step_and_advance(?, ?, ?, ?::jsonb)
            """;
    private static final String FAIL_SQL = "select * from queue_v1.fail_step(?, ?, ?, ?, ?, ?, ?)";
    private static final String REAP_SQL = "select count(*) from queue_v1.reap_expired(?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcDurableQueueClient(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<QueueClaim> claim(
            String workerId,
            Collection<StepType> supported,
            int leaseSeconds
    ) {
        var values = supported.stream().map(Enum::name).toArray(String[]::new);
        var claims = jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement(CLAIM_SQL);
            statement.setString(1, workerId);
            statement.setArray(2, connection.createArrayOf("varchar", values));
            statement.setInt(3, leaseSeconds);
            return statement;
        }, (row, ignored) -> new QueueClaim(
                row.getObject("research_job_id", UUID.class),
                row.getObject("research_step_id", UUID.class),
                row.getObject("attempt_id", UUID.class),
                row.getInt("attempt_number"),
                row.getObject("lease_token", UUID.class),
                row.getTimestamp("lease_expires_at").toInstant(),
                StepType.valueOf(row.getString("step_type")),
                row.getString("input_hash"),
                row.getString("implementation_version"),
                row.getInt("payload_version"),
                json(row.getString("payload_json"))
        ));
        if (claims.size() > 1) {
            throw new QueueProtocolException("claim_step returned more than one row");
        }
        return claims.stream().findFirst();
    }

    @Override
    public HeartbeatResult heartbeat(UUID attemptId, UUID leaseToken, int leaseSeconds) {
        return one(HEARTBEAT_SQL, (row, ignored) -> new HeartbeatResult(
                row.getString("result_code"),
                row.getBoolean("cancellation_requested"),
                instant(row, "lease_expires_at")
        ), attemptId, leaseToken, leaseSeconds);
    }

    @Override
    public QueueCompletion completeAndAdvance(
            UUID attemptId,
            UUID leaseToken,
            String outputHash,
            JsonNode outputManifest
    ) {
        return one(COMPLETE_SQL, (row, ignored) -> new QueueCompletion(
                row.getString("result_code"),
                row.getObject("research_job_id", UUID.class),
                row.getObject("research_step_id", UUID.class),
                row.getString("committed_output_hash"),
                row.getObject("next_research_step_id", UUID.class),
                nullableStepType(row.getString("next_step_type")),
                row.getString("next_input_hash")
        ), attemptId, leaseToken, outputHash, jsonText(outputManifest));
    }

    @Override
    public QueueFailure fail(
            UUID attemptId,
            UUID leaseToken,
            boolean retryable,
            String errorCode,
            String safeMessage,
            int baseDelaySeconds,
            int maxDelaySeconds
    ) {
        return one(FAIL_SQL, (row, ignored) -> new QueueFailure(
                row.getString("result_code"),
                row.getObject("research_job_id", UUID.class),
                row.getObject("research_step_id", UUID.class),
                row.getString("step_status"),
                instant(row, "available_at")
        ), attemptId, leaseToken, retryable, errorCode, safeMessage,
                baseDelaySeconds, maxDelaySeconds);
    }

    @Override
    public int reapExpired(int batchSize, int baseDelaySeconds, int maxDelaySeconds) {
        Integer value = jdbcTemplate.queryForObject(
                REAP_SQL,
                Integer.class,
                batchSize,
                baseDelaySeconds,
                maxDelaySeconds
        );
        return value == null ? 0 : value;
    }

    private <T> T one(
            String sql,
            org.springframework.jdbc.core.RowMapper<T> mapper,
            Object... parameters
    ) {
        var rows = jdbcTemplate.query(sql, preparedStatement -> {
            for (int index = 0; index < parameters.length; index++) {
                Object value = parameters[index];
                if (value == null) {
                    preparedStatement.setNull(index + 1, Types.VARCHAR);
                } else {
                    preparedStatement.setObject(index + 1, value);
                }
            }
        }, mapper);
        if (rows.size() != 1) {
            throw new QueueProtocolException(
                    "Durable queue command returned " + rows.size() + " rows"
            );
        }
        return rows.getFirst();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new QueueProtocolException("Queue payload is not valid JSON", exception);
        }
    }

    private String jsonText(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new QueueProtocolException("Queue output manifest is not valid JSON", exception);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static StepType nullableStepType(String value) {
        return value == null ? null : StepType.valueOf(value);
    }
}
