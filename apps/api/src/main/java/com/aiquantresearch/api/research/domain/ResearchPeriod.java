package com.aiquantresearch.api.research.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum ResearchPeriod {
    ONE_YEAR("1y"),
    THREE_YEARS("3y"),
    FIVE_YEARS("5y"),
    TEN_YEARS("10y"),
    MAX("max");

    private final String value;

    ResearchPeriod(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ResearchPeriod fromValue(String value) {
        return Arrays.stream(values())
                .filter(period -> period.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported period: " + value));
    }
}
