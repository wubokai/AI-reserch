package com.aiquantresearch.api.research.persistence;

import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchStepRepository extends JpaRepository<ResearchStepEntity, UUID> {

    List<ResearchStepEntity> findAllByResearchJobIdOrderBySequenceNoAsc(UUID researchJobId);

    Optional<ResearchStepEntity> findByResearchJobIdAndStepType(UUID researchJobId, StepType stepType);

    @Query("""
            select step
            from ResearchStepEntity step, ResearchJobEntity research
            where step.researchJobId = research.id
              and research.id = :researchId
              and research.ownerId = :ownerId
              and research.deletedAt is null
            order by step.sequenceNo
            """)
    List<ResearchStepEntity> findAllOwned(
            @Param("researchId") UUID researchId,
            @Param("ownerId") UUID ownerId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select step
            from ResearchStepEntity step
            where step.researchJobId = :researchId
            order by step.sequenceNo
            """)
    List<ResearchStepEntity> findAllByResearchJobIdForUpdate(@Param("researchId") UUID researchId);

    long countByResearchJobIdAndStatus(UUID researchJobId, StepStatus status);

    boolean existsByResearchJobIdAndStatusIn(UUID researchJobId, List<StepStatus> statuses);
}
