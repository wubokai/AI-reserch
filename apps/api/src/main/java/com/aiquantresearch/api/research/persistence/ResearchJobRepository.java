package com.aiquantresearch.api.research.persistence;

import com.aiquantresearch.api.research.domain.ResearchStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchJobRepository extends JpaRepository<ResearchJobEntity, UUID> {

    Optional<ResearchJobEntity> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

    Optional<ResearchJobEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select research
            from ResearchJobEntity research
            where research.id = :id
              and research.ownerId = :ownerId
              and research.deletedAt is null
            """)
    Optional<ResearchJobEntity> findOwnedForUpdate(
            @Param("id") UUID id,
            @Param("ownerId") UUID ownerId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select research
            from ResearchJobEntity research
            where research.id = :id
              and research.deletedAt is null
            """)
    Optional<ResearchJobEntity> findActiveForUpdate(@Param("id") UUID id);

    @Query("""
            select research
            from ResearchJobEntity research
            where research.ownerId = :ownerId
              and research.deletedAt is null
              and (:symbolFilter = false or research.symbolInput = :symbol)
              and (:statusFilter = false or research.status = :status)
              and (:fromFilter = false or research.createdAt >= :fromInstant)
              and (:toFilter = false or research.createdAt < :toInstant)
              and (:queryFilter = false
                   or lower(research.query) like lower(concat('%', :queryText, '%'))
                   or lower(research.symbolInput) like lower(concat('%', :queryText, '%'))
                   or lower(cast(function(
                       'jsonb_extract_path_text', research.requestJson, 'companyName'
                   ) as string)) like lower(concat('%', :queryText, '%')))
            """)
    Page<ResearchJobEntity> searchOwned(
            @Param("ownerId") UUID ownerId,
            @Param("symbolFilter") boolean symbolFilter,
            @Param("symbol") String symbol,
            @Param("statusFilter") boolean statusFilter,
            @Param("status") ResearchStatus status,
            @Param("fromFilter") boolean fromFilter,
            @Param("fromInstant") Instant fromInstant,
            @Param("toFilter") boolean toFilter,
            @Param("toInstant") Instant toInstant,
            @Param("queryFilter") boolean queryFilter,
            @Param("queryText") String queryText,
            Pageable pageable
    );
}
