package com.aiquantresearch.api.research.persistence;

import com.aiquantresearch.api.research.domain.UserRole;
import com.aiquantresearch.api.research.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id;

    @Column(name = "email", columnDefinition = "citext")
    private String email;

    @Column(name = "auth_provider", length = 64)
    private String authProvider;

    @Column(name = "auth_subject", length = 255)
    private String authSubject;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected UserEntity() {
        // Required by JPA.
    }

    public static UserEntity localPrincipal(UUID id, String email, Instant now) {
        var entity = new UserEntity();
        entity.id = Objects.requireNonNull(id, "id");
        entity.email = normalizeEmail(email);
        entity.role = UserRole.USER;
        entity.status = UserStatus.ACTIVE;
        entity.createdAt = Objects.requireNonNull(now, "now");
        entity.updatedAt = now;
        return entity;
    }

    public static UserEntity externalPrincipal(
            UUID id,
            String email,
            String provider,
            String subject,
            Instant now
    ) {
        var entity = localPrincipal(id, email, now);
        entity.authProvider = requireText(provider, "provider");
        entity.authSubject = requireText(subject, "subject");
        return entity;
    }

    private static String normalizeEmail(String value) {
        String normalized = requireText(value, "email").trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains("@") || normalized.length() > 320) {
            throw new IllegalArgumentException("email must be a valid normalized address");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getAuthProvider() {
        return authProvider;
    }

    public String getAuthSubject() {
        return authSubject;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
