package com.aiquantresearch.api.research.provider.mock;

import com.aiquantresearch.api.research.provider.FilingDocument;
import com.aiquantresearch.api.research.provider.FilingProvider;
import com.aiquantresearch.api.research.provider.FilingSnapshot;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.providers.filing",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockFilingProvider implements FilingProvider {

    private final MockFixtureCatalog catalog;

    public MockFilingProvider(MockFixtureCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public FilingSnapshot fetch(String symbol) {
        var manifest = catalog.manifest();
        var fixture = catalog.security(symbol);
        var documents = fixture.filings().stream()
                .map(item -> new FilingDocument(
                        item.documentId(),
                        item.formType(),
                        item.filingDate(),
                        item.title(),
                        item.summary(),
                        representativeHtml(item.title(), item.summary())
                ))
                .toList();
        return new FilingSnapshot(
                "MOCK_FILINGS_V1",
                "mock_filings_v1",
                manifest.fixtureVersion(),
                fixture.symbol(),
                manifest.asOfDate(),
                manifest.asOfDate().atStartOfDay().toInstant(ZoneOffset.UTC),
                null,
                null,
                documents,
                manifest.watermark(),
                true,
                "FRESH",
                "mock_fixture_license_v1"
        );
    }

    private static String representativeHtml(String title, String summary) {
        String safeTitle = escape(title);
        String safeSummary = escape(summary);
        return """
                <!doctype html><html><body>
                <h1>%s</h1>
                <h2>Item 1. Business</h2><p>%s</p>
                <h2>Item 1A. Risk Factors</h2><p>%s</p>
                <h2>Item 7. Management's Discussion and Analysis</h2><p>%s</p>
                <h2>Item 8. Financial Statements and Supplementary Data</h2>
                <p>Fixed synthetic financial statement context for deterministic testing.</p>
                </body></html>
                """.formatted(safeTitle, safeSummary, safeSummary, safeSummary);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
