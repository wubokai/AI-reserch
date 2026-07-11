package com.aiquantresearch.api.research.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.application.ResearchWorkflowService;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.worker.ActiveLeaseRegistry;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultResearchStepProcessorTest {

    @Mock
    private ResearchWorkflowService workflowService;
    @Mock
    private Phase3StepExecutor stepExecutor;
    @Mock
    private StepCommitService commitService;
    @Mock
    private ActiveLeaseRegistry activeLeases;
    @Mock
    private ResearchExecutionBudgetGuard executionBudget;

    private DefaultResearchStepProcessor processor;
    private QueueClaim claim;
    private StepExecutionResult result;

    @BeforeEach
    void setUp() {
        processor = new DefaultResearchStepProcessor(
                workflowService,
                stepExecutor,
                commitService,
                activeLeases,
                executionBudget
        );
        claim = new QueueClaim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                Instant.parse("2026-07-11T12:01:00Z"),
                StepType.FETCH_MARKET_DATA,
                "a".repeat(64),
                "test-v1",
                1,
                JsonNodeFactory.instance.objectNode()
        );
        result = StepExecutionResult.complete(JsonNodeFactory.instance.objectNode());
    }

    @Test
    void checksTheGlobalDeadlineBeforeProjectionExecutionAndCommit() {
        when(stepExecutor.execute(claim)).thenReturn(result);

        processor.process(claim);

        InOrder order = inOrder(executionBudget, workflowService, stepExecutor, commitService);
        order.verify(executionBudget).assertWithinBudget(claim.researchJobId());
        order.verify(workflowService).projectStage(claim.researchJobId(), claim.stepType());
        order.verify(executionBudget).assertWithinBudget(claim.researchJobId());
        order.verify(stepExecutor).execute(claim);
        order.verify(executionBudget).assertWithinBudget(claim.researchJobId());
        order.verify(commitService).commit(claim, result);
    }

    @Test
    void doesNotCommitAResultThatReturnsAfterTheDeadline() {
        when(stepExecutor.execute(claim)).thenReturn(result);
        org.mockito.Mockito.doNothing()
                .doNothing()
                .doThrow(new StepExecutionException(
                        ResearchExecutionBudgetGuard.EXCEEDED_CODE,
                        "Research exceeded the configured maximum execution time",
                        false
                ))
                .when(executionBudget).assertWithinBudget(claim.researchJobId());

        assertThatThrownBy(() -> processor.process(claim))
                .isInstanceOfSatisfying(StepExecutionException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(ResearchExecutionBudgetGuard.EXCEEDED_CODE)
                );

        verify(commitService, never()).commit(claim, result);
    }
}
