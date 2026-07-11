package com.aiquantresearch.api.shared.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserResolver {

    private static final String ID_NAMESPACE = "ai-quant-research:user:v1:";

    public CurrentUserIdentity resolve(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("An authenticated principal is required");
        }

        String username = authentication.getName();
        if (username == null || username.isBlank()) {
            throw new AuthenticationCredentialsNotFoundException("The principal has no stable subject");
        }

        String normalized = username.trim().toLowerCase(Locale.ROOT);
        UUID id = UUID.nameUUIDFromBytes(
                (ID_NAMESPACE + normalized).getBytes(StandardCharsets.UTF_8)
        );
        String jwtEmail = authentication.getPrincipal() instanceof Jwt jwt
                ? jwt.getClaimAsString("email")
                : null;
        String email = jwtEmail == null || jwtEmail.isBlank()
                ? (normalized.contains("@") ? normalized : normalized + "@local.invalid")
                : jwtEmail.strip().toLowerCase(Locale.ROOT);
        return new CurrentUserIdentity(id, username.trim(), email);
    }
}
