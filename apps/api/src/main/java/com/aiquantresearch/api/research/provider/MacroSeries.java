package com.aiquantresearch.api.research.provider;

import java.util.List;

public record MacroSeries(
        String seriesId,
        String name,
        String unit,
        String frequency,
        String frequencyShort,
        String unitsShort,
        String seasonalAdjustment,
        String lastUpdated,
        List<MacroObservation> observations
) {
    public MacroSeries {
        observations = List.copyOf(observations);
    }

    public MacroSeries(
            String seriesId,
            String name,
            String unit,
            List<MacroObservation> observations
    ) {
        this(seriesId, name, unit, null, null, null, null, null, observations);
    }
}
