package com.aiquantresearch.api.research.worker;

import com.aiquantresearch.api.research.application.InvalidStateTransitionException;
import com.aiquantresearch.api.research.application.ResearchWorkflowService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class ResearchSettlementScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResearchSettlementScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final ResearchWorkflowService workflowService;

    public ResearchSettlementScheduler(
            JdbcTemplate jdbcTemplate,
            ResearchWorkflowService workflowService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workflowService = workflowService;
    }

    @Scheduled(fixedDelayString = "1s", initialDelayString = "1s")
    public void settle() {
        var candidates = jdbcTemplate.queryForList("""
                select distinct r.id
                  from research_jobs r
                 where r.deleted_at is null
                   and r.status not in ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
                   and (
                       r.cancellation_requested
                       or exists (
                           select 1 from research_steps s
                            where s.research_job_id = r.id and s.status = 'FAILED'
                       )
                   )
                   and not exists (
                       select 1 from research_steps s
                        where s.research_job_id = r.id and s.status = 'RUNNING'
                   )
                 order by r.id
                 limit 100
                """, UUID.class);
        for (UUID researchId : candidates) {
            try {
                Boolean cancellation = jdbcTemplate.queryForObject(
                        "select cancellation_requested from research_jobs where id = ?",
                        Boolean.class,
                        researchId
                );
                if (Boolean.TRUE.equals(cancellation)) {
                    workflowService.confirmCancellationIfSettled(researchId);
                } else {
                    workflowService.finalizeResearch(researchId, false, false);
                }
            } catch (InvalidStateTransitionException exception) {
                LOGGER.debug("Research settlement deferred researchId={}", researchId, exception);
            } catch (RuntimeException exception) {
                LOGGER.warn("Research settlement failed researchId={}", researchId, exception);
            }
        }
    }
}
