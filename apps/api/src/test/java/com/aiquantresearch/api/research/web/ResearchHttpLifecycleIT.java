package com.aiquantresearch.api.research.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.application.CreateResearchCommand;
import com.aiquantresearch.api.research.application.ResearchCommandService;
import com.aiquantresearch.api.research.application.ResearchWorkflowService;
import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchPeriod;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.demo-principal.enabled=true",
                "app.security.demo-principal.username=phase2-http-integration-user",
                "app.security.demo-principal.password=phase2-http-integration-password-32!"
        }
)
@ResourceLock("durable-postgres-queue")
class ResearchHttpLifecycleIT extends PostgresRedisIntegrationTestSupport {

    private static final String DEMO_USERNAME = "phase2-http-integration-user";
    private static final String DEMO_PASSWORD = "phase2-http-integration-password-32!";
    private static final UUID DEMO_OWNER_ID = stableOwnerId(DEMO_USERNAME);
    private static final String CREATE_KEY = "http-it-create-001";
    private static final String VALID_CREATE_JSON = """
            {
              "query":"Analyze NVIDIA durable growth drivers and material valuation risks",
              "symbol":"NVDA",
              "locale":"en-US",
              "benchmark":"SPY",
              "period":"5y",
              "reportDepth":"STANDARD",
              "includeTechnicalAnalysis":true,
              "includeFundamentalAnalysis":true,
              "includeMacroAnalysis":true
            }
            """;
    private static final String CHANGED_CREATE_JSON = """
            {
              "query":"Analyze NVIDIA near-term margin drivers and downside scenarios",
              "symbol":"NVDA",
              "locale":"en-US",
              "benchmark":"SPY",
              "period":"5y",
              "reportDepth":"STANDARD",
              "includeTechnicalAnalysis":true,
              "includeFundamentalAnalysis":true,
              "includeMacroAnalysis":true
            }
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResearchCommandService commandService;

    @Autowired
    private ResearchWorkflowService workflowService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Set<UUID> createdOwnerIds = new LinkedHashSet<>();
    private final Set<UUID> createdResearchIds = new LinkedHashSet<>();

    @BeforeEach
    void preparePreciseCleanupScope() {
        createdOwnerIds.add(DEMO_OWNER_ID);
        deleteOnlyTrackedRows();
        createdOwnerIds.add(DEMO_OWNER_ID);
    }

    @AfterEach
    void deleteOnlyThisTestsRows() {
        deleteOnlyTrackedRows();
    }

    @Test
    void basicAuthenticationDrivesTheCompleteHttpResearchLifecycle() throws Exception {
        HttpResponse<String> unauthenticated = request("GET", "/api/v1/research", null, false, Map.of());
        assertProblem(unauthenticated, 401, "UNAUTHORIZED");

        HttpResponse<String> invalid = request(
                "POST",
                "/api/v1/research",
                "{\"query\":\"short\",\"symbol\":\"NVDA\"}",
                true,
                Map.of("Idempotency-Key", "http-it-invalid-001")
        );
        assertProblem(invalid, 400, "INVALID_REQUEST");

        long createStartedAt = System.nanoTime();
        HttpResponse<String> created = request(
                "POST",
                "/api/v1/research",
                VALID_CREATE_JSON,
                true,
                Map.of("Idempotency-Key", CREATE_KEY)
        );
        Duration createDuration = Duration.ofNanos(System.nanoTime() - createStartedAt);
        assertThat(created.statusCode()).isEqualTo(202);
        assertThat(createDuration)
                .as("HTTP create must enqueue the durable plan without waiting for a worker")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(header(created, "Idempotency-Replayed")).contains("false");
        assertThat(header(created, "Retry-After")).contains("2");
        JsonNode createdJson = json(created);
        UUID researchId = UUID.fromString(createdJson.path("researchId").asText());
        createdResearchIds.add(researchId);
        assertThat(createdJson.path("status").asText()).isEqualTo("QUEUED");
        assertThat(header(created, "Location")).contains("/api/v1/research/" + researchId);
        assertThat(count("select count(*) from research_steps where research_job_id = ?", researchId))
                .isEqualTo(StepType.values().length);

        // Every request supplies Basic credentials again. This guards against a cached UserDetails
        // instance having its password erased by the preceding authentication.
        HttpResponse<String> replayed = request(
                "POST",
                "/api/v1/research",
                VALID_CREATE_JSON,
                true,
                Map.of("Idempotency-Key", CREATE_KEY)
        );
        assertThat(replayed.statusCode()).isEqualTo(202);
        assertThat(header(replayed, "Idempotency-Replayed")).contains("true");
        assertThat(json(replayed).path("researchId").asText()).isEqualTo(researchId.toString());
        assertThat(count("select count(*) from research_jobs where user_id = ?", DEMO_OWNER_ID)).isOne();

        HttpResponse<String> changedBodyConflict = request(
                "POST",
                "/api/v1/research",
                CHANGED_CREATE_JSON,
                true,
                Map.of("Idempotency-Key", CREATE_KEY)
        );
        assertProblem(changedBodyConflict, 409, "IDEMPOTENCY_KEY_REUSED");

        HttpResponse<String> status = request(
                "GET",
                "/api/v1/research/" + researchId + "/status",
                null,
                true,
                Map.of()
        );
        assertThat(status.statusCode()).isEqualTo(200);
        JsonNode statusJson = json(status);
        assertThat(statusJson.path("researchId").asText()).isEqualTo(researchId.toString());
        assertThat(statusJson.path("status").asText()).isEqualTo("QUEUED");
        assertThat(statusJson.path("totalSteps").asInt()).isEqualTo(StepType.values().length);
        assertThat(statusJson.path("steps")).hasSize(StepType.values().length);
        assertThat(statusJson.path("steps").findValuesAsText("status"))
                .containsOnly("PENDING");

        HttpResponse<String> ownList = request(
                "GET",
                "/api/v1/research?page=0&size=20",
                null,
                true,
                Map.of()
        );
        assertThat(ownList.statusCode()).isEqualTo(200);
        JsonNode ownListJson = json(ownList);
        assertThat(ownListJson.path("items")).hasSize(1);
        assertThat(ownListJson.path("items").get(0).path("researchId").asText())
                .isEqualTo(researchId.toString());
        assertThat(ownListJson.path("items").get(0).path("status").asText())
                .isEqualTo("QUEUED");

        HttpResponse<String> filteredList = request(
                "GET",
                "/api/v1/research?symbol=nvda&status=QUEUED&q=NVIDIA"
                        + "&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z",
                null,
                true,
                Map.of()
        );
        assertThat(filteredList.statusCode()).isEqualTo(200);
        assertThat(json(filteredList).path("items"))
                .singleElement()
                .satisfies(item -> assertThat(item.path("researchId").asText())
                        .isEqualTo(researchId.toString()));

        HttpResponse<String> ownDetail = request(
                "GET",
                "/api/v1/research/" + researchId,
                null,
                true,
                Map.of()
        );
        assertThat(ownDetail.statusCode()).isEqualTo(200);
        JsonNode ownDetailJson = json(ownDetail);
        assertThat(ownDetailJson.path("researchId").asText()).isEqualTo(researchId.toString());
        assertThat(ownDetailJson.path("status").asText()).isEqualTo("QUEUED");
        assertThat(ownDetailJson.path("symbol").asText()).isEqualTo("NVDA");
        assertThat(ownDetailJson.path("query").asText())
                .isEqualTo("Analyze NVIDIA durable growth drivers and material valuation risks");

        createFailedResearchAndRetryThroughHttp();

        UUID foreignResearchId = createForeignOwnedResearch();
        HttpResponse<String> hiddenForeignResearch = request(
                "GET",
                "/api/v1/research/" + foreignResearchId,
                null,
                true,
                Map.of()
        );
        assertProblem(hiddenForeignResearch, 404, "RESEARCH_NOT_FOUND");

        HttpResponse<String> illegalRetry = request(
                "POST",
                "/api/v1/research/" + researchId + "/retry",
                "{}",
                true,
                Map.of("Idempotency-Key", "http-it-retry-active-001")
        );
        assertProblem(illegalRetry, 409, "INVALID_STATE_TRANSITION");

        HttpResponse<String> illegalDelete = request(
                "DELETE",
                "/api/v1/research/" + researchId,
                null,
                true,
                Map.of()
        );
        assertProblem(illegalDelete, 409, "INVALID_STATE_TRANSITION");

        HttpResponse<String> cancelled = request(
                "POST",
                "/api/v1/research/" + researchId + "/cancel",
                "{\"reason\":\"HTTP integration cancellation\"}",
                true,
                Map.of("Idempotency-Key", "http-it-cancel-001")
        );
        assertThat(cancelled.statusCode()).isEqualTo(202);
        assertThat(header(cancelled, "Idempotency-Replayed")).contains("false");
        JsonNode cancelledJson = json(cancelled);
        assertThat(cancelledJson.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelledJson.path("cancellationRequested").asBoolean()).isTrue();
        assertThat(cancelledJson.path("steps")).hasSize(StepType.values().length);
        assertThat(cancelledJson.path("steps").findValuesAsText("status"))
                .containsOnly("CANCELLED");

        HttpResponse<String> deleted = request(
                "DELETE",
                "/api/v1/research/" + researchId,
                null,
                true,
                Map.of()
        );
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();

        HttpResponse<String> repeatedDelete = request(
                "DELETE",
                "/api/v1/research/" + researchId,
                null,
                true,
                Map.of()
        );
        assertThat(repeatedDelete.statusCode()).isEqualTo(204);

        HttpResponse<String> hiddenAfterDelete = request(
                "GET",
                "/api/v1/research/" + researchId,
                null,
                true,
                Map.of()
        );
        assertProblem(hiddenAfterDelete, 404, "RESEARCH_NOT_FOUND");

        assertRetainedLifecycleEvidence(researchId);
    }

    private void createFailedResearchAndRetryThroughHttp() throws Exception {
        HttpResponse<String> createdForRetry = request(
                "POST",
                "/api/v1/research",
                CHANGED_CREATE_JSON,
                true,
                Map.of("Idempotency-Key", "http-it-retry-create-001")
        );
        assertThat(createdForRetry.statusCode()).isEqualTo(202);
        UUID researchId = UUID.fromString(json(createdForRetry).path("researchId").asText());
        createdResearchIds.add(researchId);

        assertThat(jdbcTemplate.update("""
                update research_steps
                   set priority = ?, row_version = row_version + 1
                 where research_job_id = ?
                   and step_type = 'RESOLVE_SECURITY'
                   and status = 'PENDING'
                """, Integer.MAX_VALUE, researchId)).isOne();
        Map<String, Object> claim = jdbcTemplate.queryForMap("""
                select *
                  from queue_v1.claim_step(
                      cast(? as varchar), ARRAY['RESOLVE_SECURITY']::varchar[], 60
                  )
                """, "research-http-it-worker-" + UUID.randomUUID());
        assertThat(claim)
                .containsEntry("result_code", "CLAIMED")
                .containsEntry("research_job_id", researchId)
                .containsEntry("step_type", "RESOLVE_SECURITY");

        Map<String, Object> failed = jdbcTemplate.queryForMap("""
                select *
                  from queue_v1.fail_step(
                      ?, ?, false, cast(? as varchar), cast(? as varchar), 1, 1
                  )
                """,
                claim.get("attempt_id"),
                claim.get("lease_token"),
                "HTTP_IT_FAILURE",
                "Deterministic HTTP integration failure"
        );
        assertThat(failed)
                .containsEntry("result_code", "FAILED")
                .containsEntry("research_job_id", researchId)
                .containsEntry("step_status", "FAILED");
        assertThat(workflowService.finalizeResearch(researchId, false, false).status().name())
                .isEqualTo("FAILED");

        HttpResponse<String> retried = request(
                "POST",
                "/api/v1/research/" + researchId + "/retry",
                "{}",
                true,
                Map.of("Idempotency-Key", "http-it-retry-failed-001")
        );
        assertThat(retried.statusCode()).isEqualTo(202);
        assertThat(header(retried, "Idempotency-Replayed")).contains("false");
        JsonNode retriedJson = json(retried);
        assertThat(retriedJson.path("researchId").asText()).isEqualTo(researchId.toString());
        assertThat(retriedJson.path("status").asText()).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_jobs where id = ?",
                String.class,
                researchId
        )).isEqualTo("QUEUED");
    }

    private UUID createForeignOwnedResearch() {
        UUID ownerId = UUID.randomUUID();
        createdOwnerIds.add(ownerId);
        var result = commandService.create(
                ownerId,
                "http-it-foreign-" + ownerId,
                "http-it-foreign-" + ownerId + "@local.invalid",
                "http-it-foreign-create-" + ownerId,
                new CreateResearchCommand(
                        "Analyze a foreign owner's Microsoft research request",
                        "MSFT",
                        null,
                        ResearchLocale.EN_US,
                        "SPY",
                        ResearchPeriod.FIVE_YEARS,
                        null,
                        null,
                        ReportDepth.STANDARD,
                        true,
                        true,
                        true
                )
        );
        UUID researchId = result.value().researchId();
        createdResearchIds.add(researchId);
        return researchId;
    }

    private void assertRetainedLifecycleEvidence(UUID researchId) {
        Map<String, Object> physicalResearch = jdbcTemplate.queryForMap("""
                select user_id, status, cancellation_requested, deleted_at, deleted_by, delete_reason
                  from research_jobs
                 where id = ?
                """, researchId);
        assertThat(physicalResearch)
                .containsEntry("user_id", DEMO_OWNER_ID)
                .containsEntry("status", "CANCELLED")
                .containsEntry("cancellation_requested", true)
                .containsEntry("deleted_by", DEMO_OWNER_ID)
                .containsEntry("delete_reason", "USER_REQUESTED");
        assertThat(physicalResearch.get("deleted_at")).isNotNull();

        assertThat(count("select count(*) from research_steps where research_job_id = ?", researchId))
                .isEqualTo(StepType.values().length);
        assertThat(count("""
                select count(*) from research_steps
                 where research_job_id = ? and status = 'CANCELLED'
                """, researchId)).isEqualTo(StepType.values().length);
        assertThat(jdbcTemplate.queryForList("""
                select action from audit_events
                 where research_job_id = ?
                 order by occurred_at, id
                """, String.class, researchId))
                .contains("RESEARCH_CREATED", "CANCEL_REQUESTED", "SOFT_DELETED");
        assertThat(jdbcTemplate.queryForList("""
                select event_type from outbox_events
                 where aggregate_type = 'RESEARCH' and aggregate_id = ?
                 order by occurred_at, id
                """, String.class, researchId))
                .contains(
                        "RESEARCH_QUEUED",
                        "RESEARCH_CANCELLATION_REQUESTED",
                        "RESEARCH_SOFT_DELETED"
                );
        assertThat(count("""
                select count(*) from idempotency_records
                 where user_id = ? and resource_id = ? and status = 'COMPLETED'
                """, DEMO_OWNER_ID, researchId)).isEqualTo(2);
    }

    private HttpResponse<String> request(
            String method,
            String path,
            String body,
            boolean authenticated,
            Map<String, String> headers
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json, application/problem+json");
        if (authenticated) {
            String credentials = DEMO_USERNAME + ":" + DEMO_PASSWORD;
            builder.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                            credentials.getBytes(StandardCharsets.UTF_8)
                    )
            );
        }
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private void assertProblem(
            HttpResponse<String> response,
            int expectedStatus,
            String expectedCode
    ) throws Exception {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(header(response, "Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        JsonNode problem = json(response);
        assertThat(problem.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(problem.path("code").asText()).isEqualTo(expectedCode);
        assertThat(problem.path("requestId").asText()).isNotBlank();
    }

    private static Optional<String> header(
            HttpResponse<String> response,
            String name
    ) {
        return response.headers().firstValue(name);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private void deleteOnlyTrackedRows() {
        Set<UUID> cleanupResearchIds = new LinkedHashSet<>(createdResearchIds);
        for (UUID ownerId : createdOwnerIds) {
            cleanupResearchIds.addAll(jdbcTemplate.queryForList(
                    "select id from research_jobs where user_id = ?",
                    UUID.class,
                    ownerId
            ));
        }

        for (UUID researchId : cleanupResearchIds) {
            jdbcTemplate.update("""
                    delete from outbox_events
                     where aggregate_id = ?
                        or aggregate_id in (
                            select id from research_steps where research_job_id = ?
                        )
                        or payload_json ->> 'researchJobId' = ?
                    """, researchId, researchId, researchId.toString());
            jdbcTemplate.update("delete from audit_events where research_job_id = ?", researchId);
            jdbcTemplate.update("delete from idempotency_records where resource_id = ?", researchId);
            jdbcTemplate.update("""
                    delete from step_attempts
                     where research_step_id in (
                         select id from research_steps where research_job_id = ?
                     )
                    """, researchId);
            jdbcTemplate.update("delete from research_steps where research_job_id = ?", researchId);
            jdbcTemplate.update("delete from research_jobs where id = ?", researchId);
        }

        for (UUID ownerId : createdOwnerIds) {
            jdbcTemplate.update("delete from idempotency_records where user_id = ?", ownerId);
            jdbcTemplate.update("delete from audit_events where actor_user_id = ?", ownerId);
            jdbcTemplate.update("delete from users where id = ?", ownerId);
        }
        createdResearchIds.clear();
        createdOwnerIds.clear();
    }

    private static UUID stableOwnerId(String username) {
        return UUID.nameUUIDFromBytes(
                ("ai-quant-research:user:v1:" + username.toLowerCase())
                        .getBytes(StandardCharsets.UTF_8)
        );
    }
}
