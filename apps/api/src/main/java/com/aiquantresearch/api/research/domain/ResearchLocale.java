package com.aiquantresearch.api.research.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum ResearchLocale {
    ZH_CN("zh-CN"),
    EN_US("en-US");

    private final String value;

    ResearchLocale(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ResearchLocale fromValue(String value) {
        return Arrays.stream(values())
                .filter(locale -> locale.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported locale: " + value));
    }
}
