package com.aiquantresearch.api.research.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueFailureTest {

    @Test
    void classifiesEveryDocumentedFailStepResult() {
        assertThat(failure("RETRY_SCHEDULED").disposition())
                .isEqualTo(QueueFailure.Disposition.RETRY_SCHEDULED);
        assertThat(failure("FAILED").disposition())
                .isEqualTo(QueueFailure.Disposition.STEP_FAILED);
        assertThat(failure("CANCELLED").disposition())
                .isEqualTo(QueueFailure.Disposition.CANCELLED);
        assertThat(failure("STALE_LEASE").disposition())
                .isEqualTo(QueueFailure.Disposition.STALE_LEASE);
        assertThat(failure("RESEARCH_TERMINAL").disposition())
                .isEqualTo(QueueFailure.Disposition.RESEARCH_TERMINAL);
    }

    @Test
    void rejectsUnknownDatabaseProtocolResults() {
        assertThatThrownBy(() -> failure("SURPRISE").disposition())
                .isInstanceOf(QueueProtocolException.class)
                .hasMessageContaining("unknown result code");
    }

    private static QueueFailure failure(String resultCode) {
        return new QueueFailure(
                resultCode,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null
        );
    }
}
