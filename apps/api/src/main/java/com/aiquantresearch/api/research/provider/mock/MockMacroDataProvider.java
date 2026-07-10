package com.aiquantresearch.api.research.provider.mock;

import com.aiquantresearch.api.research.provider.MacroDataProvider;
import com.aiquantresearch.api.research.provider.MacroDataSnapshot;
import com.aiquantresearch.api.research.provider.MacroObservation;
import com.aiquantresearch.api.research.provider.MacroSeries;
import org.springframework.stereotype.Component;

@Component
public class MockMacroDataProvider implements MacroDataProvider {

    private final MockFixtureCatalog catalog;

    public MockMacroDataProvider(MockFixtureCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public MacroDataSnapshot fetch() {
        var manifest = catalog.manifest();
        var series = manifest.macroSeries().stream()
                .map(item -> new MacroSeries(
                        item.seriesId(),
                        item.name(),
                        item.unit(),
                        item.observations().stream()
                                .map(value -> new MacroObservation(value.date(), value.value()))
                                .toList()
                ))
                .toList();
        return new MacroDataSnapshot(
                manifest.fixtureVersion(),
                manifest.asOfDate(),
                series,
                manifest.watermark()
        );
    }
}
