package com.aiquantresearch.api.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

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
    void rejectsMissingAuthentication() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
