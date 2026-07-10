package com.aiquantresearch.api.shared.security;

import java.util.UUID;

public record CurrentUserIdentity(UUID id, String username, String email) {
}
