package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.shared.security.CurrentUserIdentity;
import com.aiquantresearch.api.shared.security.CurrentUserResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedOwnerService {

    private final CurrentUserResolver currentUserResolver;
    private final OwnerProvisioningService ownerProvisioningService;

    public AuthenticatedOwnerService(
            CurrentUserResolver currentUserResolver,
            OwnerProvisioningService ownerProvisioningService
    ) {
        this.currentUserResolver = currentUserResolver;
        this.ownerProvisioningService = ownerProvisioningService;
    }

    public CurrentUserIdentity requireActive(Authentication authentication) {
        CurrentUserIdentity owner = currentUserResolver.resolve(authentication);
        ownerProvisioningService.ensureOwner(owner.id(), owner.username(), owner.email());
        return owner;
    }
}
