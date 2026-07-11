package com.aiquantresearch.api.research.provider;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MacroObservation(
        LocalDate date,
        BigDecimal value,
        LocalDate realtimeStart,
        LocalDate realtimeEnd
) {

    public MacroObservation(LocalDate date, BigDecimal value) {
        this(date, value, null, null);
    }
}
