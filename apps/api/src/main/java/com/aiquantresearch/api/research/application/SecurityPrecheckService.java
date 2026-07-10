package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.persistence.SecurityReferenceEntity;
import com.aiquantresearch.api.research.persistence.SecurityReferenceRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rejects contradictions that can be proven from the local security master without
 * making request-thread network calls. An unknown, well-formed security deliberately
 * passes through so the durable RESOLVE_SECURITY step can consult Phase 3 providers.
 */
@Service
public class SecurityPrecheckService {

    private static final Set<String> SUPPORTED_TYPES = Set.of("COMMON_STOCK", "ETF");

    private final SecurityReferenceRepository repository;

    public SecurityPrecheckService(SecurityReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public void validate(String symbol, String companyName) {
        if (symbol != null) {
            List<SecurityReferenceEntity> symbolMatches =
                    repository.findAllBySymbolIgnoreCase(symbol);
            if (!symbolMatches.isEmpty()) {
                if (usable(symbolMatches).isEmpty()) {
                    throw invalidSymbol();
                }
            }

            if (companyName != null) {
                List<SecurityReferenceEntity> companyMatches =
                        repository.findAllByCompanyNameIgnoreCase(companyName);
                List<SecurityReferenceEntity> usableCompanies = usable(companyMatches);
                if (!companyMatches.isEmpty() && usableCompanies.isEmpty()) {
                    throw invalidSymbol();
                }
                if (!symbolMatches.isEmpty()
                        && !usableCompanies.isEmpty()
                        && disjointUsableIds(symbolMatches, usableCompanies)) {
                    throw mismatch();
                }
            }
            // A valid alias or an unknown ticker is not a provable contradiction.
            // Defer it to the durable provider-backed resolver.
            return;
        }

        List<SecurityReferenceEntity> companyMatches =
                repository.findAllByCompanyNameIgnoreCase(companyName);
        if (!companyMatches.isEmpty() && usable(companyMatches).isEmpty()) {
            throw invalidSymbol();
        }
    }

    private static List<SecurityReferenceEntity> usable(
            List<SecurityReferenceEntity> candidates
    ) {
        return candidates.stream()
                .filter(SecurityReferenceEntity::isActive)
                .filter(security -> SUPPORTED_TYPES.contains(security.getSecurityType()))
                .toList();
    }

    private static boolean disjointUsableIds(
            List<SecurityReferenceEntity> symbolMatches,
            List<SecurityReferenceEntity> usableCompanies
    ) {
        Set<java.util.UUID> symbolIds = usable(symbolMatches).stream()
                .map(SecurityReferenceEntity::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return usableCompanies.stream()
                .map(SecurityReferenceEntity::getId)
                .noneMatch(symbolIds::contains);
    }

    private static InvalidSymbolException invalidSymbol() {
        return new InvalidSymbolException(
                "The locally known security is inactive or unsupported"
        );
    }

    private static SecurityMismatchException mismatch() {
        return new SecurityMismatchException(
                "The supplied symbol and companyName resolve to different securities"
        );
    }
}
