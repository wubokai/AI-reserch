package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.application.ResearchWorkflowService;
import com.aiquantresearch.api.research.worker.ActiveLeaseRegistry;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.research.worker.ResearchStepProcessor;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class DefaultResearchStepProcessor implements ResearchStepProcessor {

    private final ResearchWorkflowService workflowService;
    private final Phase3StepExecutor stepExecutor;
    private final StepCommitService commitService;
    private final ActiveLeaseRegistry activeLeases;

    public DefaultResearchStepProcessor(
            ResearchWorkflowService workflowService,
            Phase3StepExecutor stepExecutor,
            StepCommitService commitService,
            ActiveLeaseRegistry activeLeases
    ) {
        this.workflowService = workflowService;
        this.stepExecutor = stepExecutor;
        this.commitService = commitService;
        this.activeLeases = activeLeases;
    }

    @Override
    public void process(QueueClaim claim) {
        assertLeaseUsable(claim);
        workflowService.projectStage(claim.researchJobId(), claim.stepType());
        assertLeaseUsable(claim);
        StepExecutionResult result = stepExecutor.execute(claim);
        assertLeaseUsable(claim);
        commitService.commit(claim, result);
    }

    private void assertLeaseUsable(QueueClaim claim) {
        if (activeLeases.stale(claim.attemptId())) {
            throw new StepExecutionException(
                    "STALE_WORKER_LEASE",
                    "The worker lease expired before the result could be committed",
                    true
            );
        }
        if (activeLeases.cancellationRequested(claim.attemptId())) {
            throw new StepExecutionException(
                    "RESEARCH_CANCELLATION_REQUESTED",
                    "Research cancellation was requested",
                    false
            );
        }
    }
}
