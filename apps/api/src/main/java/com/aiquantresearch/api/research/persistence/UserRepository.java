package com.aiquantresearch.api.research.persistence;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into users (id, email, role, status, created_at, updated_at, row_version)
            values (:id, :email, 'USER', 'ACTIVE', :now, :now, 0)
            on conflict (id) do nothing
            """, nativeQuery = true)
    int ensureLocalPrincipal(
            @Param("id") UUID id,
            @Param("email") String email,
            @Param("now") Instant now
    );
}
