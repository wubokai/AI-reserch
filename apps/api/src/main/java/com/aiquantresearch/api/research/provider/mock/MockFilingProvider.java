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
                        item.summary()
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
}
