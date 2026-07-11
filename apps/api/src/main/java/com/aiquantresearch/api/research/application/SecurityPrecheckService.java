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
    public ResolvedSecurityInput resolve(String symbol, String companyName) {
        if (symbol != null) {
            List<SecurityReferenceEntity> symbolMatches =
                    repository.findAllBySymbolIgnoreCase(symbol);
            List<SecurityReferenceEntity> usableSymbols = usable(symbolMatches);
            if (!symbolMatches.isEmpty()) {
                if (usableSymbols.isEmpty()) {
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
                if (!usableCompanies.isEmpty()
                        && (usableSymbols.isEmpty()
                        || disjointUsableIds(usableSymbols, usableCompanies))) {
                    throw mismatch();
                }
            }
            // A valid unresolved alias is not a provable contradiction. Defer it
            // to the durable provider-backed resolver when no known company conflicts.
            return new ResolvedSecurityInput(symbol, companyName);
        }

        List<SecurityReferenceEntity> companyMatches =
                repository.findAllByCompanyNameIgnoreCase(companyName);
        List<SecurityReferenceEntity> usableCompanies = usable(companyMatches);
        if (!companyMatches.isEmpty() && usableCompanies.isEmpty()) {
            throw invalidSymbol();
        }
        List<SecurityReferenceEntity> distinct = usableCompanies.stream()
                .collect(java.util.stream.Collectors.toMap(
                        SecurityReferenceEntity::getId,
                        value -> value,
                        (first, ignored) -> first
                ))
                .values().stream().toList();
        if (distinct.isEmpty()) {
            throw new InvalidSymbolException(
                    "The company name could not be resolved in the local security master"
            );
        }
        if (distinct.size() != 1) {
            throw new InvalidSymbolException(
                    "The company name resolves to multiple active securities"
            );
        }
        SecurityReferenceEntity resolved = distinct.getFirst();
        return new ResolvedSecurityInput(resolved.getSymbol(), resolved.getCompanyName());
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

    public record ResolvedSecurityInput(String symbol, String companyName) {
    }
}
