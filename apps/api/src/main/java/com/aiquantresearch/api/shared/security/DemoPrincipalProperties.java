package com.aiquantresearch.api.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.demo-principal")
public record DemoPrincipalProperties(
        boolean enabled,
        String username,
        String password
) {
}
