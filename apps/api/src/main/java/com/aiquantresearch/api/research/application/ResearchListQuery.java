package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.ResearchStatus;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

public record ResearchListQuery(
        int page,
        int size,
        String symbol,
        ResearchStatus status,
        Instant from,
        Instant to,
        String query,
        ResearchSort sort
) {

    private static final Pattern SYMBOL = Pattern.compile("^[A-Z][A-Z0-9.-]{0,9}$");

    public ResearchListQuery {
        if (page < 0) {
            throw new InvalidResearchRequestException("page must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new InvalidResearchRequestException("size must be between 1 and 100");
        }
        symbol = normalizeNullable(symbol);
        if (symbol != null) {
            symbol = symbol.toUpperCase(Locale.ROOT);
            if (!SYMBOL.matcher(symbol).matches()) {
                throw new InvalidResearchRequestException("symbol has an invalid ticker format");
            }
        }
        query = normalizeNullable(query);
        if (query != null && query.length() > 200) {
            throw new InvalidResearchRequestException("q must not exceed 200 characters");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidResearchRequestException("from must be before to");
        }
        sort = sort == null ? ResearchSort.CREATED_AT_DESC : sort;
    }

    public static ResearchListQuery firstPage() {
        return new ResearchListQuery(0, 20, null, null, null, null, null, ResearchSort.CREATED_AT_DESC);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
