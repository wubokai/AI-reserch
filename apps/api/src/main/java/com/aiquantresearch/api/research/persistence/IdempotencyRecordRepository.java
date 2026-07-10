package com.aiquantresearch.api.research.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into idempotency_records (
                id, user_id, http_method, request_path, idempotency_key, request_hash,
                status, expires_at, created_at, updated_at, row_version
            ) values (
                :id, :userId, :httpMethod, :requestPath, :idempotencyKey, :requestHash,
                'PROCESSING', :expiresAt, :now, :now, 0
            )
            on conflict (user_id, http_method, request_path, idempotency_key)
            do update set
                id = excluded.id,
                request_hash = excluded.request_hash,
                status = 'PROCESSING',
                response_status = null,
                response_body = null,
                resource_id = null,
                expires_at = excluded.expires_at,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                row_version = idempotency_records.row_version + 1
            where idempotency_records.expires_at <= excluded.created_at
            """, nativeQuery = true)
    int reserve(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("httpMethod") String httpMethod,
            @Param("requestPath") String requestPath,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now
    );

    Optional<IdempotencyRecordEntity> findByUserIdAndHttpMethodAndRequestPathAndIdempotencyKey(
            UUID userId,
            String httpMethod,
            String requestPath,
            String idempotencyKey
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update idempotency_records
               set status = 'COMPLETED',
                   response_status = :responseStatus,
                   response_body = cast(:responseBody as jsonb),
                   resource_id = :resourceId,
                   updated_at = :now,
                   row_version = row_version + 1
             where id = :id
               and status = 'PROCESSING'
            """, nativeQuery = true)
    int complete(
            @Param("id") UUID id,
            @Param("responseStatus") short responseStatus,
            @Param("responseBody") String responseBody,
            @Param("resourceId") UUID resourceId,
            @Param("now") Instant now
    );
}
