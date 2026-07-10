package com.aiquantresearch.api.research.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityReferenceRepository
        extends JpaRepository<SecurityReferenceEntity, UUID> {

    List<SecurityReferenceEntity> findAllBySymbolIgnoreCase(String symbol);

    List<SecurityReferenceEntity> findAllByCompanyNameIgnoreCase(String companyName);
}
