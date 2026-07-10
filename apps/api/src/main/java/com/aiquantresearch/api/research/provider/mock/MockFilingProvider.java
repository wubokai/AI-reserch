package com.aiquantresearch.api.research.provider.mock;

import com.aiquantresearch.api.research.provider.FilingDocument;
import com.aiquantresearch.api.research.provider.FilingProvider;
import com.aiquantresearch.api.research.provider.FilingSnapshot;
import org.springframework.stereotype.Component;

@Component
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
                manifest.fixtureVersion(),
                fixture.symbol(),
                manifest.asOfDate(),
                documents,
                manifest.watermark()
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
