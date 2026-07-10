package com.aiquantresearch.api.research.provider;

import java.time.LocalDate;
import java.util.List;

public record MacroDataSnapshot(
        String fixtureVersion,
        LocalDate asOfDate,
        List<MacroSeries> series,
        String watermark
) {
    public MacroDataSnapshot {
        series = List.copyOf(series);
    }
}
