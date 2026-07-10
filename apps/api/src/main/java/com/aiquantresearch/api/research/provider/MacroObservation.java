package com.aiquantresearch.api.research.provider;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MacroObservation(LocalDate date, BigDecimal value) {
}
