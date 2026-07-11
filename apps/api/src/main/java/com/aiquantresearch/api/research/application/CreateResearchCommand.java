package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchPeriod;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

public record CreateResearchCommand(
        String query,
        String symbol,
        String companyName,
        ResearchLocale locale,
        String benchmark,
        ResearchPeriod period,
        LocalDate startDate,
        LocalDate endDate,
        ReportDepth reportDepth,
        boolean includeTechnicalAnalysis,
        boolean includeFundamentalAnalysis,
        boolean includeMacroAnalysis
) {

    private static final Pattern SYMBOL = Pattern.compile("^[A-Z][A-Z0-9.-]{0,9}$");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]");

    public CreateResearchCommand {
        query = normalizeRequired(query, "query");
        if (query.length() < 10 || query.length() > 4000) {
            throw new InvalidResearchRequestException("query must contain 10-4000 characters");
        }
        symbol = normalizeSymbol(symbol, "symbol", false);
        companyName = normalizeNullable(companyName);
        if (companyName != null && companyName.length() > 200) {
            throw new InvalidResearchRequestException("companyName must not exceed 200 characters");
        }
        if (symbol == null && companyName == null) {
            throw new InvalidResearchRequestException("symbol or companyName is required");
        }
        locale = locale == null ? inferLocale(query) : locale;
        benchmark = normalizeSymbol(benchmark == null ? "SPY" : benchmark, "benchmark", true);
        period = period == null ? ResearchPeriod.FIVE_YEARS : period;
        reportDepth = reportDepth == null ? ReportDepth.STANDARD : reportDepth;
        if ((startDate == null) != (endDate == null)) {
            throw new InvalidResearchRequestException("startDate and endDate must be provided together");
        }
        if (startDate != null && startDate.isAfter(endDate)) {
            throw new InvalidResearchRequestException("startDate must not be after endDate");
        }
    }

    private static ResearchLocale inferLocale(String query) {
        return CJK.matcher(query).find() ? ResearchLocale.ZH_CN : ResearchLocale.EN_US;
    }

    private static String normalizeSymbol(String value, String field, boolean required) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            if (required) {
                throw new InvalidResearchRequestException(field + " is required");
            }
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!SYMBOL.matcher(normalized).matches()) {
            throw new InvalidResearchRequestException(field + " has an invalid ticker format");
        }
        return normalized;
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new InvalidResearchRequestException(field + " is required");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public CreateResearchCommand withResolvedSecurity(
            String resolvedSymbol,
            String resolvedCompanyName
    ) {
        return new CreateResearchCommand(
                query,
                resolvedSymbol,
                resolvedCompanyName,
                locale,
                benchmark,
                period,
                startDate,
                endDate,
                reportDepth,
                includeTechnicalAnalysis,
                includeFundamentalAnalysis,
                includeMacroAnalysis
        );
    }
}
