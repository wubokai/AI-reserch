package com.aiquantresearch.api.research.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class CanonicalHashService {

    private final ObjectMapper canonicalMapper;

    public CanonicalHashService(ObjectMapper objectMapper) {
        canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String canonicalJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResearchApplicationException(
                    "REQUEST_SERIALIZATION_FAILED",
                    "The validated request could not be normalized",
                    false
            );
        }
    }

    public String hash(Object value) {
        return hashText(canonicalJson(value));
    }

    /**
     * Hashes JSON by value instead of by its database text representation.
     *
     * <p>PostgreSQL {@code jsonb} may reorder object keys and add whitespace when a value is
     * read back. Parsing into ordinary maps before canonical serialization keeps execution
     * fingerprints stable across that round trip.
     */
    public String hashCanonicalJsonText(String value) {
        try {
            Object parsed = canonicalMapper.readValue(value, Object.class);
            return hashText(canonicalMapper.writeValueAsString(parsed));
        } catch (JsonProcessingException exception) {
            throw new ResearchApplicationException(
                    "REQUEST_SERIALIZATION_FAILED",
                    "The stored request could not be normalized",
                    false
            );
        }
    }

    public String hashText(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
