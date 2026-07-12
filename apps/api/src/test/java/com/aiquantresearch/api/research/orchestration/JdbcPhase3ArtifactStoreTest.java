package com.aiquantresearch.api.research.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JdbcPhase3ArtifactStoreTest {

    @Test
    void classifiesRealModeByStringValueInsteadOfReferenceIdentity() {
        assertThat(JdbcPhase3ArtifactStore.isDemoMode(new String("REAL"))).isFalse();
        assertThat(JdbcPhase3ArtifactStore.isDemoMode("MOCK")).isTrue();
        assertThat(JdbcPhase3ArtifactStore.isDemoMode(null)).isTrue();
    }
}
