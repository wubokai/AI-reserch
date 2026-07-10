package com.aiquantresearch.api.research.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.domain.UserStatus;
import com.aiquantresearch.api.research.persistence.UserEntity;
import com.aiquantresearch.api.research.persistence.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Mock
    private UserRepository userRepository;

    private OwnerProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new OwnerProvisioningService(
                userRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void activeOwnerCanContinue() {
        UserEntity owner = userWithStatus(UserStatus.ACTIVE);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));

        UserEntity result = service.ensureOwner(
                OWNER_ID,
                "Alice",
                "Alice@Example.com"
        );

        assertThat(result).isSameAs(owner);
        verify(userRepository).ensureLocalPrincipal(OWNER_ID, "alice@example.com", NOW);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"LOCKED", "DISABLED"})
    void nonActiveOwnerIsDenied(UserStatus status) {
        UserEntity owner = userWithStatus(status);
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.ensureOwner(
                OWNER_ID,
                "alice@example.com",
                "alice@example.com"
        ))
                .isInstanceOf(AccountAccessDeniedException.class)
                .hasMessage("The authenticated account is not active")
                .satisfies(exception -> assertThat(
                        ((AccountAccessDeniedException) exception).code()
                ).isEqualTo("ACCOUNT_DISABLED"));
    }

    private static UserEntity userWithStatus(UserStatus status) {
        UserEntity owner = mock(UserEntity.class);
        when(owner.getStatus()).thenReturn(status);
        return owner;
    }
}
