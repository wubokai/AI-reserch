package com.aiquantresearch.api.research.application;

public class AccountAccessDeniedException extends ResearchApplicationException {

    public AccountAccessDeniedException() {
        super(
                "ACCOUNT_DISABLED",
                "The authenticated account is not active",
                false
        );
    }
}
