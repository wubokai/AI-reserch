package com.aiquantresearch.api.research.provider;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceBar(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal adjustedClose,
        long volume
) {
}
