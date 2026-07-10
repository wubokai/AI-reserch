package com.aiquantresearch.api.research.application;

public enum ResearchSort {
    CREATED_AT_DESC("createdAt", false),
    CREATED_AT_ASC("createdAt", true),
    UPDATED_AT_DESC("updatedAt", false),
    UPDATED_AT_ASC("updatedAt", true);

    private final String property;
    private final boolean ascending;

    ResearchSort(String property, boolean ascending) {
        this.property = property;
        this.ascending = ascending;
    }

    public String property() {
        return property;
    }

    public boolean ascending() {
        return ascending;
    }

    public static ResearchSort fromApiValue(String value) {
        if (value == null || value.isBlank() || "createdAt,desc".equals(value)) {
            return CREATED_AT_DESC;
        }
        return switch (value) {
            case "createdAt,asc" -> CREATED_AT_ASC;
            case "updatedAt,desc" -> UPDATED_AT_DESC;
            case "updatedAt,asc" -> UPDATED_AT_ASC;
            default -> throw new InvalidResearchRequestException("Unsupported research sort: " + value);
        };
    }
}
