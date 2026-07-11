package com.aiquantresearch.api.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;

class CurrentUserResolverTest {

    private final CurrentUserResolver resolver = new CurrentUserResolver();

    @Test
    void derivesStableIdentityFromPrincipalSubject() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "Demo",
                "ignored",
                java.util.List.of()
        );

        CurrentUserIdentity first = resolver.resolve(authentication);
        CurrentUserIdentity second = resolver.resolve(authentication);

        assertThat(first.id()).isEqualTo(second.id());
        assertThat(first.username()).isEqualTo("Demo");
        assertThat(first.email()).isEqualTo("demo@local.invalid");
    }

    @Test
    void usesVerifiedJwtEmailForProductionOwner() {
        Jwt jwt = Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .subject("primary-owner")
                .claim("email", "BW2754@nyu.edu")
                .issuedAt(Instant.parse("2026-07-11T12:00:00Z"))
                .expiresAt(Instant.parse("2026-07-11T12:01:00Z"))
                .build();
        var authentication = new org.springframework.security.oauth2.server.resource.authentication
                .JwtAuthenticationToken(jwt, java.util.List.of());

        CurrentUserIdentity identity = resolver.resolve(authentication);

        assertThat(identity.username()).isEqualTo("primary-owner");
        assertThat(identity.email()).isEqualTo("bw2754@nyu.edu");
    }

    @Test
    void rejectsMissingAuthentication() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
