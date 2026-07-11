package com.aiquantresearch.api.shared.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class JsonBoundaryConfiguration implements WebMvcConfigurer {

    static final long MAX_DOCUMENT_BYTES = 64 * 1024L;
    static final long MAX_TOKEN_COUNT = 20_000L;
    static final int MAX_NESTING_DEPTH = 32;
    static final int MAX_STRING_LENGTH = 16_384;
    static final int MAX_NAME_LENGTH = 256;
    static final int MAX_NUMBER_LENGTH = 128;

    private final ObjectMapper applicationObjectMapper;

    public JsonBoundaryConfiguration(ObjectMapper applicationObjectMapper) {
        this.applicationObjectMapper = applicationObjectMapper;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        StreamReadConstraints requestConstraints = StreamReadConstraints.builder()
                .maxDocumentLength(MAX_DOCUMENT_BYTES)
                .maxTokenCount(MAX_TOKEN_COUNT)
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNameLength(MAX_NAME_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build();
        converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> {
                    ObjectMapper requestMapper = applicationObjectMapper.copy();
                    requestMapper.getFactory().setStreamReadConstraints(requestConstraints);
                    converter.setObjectMapper(requestMapper);
                });
    }
}
