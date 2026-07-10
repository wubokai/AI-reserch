package com.aiquantresearch.api.research.application;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.persistence.SecurityReferenceEntity;
import com.aiquantresearch.api.research.persistence.SecurityReferenceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SecurityPrecheckServiceTest {

    @Mock
    private SecurityReferenceRepository repository;

    private SecurityPrecheckService service;

    @BeforeEach
    void setUp() {
        service = new SecurityPrecheckService(repository);
    }

    @Test
    void acceptsActiveSupportedLocalSecurityWithMatchingCompany() {
        SecurityReferenceEntity micron = security(
                "MU",
                "Micron Technology, Inc.",
                true,
                "COMMON_STOCK"
        );
        when(repository.findAllBySymbolIgnoreCase("MU"))
                .thenReturn(List.of(micron));
        when(repository.findAllByCompanyNameIgnoreCase("Micron Technology, Inc."))
                .thenReturn(List.of(micron));

        assertThatNoException().isThrownBy(() ->
                service.validate("MU", "Micron Technology, Inc."));
    }

    @Test
    void rejectsInactiveOrUnsupportedKnownSymbol() {
        SecurityReferenceEntity inactive = security(
                "OLD",
                "Old Corp",
                false,
                "COMMON_STOCK"
        );
        SecurityReferenceEntity unsupported = security(
                "BOND",
                "Bond Fund",
                true,
                "BOND"
        );
        when(repository.findAllBySymbolIgnoreCase("OLD"))
                .thenReturn(List.of(inactive));
        when(repository.findAllBySymbolIgnoreCase("BOND"))
                .thenReturn(List.of(unsupported));

        assertThatThrownBy(() -> service.validate("OLD", null))
                .isInstanceOf(InvalidSymbolException.class)
                .hasMessageContaining("inactive or unsupported");
        assertThatThrownBy(() -> service.validate("BOND", null))
                .isInstanceOf(InvalidSymbolException.class);
    }

    @Test
    void rejectsCompanyMismatchForKnownTicker() {
        SecurityReferenceEntity micron = security(
                "MU",
                "Micron Technology",
                true,
                "COMMON_STOCK"
        );
        SecurityReferenceEntity nvidia = security(
                "NVDA",
                "NVIDIA Corporation",
                true,
                "COMMON_STOCK"
        );
        when(repository.findAllBySymbolIgnoreCase("MU"))
                .thenReturn(List.of(micron));
        when(repository.findAllByCompanyNameIgnoreCase("NVIDIA Corporation"))
                .thenReturn(List.of(nvidia));

        assertThatThrownBy(() -> service.validate("MU", "NVIDIA Corporation"))
                .isInstanceOf(SecurityMismatchException.class)
                .hasMessage("The supplied symbol and companyName resolve to different securities");
    }

    @Test
    void unknownTickerPairedWithKnownCompanyDefersToDurableResolution() {
        SecurityReferenceEntity nvidia = security(
                "NVDA",
                "NVIDIA Corporation",
                true,
                "COMMON_STOCK"
        );
        when(repository.findAllBySymbolIgnoreCase("NVDAA")).thenReturn(List.of());
        when(repository.findAllByCompanyNameIgnoreCase("NVIDIA Corporation"))
                .thenReturn(List.of(nvidia));

        assertThatNoException().isThrownBy(() ->
                service.validate("NVDAA", "NVIDIA Corporation"));
    }

    @Test
    void unresolvedCompanyAliasDoesNotCreateAFalseMismatch() {
        SecurityReferenceEntity nvidia = security(
                "NVDA",
                "NVIDIA Corporation",
                true,
                "COMMON_STOCK"
        );
        when(repository.findAllBySymbolIgnoreCase("NVDA")).thenReturn(List.of(nvidia));
        when(repository.findAllByCompanyNameIgnoreCase("NVIDIA")).thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> service.validate("NVDA", "NVIDIA"));
    }

    @Test
    void unknownValidSecurityDefersToDurableResolution() {
        when(repository.findAllBySymbolIgnoreCase("NEWCO")).thenReturn(List.of());
        when(repository.findAllByCompanyNameIgnoreCase("New Company")).thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> service.validate("NEWCO", "New Company"));
    }

    @Test
    void companyOnlyKnownInactiveSecurityIsRejected() {
        SecurityReferenceEntity legacy = security(
                "LEGACY",
                "Legacy Company",
                false,
                "COMMON_STOCK"
        );
        when(repository.findAllByCompanyNameIgnoreCase("Legacy Company"))
                .thenReturn(List.of(legacy));

        assertThatThrownBy(() -> service.validate(null, "Legacy Company"))
                .isInstanceOf(InvalidSymbolException.class);
    }

    private static SecurityReferenceEntity security(
            String symbol,
            String companyName,
            boolean active,
            String securityType
    ) {
        SecurityReferenceEntity security = mock(SecurityReferenceEntity.class);
        lenient().when(security.getId()).thenReturn(UUID.randomUUID());
        lenient().when(security.getSymbol()).thenReturn(symbol);
        lenient().when(security.getCompanyName()).thenReturn(companyName);
        lenient().when(security.isActive()).thenReturn(active);
        lenient().when(security.getSecurityType()).thenReturn(securityType);
        return security;
    }
}
