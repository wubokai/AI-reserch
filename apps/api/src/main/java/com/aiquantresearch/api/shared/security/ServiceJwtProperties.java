package com.aiquantresearch.api.shared.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security.service-jwt")
public record ServiceJwtProperties(
        boolean enabled,
        @NotBlank String issuer,
        @NotBlank String audience,
        String hmacSecret,
        @NotBlank String subject,
        @NotBlank String email,
        @NotNull Duration tokenTtl
) {

    public ServiceJwtProperties {
        hmacSecret = normalize(hmacSecret);
    }

    public byte[] requireSecretBytes() {
        if (!enabled || hmacSecret == null) {
            throw new IllegalStateException(
                    "Production service JWT authentication requires SERVICE_JWT_HMAC_SECRET"
            );
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(hmacSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "SERVICE_JWT_HMAC_SECRET must be valid base64",
                    exception
            );
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "SERVICE_JWT_HMAC_SECRET must contain at least 256 bits"
            );
        }
        if (!Duration.ofSeconds(60).equals(tokenTtl)) {
            throw new IllegalStateException(
                    "SERVICE_JWT_TOKEN_TTL must remain fixed at 60 seconds"
            );
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+$") || email.length() > 320) {
            throw new IllegalStateException("SERVICE_JWT_EMAIL must be a valid owner email");
        }
        return decoded;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
