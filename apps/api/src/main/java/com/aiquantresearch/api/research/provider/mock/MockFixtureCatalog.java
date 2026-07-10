package com.aiquantresearch.api.research.provider.mock;

import com.aiquantresearch.api.research.provider.ProviderDataNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class MockFixtureCatalog {

    public static final String EXPECTED_WATERMARK = "DEMO DATA — NOT REAL MARKET DATA";
    private static final String MANIFEST_PATH = "mock/v1/manifest.json";

    private final Manifest manifest;
    private final Map<String, SecurityFixture> securities;

    public MockFixtureCatalog(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(MANIFEST_PATH).getInputStream()) {
            manifest = objectMapper.readValue(input, Manifest.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load the deterministic Mock fixture", exception);
        }
        if (!EXPECTED_WATERMARK.equals(manifest.watermark())) {
            throw new IllegalStateException("Mock fixture watermark does not match the public contract");
        }
        try {
            securities = manifest.securities().stream().collect(Collectors.toUnmodifiableMap(
                    SecurityFixture::symbol,
                    Function.identity()
            ));
        } catch (IllegalStateException exception) {
            throw new IllegalStateException("Mock fixture contains a duplicate symbol", exception);
        }
    }

    public Manifest manifest() {
        return manifest;
    }

    public SecurityFixture security(String symbol) {
        SecurityFixture fixture = securities.get(symbol == null ? null : symbol.toUpperCase());
        if (fixture == null) {
            throw new ProviderDataNotFoundException(
                    "No deterministic Mock fixture exists for symbol " + symbol
            );
        }
        return fixture;
    }

    public boolean supports(String symbol) {
        return symbol != null && securities.containsKey(symbol.toUpperCase());
    }

    public record Manifest(
            String fixtureVersion,
            LocalDate asOfDate,
            LocalDate periodStart,
            String watermark,
            List<SecurityFixture> securities,
            List<MacroSeriesFixture> macroSeries
    ) {
        public Manifest {
            securities = List.copyOf(securities);
            macroSeries = List.copyOf(macroSeries);
        }
    }

    public record SecurityFixture(
            String symbol,
            String companyName,
            String exchange,
            String securityType,
            String currency,
            long basePriceCents,
            long dailyTrendCents,
            long cycleAmplitudeCents,
            long volumeBase,
            Map<String, BigDecimal> fundamentals,
            List<ScenarioFixture> scenarios,
            List<FilingFixture> filings
    ) {
        public SecurityFixture {
            fundamentals = Map.copyOf(fundamentals);
            scenarios = List.copyOf(scenarios);
            filings = List.copyOf(filings);
        }
    }

    public record ScenarioFixture(
            String name,
            BigDecimal revenueGrowth,
            BigDecimal targetEbitdaMargin,
            BigDecimal evToEbitdaMultiple,
            BigDecimal probability
    ) {
    }

    public record FilingFixture(
            String documentId,
            String formType,
            LocalDate filingDate,
            String title,
            String summary
    ) {
    }

    public record MacroSeriesFixture(
            String seriesId,
            String name,
            String unit,
            List<MacroObservationFixture> observations
    ) {
        public MacroSeriesFixture {
            observations = List.copyOf(observations);
        }
    }

    public record MacroObservationFixture(LocalDate date, BigDecimal value) {
    }
}
