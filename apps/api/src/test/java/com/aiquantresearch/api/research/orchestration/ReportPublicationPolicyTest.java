package com.aiquantresearch.api.research.orchestration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportPublicationPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsModeConsistentMockAndRealArtifacts() {
        assertThatCode(() -> ReportPublicationPolicy.validate(
                DataMode.MOCK,
                report(DataMode.MOCK),
                List.of(source(true)),
                List.of(evidence(true))
        )).doesNotThrowAnyException();
        assertThatCode(() -> ReportPublicationPolicy.validate(
                DataMode.REAL,
                report(DataMode.REAL),
                List.of(source(false)),
                List.of(evidence(false))
        )).doesNotThrowAnyException();
    }

    @Test
    void blocksMixedTestPublication() {
        assertFailure(
                "REPORT_DATA_MODE_BLOCKED",
                () -> ReportPublicationPolicy.validate(
                        DataMode.MIXED_TEST,
                        report(DataMode.MIXED_TEST),
                        List.of(source(false)),
                        List.of(evidence(false))
                )
        );
    }

    @Test
    void rejectsReportModeMismatch() {
        assertFailure(
                "REPORT_DATA_MODE_MISMATCH",
                () -> ReportPublicationPolicy.validate(
                        DataMode.REAL,
                        report(DataMode.MOCK),
                        List.of(source(false)),
                        List.of(evidence(false))
                )
        );
    }

    @Test
    void rejectsSourceOrEvidenceModeContamination() {
        assertFailure(
                "REPORT_SOURCE_MODE_MISMATCH",
                () -> ReportPublicationPolicy.validate(
                        DataMode.REAL,
                        report(DataMode.REAL),
                        List.of(source(true)),
                        List.of(evidence(false))
                )
        );
        assertFailure(
                "REPORT_EVIDENCE_MODE_MISMATCH",
                () -> ReportPublicationPolicy.validate(
                        DataMode.REAL,
                        report(DataMode.REAL),
                        List.of(source(false)),
                        List.of(evidence(true))
                )
        );
    }

    private com.fasterxml.jackson.databind.node.ObjectNode report(DataMode mode) {
        return objectMapper.createObjectNode().put("dataMode", mode.name());
    }

    private StoredSource source(boolean demo) {
        return new StoredSource(
                UUID.randomUUID(),
                "MARKET_DATA",
                "MU",
                objectMapper.createObjectNode(),
                "a".repeat(64),
                demo ? "MOCK_MARKET_V1" : "LICENSED_MARKET",
                true,
                "FRESH",
                demo
        );
    }

    private StoredEvidence evidence(boolean demo) {
        return new StoredEvidence(
                UUID.randomUUID(),
                "ev_market",
                "MARKET_PRICE",
                "Market snapshot",
                "Immutable evidence",
                objectMapper.createObjectNode(),
                null,
                UUID.randomUUID(),
                null,
                new BigDecimal("0.9900"),
                true,
                "FRESH",
                LocalDate.parse("2026-07-10"),
                demo
        );
    }

    private static void assertFailure(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(StepExecutionException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(code));
    }
}
