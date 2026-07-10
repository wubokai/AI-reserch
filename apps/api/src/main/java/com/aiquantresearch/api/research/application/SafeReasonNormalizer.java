package com.aiquantresearch.api.research.application;

/** Normalizes user-supplied audit reasons without copying control characters into logs/events. */
final class SafeReasonNormalizer {

    static final String USER_REQUESTED = "USER_REQUESTED";
    private static final int MAX_LENGTH = 500;

    private SafeReasonNormalizer() {
    }

    static String nullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
        boolean previousWasSeparator = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean separator = Character.isWhitespace(character)
                    || Character.isISOControl(character);
            if (separator) {
                if (!previousWasSeparator && !normalized.isEmpty()) {
                    normalized.append(' ');
                }
                previousWasSeparator = true;
            } else {
                normalized.append(character);
                previousWasSeparator = false;
            }
            if (normalized.length() > MAX_LENGTH) {
                throw new InvalidResearchRequestException(
                        "reason must not exceed 500 normalized characters"
                );
            }
        }
        String result = normalized.toString().strip();
        return result.isEmpty() ? null : result;
    }

    static String withDefault(String value) {
        String normalized = nullable(value);
        return normalized == null ? USER_REQUESTED : normalized;
    }
}
