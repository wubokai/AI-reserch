"""Application service and HTTP routes for deterministic analytics."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

from enum import StrEnum

from fastapi import APIRouter

from ai_quant_analytics.contracts import (
    CALCULATION_VERSION,
    RESPONSE_SCHEMA_VERSION,
    AnalysisResponse,
    AnalysisStatus,
    FullAnalysisRequest,
    Metric,
)
from ai_quant_analytics.core_metrics import (
    calculate_return_metrics,
    calculate_risk_metrics,
    calculate_technical_metrics,
)
from ai_quant_analytics.errors import AnalyticsDomainError
from ai_quant_analytics.fundamentals import (
    calculate_fundamental_metrics,
    calculate_valuation_metrics,
)
from ai_quant_analytics.normalization import clean_price_series, decimal_from_string
from ai_quant_analytics.scenarios import calculate_scenario_metrics
from ai_quant_analytics.serialization import sort_warnings

router = APIRouter(prefix="/analytics/v1", tags=["analytics"])


class AnalysisModule(StrEnum):
    """Internal module selection for the shared request envelope."""

    RETURNS = "returns"
    RISK = "risk"
    TECHNICALS = "technicals"
    FUNDAMENTALS = "fundamentals"
    VALUATION = "valuation"
    SCENARIOS = "scenarios"


_FULL_ANALYSIS_ORDER = tuple(AnalysisModule)
_MAX_TOTAL_PRICE_BARS = 10_000


def analyze(  # noqa: C901 - explicit dispatch keeps the endpoint module boundary visible.
    request: FullAnalysisRequest,
    modules: tuple[AnalysisModule, ...],
) -> AnalysisResponse:
    """Validate shared input once and execute selected modules in canonical order."""
    if request.calculation_version != CALCULATION_VERSION:
        raise AnalyticsDomainError(
            "UNSUPPORTED_CALCULATION_VERSION",
            "Only calculationVersion quant_v1 is supported.",
        )
    if len(request.prices) + len(request.benchmark_prices) > _MAX_TOTAL_PRICE_BARS:
        raise AnalyticsDomainError(
            "REQUEST_LIMIT_EXCEEDED",
            "Asset and benchmark prices may contain at most 10,000 bars in total.",
            status_code=413,
        )
    if request.benchmark_prices and request.benchmark_symbol is None:
        raise AnalyticsDomainError(
            "BENCHMARK_SYMBOL_REQUIRED",
            "benchmarkSymbol is required when benchmarkPrices are supplied.",
        )
    decimal_from_string(
        request.minimum_accepted_return_annual,
        "minimumAcceptedReturnAnnual",
    )
    series = clean_price_series(
        request.prices,
        period_start=request.period_start,
        period_end=request.period_end,
        series_name="prices",
    )
    benchmark = clean_price_series(
        request.benchmark_prices,
        period_start=request.period_start,
        period_end=request.period_end,
        series_name="benchmarkPrices",
    )

    metrics: list[Metric] = []
    for module in modules:
        if module is AnalysisModule.RETURNS:
            metrics.extend(calculate_return_metrics(series))
        elif module is AnalysisModule.RISK:
            metrics.extend(calculate_risk_metrics(series, request.risk_free_rate_annual))
        elif module is AnalysisModule.TECHNICALS:
            metrics.extend(calculate_technical_metrics(series))
        elif module is AnalysisModule.FUNDAMENTALS:
            metrics.extend(calculate_fundamental_metrics(request.fundamentals))
        elif module is AnalysisModule.VALUATION:
            metrics.extend(calculate_valuation_metrics(request.scenario_input, series))
        elif module is AnalysisModule.SCENARIOS:
            metrics.extend(
                calculate_scenario_metrics(
                    request.scenario_input,
                    period_start=series.bars[0].date,
                    period_end=series.bars[-1].date,
                ),
            )

    response_warnings = sort_warnings(
        series.warnings
        + benchmark.warnings
        + tuple(item for metric in metrics for item in metric.warnings),
    )
    completed_cleanly = not response_warnings and all(
        metric.value is not None for metric in metrics
    )
    return AnalysisResponse(
        schema_version=RESPONSE_SCHEMA_VERSION,
        calculation_version=CALCULATION_VERSION,
        input_hash=request.input_hash,
        symbol=request.symbol,
        status=(
            AnalysisStatus.COMPLETED
            if completed_cleanly
            else AnalysisStatus.COMPLETED_WITH_WARNINGS
        ),
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        sample_size=len(series.bars),
        benchmark_sample_size=len(benchmark.bars),
        metrics=tuple(metrics),
        warnings=response_warnings,
    )


@router.post("/returns", response_model=AnalysisResponse, summary="Calculate return metrics")
def analyze_returns(request: FullAnalysisRequest) -> AnalysisResponse:
    """Calculate total return and CAGR."""
    return analyze(request, (AnalysisModule.RETURNS,))


@router.post("/risk", response_model=AnalysisResponse, summary="Calculate risk metrics")
def analyze_risk(request: FullAnalysisRequest) -> AnalysisResponse:
    """Calculate annualized volatility, maximum drawdown, and Sharpe ratio."""
    return analyze(request, (AnalysisModule.RISK,))


@router.post(
    "/technicals",
    response_model=AnalysisResponse,
    summary="Calculate technical metrics",
)
def analyze_technicals(request: FullAnalysisRequest) -> AnalysisResponse:
    """Calculate final RSI-14, MACD, and MACD signal values."""
    return analyze(request, (AnalysisModule.TECHNICALS,))


@router.post(
    "/fundamentals",
    response_model=AnalysisResponse,
    summary="Calculate normalized fundamental metrics",
)
def analyze_fundamentals(request: FullAnalysisRequest) -> AnalysisResponse:
    """Calculate latest same-period fundamental margins."""
    return analyze(request, (AnalysisModule.FUNDAMENTALS,))


@router.post(
    "/valuation",
    response_model=AnalysisResponse,
    summary="Calculate deterministic valuation metrics",
)
def analyze_valuation(request: FullAnalysisRequest) -> AnalysisResponse:
    """Calculate market capitalization and enterprise value."""
    return analyze(request, (AnalysisModule.VALUATION,))


@router.post(
    "/scenarios",
    response_model=AnalysisResponse,
    summary="Calculate Bull, Base, and Bear scenarios",
)
def analyze_scenarios(request: FullAnalysisRequest) -> AnalysisResponse:
    """Calculate scenario prices and probability-weighted value."""
    return analyze(request, (AnalysisModule.SCENARIOS,))


@router.post(
    "/full-analysis",
    response_model=AnalysisResponse,
    summary="Calculate the complete Phase 3 metric set",
)
def analyze_full(request: FullAnalysisRequest) -> AnalysisResponse:
    """Calculate all deterministic Phase 3 modules with one cleaning pass."""
    return analyze(request, _FULL_ANALYSIS_ORDER)
