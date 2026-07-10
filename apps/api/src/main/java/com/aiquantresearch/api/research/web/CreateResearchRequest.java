package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.CreateResearchCommand;
import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchPeriod;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateResearchRequest(
        @NotBlank @Size(min = 10, max = 4000) String query,
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9.-]{0,9}$") String symbol,
        @Size(min = 1, max = 200) String companyName,
        ResearchLocale locale,
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9.-]{0,9}$") String benchmark,
        ResearchPeriod period,
        LocalDate startDate,
        LocalDate endDate,
        ReportDepth reportDepth,
        Boolean includeTechnicalAnalysis,
        Boolean includeFundamentalAnalysis,
        Boolean includeMacroAnalysis
) {

    @JsonIgnore
    @AssertTrue(message = "symbol or companyName is required")
    public boolean isSecuritySpecified() {
        return hasText(symbol) || hasText(companyName);
    }

    public CreateResearchCommand toCommand() {
        return new CreateResearchCommand(
                query,
                symbol,
                companyName,
                locale,
                benchmark,
                period,
                startDate,
                endDate,
                reportDepth,
                includeTechnicalAnalysis == null || includeTechnicalAnalysis,
                includeFundamentalAnalysis == null || includeFundamentalAnalysis,
                includeMacroAnalysis == null || includeMacroAnalysis
        );
    }

    public static CreateResearchRequest fromCommand(CreateResearchCommand command) {
        return new CreateResearchRequest(
                command.query(),
                command.symbol(),
                command.companyName(),
                command.locale(),
                command.benchmark(),
                command.period(),
                command.startDate(),
                command.endDate(),
                command.reportDepth(),
                command.includeTechnicalAnalysis(),
                command.includeFundamentalAnalysis(),
                command.includeMacroAnalysis()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
