package com.aiquantresearch.api.shared.config;

import com.aiquantresearch.api.shared.domain.DataMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record ApplicationProperties(
        @NotBlank String serviceName,
        @NotBlank String version,
        @NotNull DataMode dataMode
) {
}
