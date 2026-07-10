package com.aiquantresearch.api.research.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StepAttemptRepository extends JpaRepository<StepAttemptEntity, UUID> {

    List<StepAttemptEntity> findAllByResearchStepIdInOrderByResearchStepIdAscAttemptNumberAsc(
            Collection<UUID> researchStepIds
    );

    Optional<StepAttemptEntity> findFirstByResearchStepIdOrderByAttemptNumberDesc(UUID stepId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from StepAttemptEntity attempt where attempt.id = :id")
    Optional<StepAttemptEntity> findByIdForUpdate(@Param("id") UUID id);
}
