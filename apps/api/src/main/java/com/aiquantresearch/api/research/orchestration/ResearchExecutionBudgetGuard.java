package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.persistence.ResearchJobRepository;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.aiquantresearch.api.research.worker.WorkerProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class ResearchExecutionBudgetGuard {

    static final String EXCEEDED_CODE = "RESEARCH_EXECUTION_BUDGET_EXCEEDED";

    private final ResearchJobRepository researchJobs;
    private final WorkerProperties properties;
    private final Clock clock;

    public ResearchExecutionBudgetGuard(
            ResearchJobRepository researchJobs,
            WorkerProperties properties,
            Clock clock
    ) {
        this.researchJobs = researchJobs;
        this.properties = properties;
        this.clock = clock;
    }

    public void assertWithinBudget(UUID researchId) {
        var research = researchJobs.findById(researchId).orElseThrow(() ->
                new StepExecutionException(
                        "RESEARCH_NOT_FOUND",
                        "The research job is unavailable",
                        false
                )
        );
        Instant startedAt = research.getStartedAt();
        if (startedAt == null) {
            return;
        }
        Instant deadline = startedAt.plus(properties.maxExecutionDuration());
        if (!clock.instant().isBefore(deadline)) {
            throw new StepExecutionException(
                    EXCEEDED_CODE,
                    "Research exceeded the configured maximum execution time",
                    false
            );
        }
    }
}
