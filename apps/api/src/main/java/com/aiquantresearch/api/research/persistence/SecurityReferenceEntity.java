package com.aiquantresearch.api.research.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of the local security master used by the synchronous API precheck.
 * Durable provider-backed resolution remains the responsibility of RESOLVE_SECURITY.
 */
@Entity
@Immutable
@Table(name = "securities")
public class SecurityReferenceEntity {

    @Id
    private UUID id;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 255)
    private String companyName;

    @Column(name = "security_type", nullable = false, length = 32)
    private String securityType;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected SecurityReferenceEntity() {
        // Required by JPA.
    }

    public UUID getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getSecurityType() {
        return securityType;
    }

    public boolean isActive() {
        return active;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
