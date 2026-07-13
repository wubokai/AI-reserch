"""Strict request and response contracts for deterministic analytics."""

from __future__ import annotations

from datetime import date  # noqa: TC003 - Pydantic resolves this type at runtime.
from enum import StrEnum
from typing import Annotated, ClassVar, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_validator,
    model_validator,
)

CALCULATION_VERSION: Literal["quant_v1"] = "quant_v1"
REQUEST_SCHEMA_VERSION: Literal["analytics_full_request_v1"] = "analytics_full_request_v1"
RESPONSE_SCHEMA_VERSION: Literal["analytics_full_response_v1"] = "analytics_full_response_v1"
INSIGHTS_RESPONSE_SCHEMA_VERSION: Literal["analytics_insights_response_v1"] = (
    "analytics_insights_response_v1"
)
INSIGHTS_CALCULATION_VERSION: Literal["insights_v1"] = "insights_v1"

DecimalString = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=64,
        pattern=r"^-?[0-9]+(?:\.[0-9]+)?$",
    ),
]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{64}$")]
SnapshotId = Annotated[str, StringConstraints(min_length=1, max_length=100)]
Symbol = Annotated[str, StringConstraints(pattern=r"^[A-Z0-9.\-]{1,12}$")]


class StrictApiModel(BaseModel):
    """Base model that rejects coercion and unknown fields."""

    model_config: ClassVar[ConfigDict] = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )


class SecurityType(StrEnum):
    """Security types supported by the v1 analytics contract."""

    COMMON_STOCK = "COMMON_STOCK"
    ETF = "ETF"


class ScenarioName(StrEnum):
    """Canonical scenario names in deterministic output order."""

    BULL = "BULL"
    BASE = "BASE"
    BEAR = "BEAR"


class PeriodType(StrEnum):
    """Supported normalized fundamental periods."""

    QUARTER = "QUARTER"
    ANNUAL = "ANNUAL"
    TTM = "TTM"
    POINT_IN_TIME = "POINT_IN_TIME"


class MetricStatus(StrEnum):
    """Availability state for an individual metric."""

    AVAILABLE = "AVAILABLE"
    NOT_AVAILABLE = "NOT_AVAILABLE"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    INVALID_INPUT = "INVALID_INPUT"


class AnalysisStatus(StrEnum):
    """Aggregate status for one analytics response."""

    COMPLETED = "COMPLETED"
    COMPLETED_WITH_WARNINGS = "COMPLETED_WITH_WARNINGS"
    FAILED = "FAILED"


class TrendClassification(StrEnum):
    """Deterministic long-horizon trend labels."""

    STRONG_UPTREND = "STRONG_UPTREND"
    UPTREND = "UPTREND"
    RANGE = "RANGE"
    DOWNTREND = "DOWNTREND"
    STRONG_DOWNTREND = "STRONG_DOWNTREND"


class PriceBar(StrictApiModel):
    """One adjusted daily OHLCV observation."""

    date: date
    open: DecimalString
    high: DecimalString
    low: DecimalString
    close: DecimalString
    adjusted_close: DecimalString = Field(alias="adjustedClose")
    volume: DecimalString
    source_snapshot_id: SnapshotId = Field(alias="sourceSnapshotId")


class FundamentalValue(StrictApiModel):
    """One normalized fundamental input value."""

    name: Annotated[str, StringConstraints(min_length=1, max_length=100)]
    value: DecimalString
    unit: Annotated[str, StringConstraints(min_length=1, max_length=32)]
    period_type: PeriodType = Field(alias="periodType")
    period_end_date: date = Field(alias="periodEndDate")
    source_snapshot_id: SnapshotId = Field(alias="sourceSnapshotId")


class ScenarioAssumption(StrictApiModel):
    """One Bull, Base, or Bear scenario assumption set."""

    name: ScenarioName
    revenue_growth: DecimalString = Field(alias="revenueGrowth")
    target_ebitda_margin: DecimalString = Field(alias="targetEbitdaMargin")
    ev_to_ebitda_multiple: DecimalString = Field(alias="evToEbitdaMultiple")
    valuation_method: Literal["EV_EBITDA", "EV_REVENUE"] = Field(
        default="EV_EBITDA",
        alias="valuationMethod",
    )
    valuation_multiple: DecimalString | None = Field(
        default=None,
        alias="valuationMultiple",
    )
    probability: DecimalString


class ScenarioInput(StrictApiModel):
    """Inputs for the transparent EV/EBITDA scenario model."""

    base_revenue: DecimalString = Field(alias="baseRevenue")
    current_price: DecimalString = Field(alias="currentPrice")
    net_debt: DecimalString = Field(alias="netDebt")
    diluted_shares: DecimalString = Field(alias="dilutedShares")
    currency: Annotated[str, StringConstraints(pattern=r"^[A-Z]{3}$")]
    source_snapshot_ids: tuple[SnapshotId, ...] = Field(
        alias="sourceSnapshotIds",
        min_length=1,
        max_length=20,
    )
    scenarios: tuple[ScenarioAssumption, ...] = Field(min_length=3, max_length=3)

    @field_validator("source_snapshot_ids")
    @classmethod
    def _require_unique_snapshot_ids(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if len(values) != len(set(values)):
            msg = "sourceSnapshotIds must be unique"
            raise ValueError(msg)
        return values


class FullAnalysisRequest(StrictApiModel):
    """Canonical input envelope shared by every analytics endpoint."""

    schema_version: Literal["analytics_full_request_v1"] = Field(alias="schemaVersion")
    calculation_version: Literal["quant_v1"] = Field(alias="calculationVersion")
    input_hash: Sha256 = Field(alias="inputHash")
    symbol: Symbol
    security_type: SecurityType = Field(alias="securityType")
    period_start: date = Field(alias="periodStart")
    period_end: date = Field(alias="periodEnd")
    prices: tuple[PriceBar, ...] = Field(min_length=2, max_length=5000)
    benchmark_symbol: Symbol | None = Field(alias="benchmarkSymbol")
    benchmark_prices: tuple[PriceBar, ...] = Field(
        alias="benchmarkPrices",
        max_length=5000,
    )
    risk_free_rate_annual: DecimalString = Field(alias="riskFreeRateAnnual")
    minimum_accepted_return_annual: DecimalString = Field(
        alias="minimumAcceptedReturnAnnual",
    )
    fundamentals: tuple[FundamentalValue, ...] = Field(max_length=500)
    scenario_input: ScenarioInput | None = Field(alias="scenarioInput")


class AnalyticsWarning(StrictApiModel):
    """Safe, structured warning returned at metric or response scope."""

    code: Annotated[str, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{1,63}$")]
    message: Annotated[str, StringConstraints(min_length=1, max_length=500)]
    metric_name: Annotated[str, StringConstraints(max_length=100)] | None = Field(
        alias="metricName",
    )


class Metric(StrictApiModel):
    """One deterministic scalar result with full calculation lineage."""

    name: Annotated[str, StringConstraints(min_length=1, max_length=100)]
    value: DecimalString | None
    unit: Annotated[str, StringConstraints(min_length=1, max_length=32)]
    status: MetricStatus
    sample_size: Annotated[int, Field(ge=0)] = Field(alias="sampleSize")
    period_start: date | None = Field(alias="periodStart")
    period_end: date | None = Field(alias="periodEnd")
    calculation_version: Literal["quant_v1"] = Field(alias="calculationVersion")
    input_snapshot_ids: tuple[SnapshotId, ...] = Field(
        alias="inputSnapshotIds",
        max_length=100,
    )
    warnings: tuple[AnalyticsWarning, ...] = Field(max_length=20)

    @model_validator(mode="after")
    def _validate_value_matches_status(self) -> Metric:
        if self.status is MetricStatus.AVAILABLE and self.value is None:
            msg = "AVAILABLE metrics require a value"
            raise ValueError(msg)
        if self.status is not MetricStatus.AVAILABLE and self.value is not None:
            msg = "Unavailable metrics must not publish a value"
            raise ValueError(msg)
        return self


class TrendSignals(StrictApiModel):
    """The five deterministic signals used to classify trend."""

    close_above_sma20: Literal[-1, 1] = Field(alias="closeAboveSma20")
    sma20_above_sma50: Literal[-1, 1] = Field(alias="sma20AboveSma50")
    sma50_above_sma200: Literal[-1, 1] = Field(alias="sma50AboveSma200")
    sma50_slope_20: Literal[-1, 0, 1] = Field(alias="sma50Slope20")
    close_distance_sma200: Literal[-1, 0, 1] = Field(alias="closeDistanceSma200")


class TrendResult(StrictApiModel):
    """Versioned, explainable trend classification without LLM involvement."""

    classification: TrendClassification
    score: Annotated[int, Field(ge=-5, le=5)]
    signals: TrendSignals
    sample_size: Annotated[int, Field(ge=200)] = Field(alias="sampleSize")
    period_start: date = Field(alias="periodStart")
    period_end: date = Field(alias="periodEnd")
    calculation_version: Literal["quant_v1"] = Field(alias="calculationVersion")
    input_snapshot_ids: tuple[SnapshotId, ...] = Field(
        alias="inputSnapshotIds",
        max_length=100,
    )
    warnings: tuple[AnalyticsWarning, ...] = Field(max_length=20)


class AnalysisResponse(StrictApiModel):
    """Deterministic response envelope returned by every POST endpoint."""

    schema_version: Literal["analytics_full_response_v1"] = Field(alias="schemaVersion")
    calculation_version: Literal["quant_v1"] = Field(alias="calculationVersion")
    input_hash: Sha256 = Field(alias="inputHash")
    symbol: Symbol
    status: AnalysisStatus
    period_start: date = Field(alias="periodStart")
    period_end: date = Field(alias="periodEnd")
    sample_size: Annotated[int, Field(ge=0)] = Field(alias="sampleSize")
    benchmark_sample_size: Annotated[int, Field(ge=0)] = Field(
        alias="benchmarkSampleSize",
    )
    metrics: tuple[Metric, ...] = Field(max_length=300)
    trend: TrendResult | None
    warnings: tuple[AnalyticsWarning, ...] = Field(max_length=100)


class InsightPricePoint(StrictApiModel):
    """One chart overlay point calculated from a registered price series."""

    date: date
    ma20: DecimalString | None
    ma50: DecimalString | None


class InsightTechnicalSummary(StrictApiModel):
    """Explainable current-price relationship to chart moving averages."""

    current_price: DecimalString = Field(alias="currentPrice")
    price_vs_ma20: DecimalString | None = Field(alias="priceVsMa20")
    price_vs_ma50: DecimalString | None = Field(alias="priceVsMa50")
    signal: Literal[
        "ABOVE_BOTH",
        "BELOW_BOTH",
        "MIXED",
        "INSUFFICIENT_HISTORY",
    ]


class InsightSensitivityRow(StrictApiModel):
    """One deterministic revenue-growth row in the valuation grid."""

    revenue_growth_rate: DecimalString = Field(alias="revenueGrowthRate")
    implied_prices: tuple[DecimalString, ...] = Field(
        alias="impliedPrices",
        min_length=5,
        max_length=5,
    )
    upside_downside: tuple[DecimalString, ...] = Field(
        alias="upsideDownside",
        min_length=5,
        max_length=5,
    )


class InsightRangeStat(StrictApiModel):
    """Precalculated statistics for one supported chart range."""

    range: Literal["3M", "1Y", "3Y", "MAX"]
    period_start: date = Field(alias="periodStart")
    period_end: date = Field(alias="periodEnd")
    first_price: DecimalString = Field(alias="firstPrice")
    last_price: DecimalString = Field(alias="lastPrice")
    period_return: DecimalString = Field(alias="periodReturn")
    high: DecimalString
    low: DecimalString
    average_volume: DecimalString = Field(alias="averageVolume")


class InsightSensitivityMatrix(StrictApiModel):
    """Five-by-five deterministic price sensitivity matrix."""

    revenue_growth_rates: tuple[DecimalString, ...] = Field(
        alias="revenueGrowthRates",
        min_length=5,
        max_length=5,
    )
    valuation_multiples: tuple[DecimalString, ...] = Field(
        alias="valuationMultiples",
        min_length=5,
        max_length=5,
    )
    rows: tuple[InsightSensitivityRow, ...] = Field(min_length=5, max_length=5)


class InsightValuation(StrictApiModel):
    """Reverse-implied market expectation and scenario sensitivity output."""

    current_price: DecimalString = Field(alias="currentPrice")
    weighted_implied_price: DecimalString = Field(alias="weightedImpliedPrice")
    premium_discount_to_weighted_value: DecimalString = Field(
        alias="premiumDiscountToWeightedValue",
    )
    market_implied_revenue_growth: DecimalString | None = Field(
        alias="marketImpliedRevenueGrowth",
    )
    market_implied_growth_gap: DecimalString | None = Field(alias="marketImpliedGrowthGap")
    valuation_method: Literal["EV_EBITDA", "EV_REVENUE"] = Field(alias="valuationMethod")
    base_revenue_growth: DecimalString = Field(alias="baseRevenueGrowth")
    base_ebitda_margin: DecimalString = Field(alias="baseEbitdaMargin")
    base_valuation_multiple: DecimalString = Field(alias="baseValuationMultiple")
    sensitivity: InsightSensitivityMatrix


class InsightsResponse(StrictApiModel):
    """Versioned deterministic chart and valuation insight response."""

    schema_version: Literal["analytics_insights_response_v1"] = Field(alias="schemaVersion")
    calculation_version: Literal["insights_v1"] = Field(alias="calculationVersion")
    input_hash: Sha256 = Field(alias="inputHash")
    symbol: Symbol
    price_points: tuple[InsightPricePoint, ...] = Field(alias="pricePoints", max_length=5000)
    range_stats: tuple[InsightRangeStat, ...] = Field(
        alias="rangeStats",
        min_length=4,
        max_length=4,
    )
    technical_summary: InsightTechnicalSummary = Field(alias="technicalSummary")
    valuation: InsightValuation


class AnalyticsErrorResponse(StrictApiModel):
    """Safe error envelope for invalid input and unexpected failures."""

    timestamp: str
    status: int
    code: Annotated[str, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{1,63}$")]
    message: Annotated[str, StringConstraints(min_length=1, max_length=500)]
    request_id: str = Field(alias="requestId")
    research_id: str | None = Field(alias="researchId")
    details: dict[str, object]
