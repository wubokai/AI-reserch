package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.research.application.ResearchNotFoundException;
import com.aiquantresearch.api.research.report.ReportHtmlRenderer;
import com.aiquantresearch.api.research.report.ReportMarkdownRenderer;
import com.aiquantresearch.api.research.report.ReportPdfRenderer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReportExportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportExportService.class);

    private final ReportExportStore store;
    private final ReportMarkdownRenderer markdownRenderer;
    private final ReportHtmlRenderer htmlRenderer;
    private final ReportPdfRenderer pdfRenderer;

    ReportExportService(
            ReportExportStore store,
            ReportMarkdownRenderer markdownRenderer,
            ReportHtmlRenderer htmlRenderer,
            ReportPdfRenderer pdfRenderer
    ) {
        this.store = store;
        this.markdownRenderer = markdownRenderer;
        this.htmlRenderer = htmlRenderer;
        this.pdfRenderer = pdfRenderer;
    }

    @Transactional
    ReportExportArtifact export(
            UUID ownerId,
            UUID researchId,
            ReportExportFormat format,
            Integer reportVersion
    ) {
        ExportReportSource source = store.findReport(ownerId, researchId, reportVersion)
                .orElseThrow(() -> new ResearchNotFoundException(researchId));
        String templateVersion = format.templateVersion();
        try {
            // Serialize first creation for a report version. A second request waits, then
            // observes and returns the exact bytes committed by the first request.
            store.lockReportVersion(source.reportVersionId());
            CachedReportExport cached = store.cached(
                    source.reportVersionId(),
                    format,
                    templateVersion
            ).orElse(null);
            if (cached != null) {
                verifyContentHash(cached);
                return artifact(source, format, cached);
            }
            var evidence = store.evidence(source.reportVersionId(), researchId);
            byte[] rendered = switch (format) {
                case MARKDOWN -> format.encode(markdownRenderer.render(source.report(), evidence));
                case HTML -> format.encode(htmlRenderer.render(source.report(), evidence));
                case PDF -> pdfRenderer.render(source.report(), evidence);
            };
            if (rendered.length == 0) {
                throw new IllegalStateException("The renderer produced an empty report export");
            }
            CachedReportExport persisted = store.cache(
                    source,
                    format,
                    templateVersion,
                    rendered,
                    sha256(rendered)
            );
            verifyContentHash(persisted);
            return artifact(source, format, persisted);
        } catch (RuntimeException exception) {
            if (exception instanceof ReportExportException) {
                throw exception;
            }
            LOGGER.warn(
                    "Report export failed for research {} format {} ({})",
                    researchId,
                    format,
                    exception.getClass().getSimpleName()
            );
            throw new ReportExportException(researchId);
        }
    }

    private static ReportExportArtifact artifact(
            ExportReportSource source,
            ReportExportFormat format,
            CachedReportExport cached
    ) {
        String safeSymbol = source.symbol().replaceAll("[^A-Za-z0-9.-]", "_");
        String filename = safeSymbol + "-research-v" + source.version() + "." + format.extension();
        return new ReportExportArtifact(
                cached.content(),
                cached.contentHash(),
                format,
                filename,
                source.version(),
                source.dataMode()
        );
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void verifyContentHash(CachedReportExport cached) {
        if (!sha256(cached.content()).equals(cached.contentHash())) {
            throw new IllegalStateException("The cached report export hash is invalid");
        }
    }
}
