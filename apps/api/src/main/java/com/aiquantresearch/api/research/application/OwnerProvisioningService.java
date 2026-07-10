package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.UserStatus;
import com.aiquantresearch.api.research.persistence.UserEntity;
import com.aiquantresearch.api.research.persistence.UserRepository;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerProvisioningService {

    private final UserRepository userRepository;
    private final Clock clock;

    public OwnerProvisioningService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public UserEntity ensureOwner(UUID ownerId, String principalName, String email) {
        if (ownerId == null) {
            throw new InvalidResearchRequestException("Authenticated owner id is required");
        }
        String normalizedEmail = normalizeEmail(principalName, email);
        userRepository.ensureLocalPrincipal(ownerId, normalizedEmail, clock.instant());
        UserEntity owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResearchApplicationException(
                        "OWNER_PROVISIONING_FAILED",
                        "The authenticated owner could not be provisioned",
                        false
                ));
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new AccountAccessDeniedException();
        }
        return owner;
    }

    @Transactional
    public UserEntity ensureOwner(UUID ownerId, String email) {
        return ensureOwner(ownerId, email, email);
    }

    private static String normalizeEmail(String principalName, String email) {
        String candidate = email;
        if (candidate == null || candidate.isBlank()) {
            if (principalName == null || principalName.isBlank()) {
                throw new InvalidResearchRequestException("Authenticated principal name is required");
            }
            String normalizedPrincipal = principalName.trim().toLowerCase(Locale.ROOT);
            candidate = normalizedPrincipal.contains("@")
                    ? normalizedPrincipal
                    : normalizedPrincipal + "@local.invalid";
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains("@") || normalized.length() > 320) {
            throw new InvalidResearchRequestException("Authenticated principal email is invalid");
        }
        return normalized;
    }
}
