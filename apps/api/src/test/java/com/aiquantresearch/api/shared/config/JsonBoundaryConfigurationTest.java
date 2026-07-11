package com.aiquantresearch.api.shared.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class JsonBoundaryConfigurationTest {

    @Test
    void rejectsJsonDocumentsBeyondTheConfiguredStringBoundary() {
        var applicationMapper = new ObjectMapper();
        var converter = new MappingJackson2HttpMessageConverter(applicationMapper);
        var converters = new ArrayList<HttpMessageConverter<?>>();
        converters.add(converter);
        new JsonBoundaryConfiguration(applicationMapper).extendMessageConverters(converters);
        var mapper = converter.getObjectMapper();
        String oversized = "{\"query\":\"" + "x".repeat(
                JsonBoundaryConfiguration.MAX_STRING_LENGTH + 1
        ) + "\"}";

        assertThatThrownBy(() -> mapper.readTree(oversized))
                .isInstanceOf(StreamConstraintsException.class)
                .hasMessageContaining("String value length");

        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() ->
                applicationMapper.readTree(oversized));
    }
}
