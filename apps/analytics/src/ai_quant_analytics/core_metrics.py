"""Deterministic return, risk, and benchmark indicators for ``quant_v1``."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

import math
import statistics
from dataclasses import dataclass
from datetime import date  # noqa: TC003 - helper signatures are runtime metadata.
from decimal import Decimal
from itertools import pairwise
from typing import TYPE_CHECKING

from ai_quant_analytics.errors import AnalyticsDomainError
from ai_quant_analytics.normalization import CleanPriceSeries, decimal_from_string
from ai_quant_analytics.serialization import available_metric, unavailable_metric, warning

TRADING_DAYS_PER_YEAR = 252
ZERO_DENOMINATOR = 1e-12
_MINIMUM_PRICES = 2
_MINIMUM_CAGR_DAYS = 30
_MINIMUM_ANNUALIZED_RETURNS = 30
_MINIMUM_RISK_RETURNS = 30
_MINIMUM_VAR_RETURNS = 100
_MINIMUM_PAIRED_RETURNS = 60
_ROLLING_RETURN_WINDOW = 20

if TYPE_CHECKING:
    from ai_quant_analytics.contracts import AnalyticsWarning, Metric


@dataclass(frozen=True, slots=True)
class _AlignedReturns:
    dates: tuple[date, ...]
    asset: tuple[float, ...]
    benchmark: tuple[float, ...]
    snapshot_ids: tuple[str, ...]
    warnings: tuple[AnalyticsWarning, ...]


def calculate_return_metrics(
    series: CleanPriceSeries,
    benchmark: CleanPriceSeries,
) -> tuple[Metric, ...]:
    """Calculate scalar return metrics and aligned benchmark comparisons."""
    prices = _prices(series)
    returns = _simple_returns(prices)
    start, end = _period(series)
    latest_daily = available_metric(
        name="latest_daily_return",
        value=returns[-1],
        unit="ratio",
        places=8,
        sample_size=1,
        period_start=series.bars[-2].date,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
    )
    total_return = _cumulative_return(returns)
    total_metric = available_metric(
        name="total_return",
        value=total_return,
        unit="ratio",
        places=8,
        sample_size=len(returns),
        period_start=start,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
    )
    annualized_metric = _annualized_metric(
        name="annualized_return",
        returns=returns,
        series=series,
    )

    calendar_days = (end - start).days
    if calendar_days < _MINIMUM_CAGR_DAYS:
        cagr_metric = _insufficient_metric(
            name="cagr",
            unit="ratio",
            minimum="a span of at least 30 calendar days",
            sample_size=len(prices),
            series=series,
        )
    else:
        cagr = (prices[-1] / prices[0]) ** (365.2425 / calendar_days) - 1.0
        cagr_metric = available_metric(
            name="cagr",
            value=cagr,
            unit="ratio",
            places=8,
            sample_size=len(prices),
            period_start=start,
            period_end=end,
            snapshot_ids=series.snapshot_ids,
        )

    if len(returns) < _ROLLING_RETURN_WINDOW:
        rolling_metric = _insufficient_metric(
            name="rolling_return_20",
            unit="ratio",
            minimum="20 daily returns",
            sample_size=len(returns),
            series=series,
        )
    else:
        rolling_metric = available_metric(
            name="rolling_return_20",
            value=prices[-1] / prices[-(_ROLLING_RETURN_WINDOW + 1)] - 1.0,
            unit="ratio",
            places=8,
            sample_size=_ROLLING_RETURN_WINDOW,
            period_start=series.bars[-(_ROLLING_RETURN_WINDOW + 1)].date,
            period_end=end,
            snapshot_ids=series.snapshot_ids,
        )

    aligned = _align_returns(series, benchmark)
    paired = _paired_return_metrics(aligned)
    return (
        latest_daily,
        total_metric,
        annualized_metric,
        cagr_metric,
        rolling_metric,
        *paired,
    )


def calculate_risk_metrics(
    series: CleanPriceSeries,
    benchmark: CleanPriceSeries,
    risk_free_rate_annual: str,
    minimum_accepted_return_annual: str,
) -> tuple[Metric, ...]:
    """Calculate complete standalone and aligned benchmark risk metrics."""
    prices = _prices(series)
    returns = _simple_returns(prices)
    start, end = _period(series)
    snapshot_ids = series.snapshot_ids
    risk_free_daily = _daily_rate(risk_free_rate_annual, "riskFreeRateAnnual")
    minimum_accepted_daily = _daily_rate(
        minimum_accepted_return_annual,
        "minimumAcceptedReturnAnnual",
    )

    if len(returns) < _MINIMUM_RISK_RETURNS:
        daily_volatility = _insufficient_metric(
            name="daily_volatility",
            unit="ratio",
            minimum="30 daily returns",
            sample_size=len(returns),
            series=series,
        )
        annualized_volatility = _insufficient_metric(
            name="annualized_volatility",
            unit="ratio",
            minimum="30 daily returns",
            sample_size=len(returns),
            series=series,
        )
        downside_deviation = _insufficient_metric(
            name="downside_deviation",
            unit="ratio",
            minimum="30 daily returns",
            sample_size=len(returns),
            series=series,
        )
    else:
        daily_std = statistics.stdev(returns)
        daily_volatility = available_metric(
            name="daily_volatility",
            value=daily_std,
            unit="ratio",
            places=8,
            sample_size=len(returns),
            period_start=start,
            period_end=end,
            snapshot_ids=snapshot_ids,
        )
        annualized_volatility = available_metric(
            name="annualized_volatility",
            value=daily_std * math.sqrt(TRADING_DAYS_PER_YEAR),
            unit="ratio",
            places=8,
            sample_size=len(returns),
            period_start=start,
            period_end=end,
            snapshot_ids=snapshot_ids,
        )
        downside_daily = _downside_deviation_daily(returns, minimum_accepted_daily)
        downside_deviation = available_metric(
            name="downside_deviation",
            value=downside_daily * math.sqrt(TRADING_DAYS_PER_YEAR),
            unit="ratio",
            places=8,
            sample_size=len(returns),
            period_start=start,
            period_end=end,
            snapshot_ids=snapshot_ids,
        )

    max_drawdown_value, duration_value, open_drawdown = _drawdown_statistics(prices)
    max_drawdown = available_metric(
        name="max_drawdown",
        value=max_drawdown_value,
        unit="ratio",
        places=8,
        sample_size=len(prices),
        period_start=start,
        period_end=end,
        snapshot_ids=snapshot_ids,
    )
    duration_warnings: tuple[AnalyticsWarning, ...] = ()
    if open_drawdown:
        duration_warnings = (
            warning(
                "OPEN_DRAWDOWN",
                "The final drawdown is still open at the end of the requested period.",
                "drawdown_duration",
            ),
        )
    drawdown_duration = available_metric(
        name="drawdown_duration",
        value=float(duration_value),
        unit="trading_days",
        places=0,
        sample_size=len(prices),
        period_start=start,
        period_end=end,
        snapshot_ids=snapshot_ids,
        warnings=duration_warnings,
    )

    var_metric, cvar_metric = _tail_risk_metrics(returns, series)
    sharpe = _ratio_metric(
        name="sharpe_ratio",
        returns=returns,
        hurdle_daily=risk_free_daily,
        denominator=(statistics.stdev(returns) if len(returns) >= _MINIMUM_PRICES else 0.0),
        denominator_label="daily return volatility",
        series=series,
    )
    downside_daily = _downside_deviation_daily(returns, minimum_accepted_daily) if returns else 0.0
    sortino = _ratio_metric(
        name="sortino_ratio",
        returns=returns,
        hurdle_daily=minimum_accepted_daily,
        denominator=downside_daily,
        denominator_label="downside deviation",
        series=series,
    )
    calmar = _calmar_metric(returns, max_drawdown_value, series)
    beta, alpha, correlation = _paired_risk_metrics(
        _align_returns(series, benchmark),
        risk_free_daily,
    )
    return (
        daily_volatility,
        annualized_volatility,
        downside_deviation,
        max_drawdown,
        drawdown_duration,
        var_metric,
        cvar_metric,
        sharpe,
        sortino,
        calmar,
        beta,
        alpha,
        correlation,
    )


def _paired_return_metrics(aligned: _AlignedReturns) -> tuple[Metric, ...]:
    names = (
        ("benchmark_total_return", "ratio"),
        ("excess_total_return", "ratio"),
        ("benchmark_annualized_return", "ratio"),
        ("excess_annualized_return", "ratio"),
    )
    if len(aligned.asset) < _MINIMUM_ANNUALIZED_RETURNS:
        return tuple(
            _unavailable_aligned_metric(
                name=name,
                unit=unit,
                minimum="30 aligned daily returns",
                aligned=aligned,
            )
            for name, unit in names
        )
    asset_total = _cumulative_return(aligned.asset)
    benchmark_total = _cumulative_return(aligned.benchmark)
    asset_annualized = _annualized_return(aligned.asset)
    benchmark_annualized = _annualized_return(aligned.benchmark)
    return (
        _available_aligned_metric(
            "benchmark_total_return",
            benchmark_total,
            aligned,
        ),
        _available_aligned_metric(
            "excess_total_return",
            asset_total - benchmark_total,
            aligned,
        ),
        _available_aligned_metric(
            "benchmark_annualized_return",
            benchmark_annualized,
            aligned,
        ),
        _available_aligned_metric(
            "excess_annualized_return",
            asset_annualized - benchmark_annualized,
            aligned,
        ),
    )


def _tail_risk_metrics(
    returns: list[float],
    series: CleanPriceSeries,
) -> tuple[Metric, Metric]:
    if len(returns) < _MINIMUM_VAR_RETURNS:
        return (
            _insufficient_metric(
                name="historical_var_95",
                unit="ratio",
                minimum="100 daily returns",
                sample_size=len(returns),
                series=series,
            ),
            _insufficient_metric(
                name="historical_cvar_95",
                unit="ratio",
                minimum="100 daily returns",
                sample_size=len(returns),
                series=series,
            ),
        )
    quantile = _linear_quantile(returns, 0.05)
    tail = [item for item in returns if item <= quantile]
    start, end = _period(series)
    return (
        available_metric(
            name="historical_var_95",
            value=max(0.0, -quantile),
            unit="ratio",
            places=8,
            sample_size=len(returns),
            period_start=start,
            period_end=end,
            snapshot_ids=series.snapshot_ids,
        ),
        available_metric(
            name="historical_cvar_95",
            value=max(0.0, -statistics.fmean(tail)),
            unit="ratio",
            places=8,
            sample_size=len(returns),
            period_start=start,
            period_end=end,
            snapshot_ids=series.snapshot_ids,
        ),
    )


def _ratio_metric(  # noqa: PLR0913 - keeps the ratio policy inputs explicit.
    *,
    name: str,
    returns: list[float],
    hurdle_daily: float,
    denominator: float,
    denominator_label: str,
    series: CleanPriceSeries,
) -> Metric:
    if len(returns) < _MINIMUM_RISK_RETURNS:
        return _insufficient_metric(
            name=name,
            unit="ratio",
            minimum="30 daily returns",
            sample_size=len(returns),
            series=series,
        )
    if abs(denominator) < ZERO_DENOMINATOR:
        return _zero_denominator_metric(
            name=name,
            message=f"{name} is unavailable because {denominator_label} is zero.",
            sample_size=len(returns),
            series=series,
        )
    value = (statistics.fmean(returns) - hurdle_daily) / denominator
    start, end = _period(series)
    return available_metric(
        name=name,
        value=value * math.sqrt(TRADING_DAYS_PER_YEAR),
        unit="ratio",
        places=8,
        sample_size=len(returns),
        period_start=start,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
    )


def _calmar_metric(
    returns: list[float],
    max_drawdown: float,
    series: CleanPriceSeries,
) -> Metric:
    if len(returns) < _MINIMUM_ANNUALIZED_RETURNS:
        return _insufficient_metric(
            name="calmar_ratio",
            unit="ratio",
            minimum="30 daily returns",
            sample_size=len(returns),
            series=series,
        )
    if abs(max_drawdown) < ZERO_DENOMINATOR:
        return _zero_denominator_metric(
            name="calmar_ratio",
            message="Calmar ratio is unavailable because maximum drawdown is zero.",
            sample_size=len(returns),
            series=series,
        )
    start, end = _period(series)
    return available_metric(
        name="calmar_ratio",
        value=_annualized_return(returns) / abs(max_drawdown),
        unit="ratio",
        places=8,
        sample_size=len(returns),
        period_start=start,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
    )


def _paired_risk_metrics(
    aligned: _AlignedReturns,
    risk_free_daily: float,
) -> tuple[Metric, Metric, Metric]:
    names = ("beta", "alpha", "correlation")
    if len(aligned.asset) < _MINIMUM_PAIRED_RETURNS:
        return tuple(
            _unavailable_aligned_metric(
                name=name,
                unit="ratio",
                minimum="60 aligned daily returns",
                aligned=aligned,
            )
            for name in names
        )  # type: ignore[return-value]
    benchmark_variance = statistics.variance(aligned.benchmark)
    if abs(benchmark_variance) < ZERO_DENOMINATOR:
        return (
            _zero_denominator_aligned_metric(
                name="beta",
                message="beta is unavailable because benchmark variance is zero.",
                aligned=aligned,
            ),
            _zero_denominator_aligned_metric(
                name="alpha",
                message="alpha is unavailable because benchmark variance is zero.",
                aligned=aligned,
            ),
            _zero_denominator_aligned_metric(
                name="correlation",
                message="correlation is unavailable because benchmark variance is zero.",
                aligned=aligned,
            ),
        )

    beta_value = statistics.covariance(aligned.asset, aligned.benchmark) / benchmark_variance
    alpha_value = (
        (statistics.fmean(aligned.asset) - risk_free_daily)
        - beta_value * (statistics.fmean(aligned.benchmark) - risk_free_daily)
    ) * TRADING_DAYS_PER_YEAR
    beta = _available_aligned_metric("beta", beta_value, aligned)
    alpha = _available_aligned_metric("alpha", alpha_value, aligned)
    if statistics.stdev(aligned.asset) < ZERO_DENOMINATOR:
        correlation = _zero_denominator_aligned_metric(
            name="correlation",
            message="Correlation is unavailable because asset return volatility is zero.",
            aligned=aligned,
        )
    else:
        correlation = _available_aligned_metric(
            "correlation",
            max(-1.0, min(1.0, statistics.correlation(aligned.asset, aligned.benchmark))),
            aligned,
        )
    return beta, alpha, correlation


def _align_returns(series: CleanPriceSeries, benchmark: CleanPriceSeries) -> _AlignedReturns:
    asset_by_date = _returns_by_date(series)
    benchmark_by_date = _returns_by_date(benchmark)
    common_dates = tuple(sorted(set(asset_by_date) & set(benchmark_by_date)))
    alignment_warnings: tuple[AnalyticsWarning, ...] = ()
    if benchmark_by_date and len(common_dates) < min(len(asset_by_date), len(benchmark_by_date)):
        alignment_warnings = (
            warning(
                "BENCHMARK_ALIGNMENT_REDUCED_SAMPLE",
                "Asset and benchmark returns were inner-joined on common trading dates.",
                None,
            ),
        )
    return _AlignedReturns(
        dates=common_dates,
        asset=tuple(asset_by_date[item] for item in common_dates),
        benchmark=tuple(benchmark_by_date[item] for item in common_dates),
        snapshot_ids=tuple(sorted(set(series.snapshot_ids) | set(benchmark.snapshot_ids))),
        warnings=alignment_warnings,
    )


def _returns_by_date(series: CleanPriceSeries) -> dict[date, float]:
    return {
        current.date: float(current.adjusted_close / previous.adjusted_close - Decimal(1))
        for previous, current in pairwise(series.bars)
    }


def _drawdown_statistics(prices: list[float]) -> tuple[float, int, bool]:
    running_max = prices[0]
    maximum_drawdown = 0.0
    maximum_duration = 0
    current_duration = 0
    for price in prices[1:]:
        if price >= running_max:
            running_max = price
            current_duration = 0
        else:
            current_duration += 1
            maximum_duration = max(maximum_duration, current_duration)
        maximum_drawdown = min(maximum_drawdown, price / running_max - 1.0)
    return maximum_drawdown, maximum_duration, current_duration > 0


def _annualized_metric(
    *,
    name: str,
    returns: list[float],
    series: CleanPriceSeries,
) -> Metric:
    if len(returns) < _MINIMUM_ANNUALIZED_RETURNS:
        return _insufficient_metric(
            name=name,
            unit="ratio",
            minimum="30 daily returns",
            sample_size=len(returns),
            series=series,
        )
    start, end = _period(series)
    return available_metric(
        name=name,
        value=_annualized_return(returns),
        unit="ratio",
        places=8,
        sample_size=len(returns),
        period_start=start,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
    )


def _annualized_return(returns: tuple[float, ...] | list[float]) -> float:
    return math.prod(1.0 + item for item in returns) ** (TRADING_DAYS_PER_YEAR / len(returns)) - 1.0


def _cumulative_return(returns: tuple[float, ...] | list[float]) -> float:
    return math.prod(1.0 + item for item in returns) - 1.0


def _downside_deviation_daily(returns: list[float], hurdle_daily: float) -> float:
    return math.sqrt(statistics.fmean(min(item - hurdle_daily, 0.0) ** 2 for item in returns))


def _daily_rate(annual_value: str, field: str) -> float:
    annual_rate = decimal_from_string(annual_value, field)
    if annual_rate <= Decimal(-1):
        raise AnalyticsDomainError(
            "INVALID_ANNUAL_RATE",
            f"{field} must be greater than -1.",
        )
    return math.pow(1.0 + float(annual_rate), 1.0 / TRADING_DAYS_PER_YEAR) - 1.0


def _linear_quantile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def _prices(series: CleanPriceSeries) -> list[float]:
    if len(series.bars) < _MINIMUM_PRICES:
        raise AnalyticsDomainError(
            "INSUFFICIENT_DATA",
            "At least two valid prices are required.",
        )
    return [float(bar.adjusted_close) for bar in series.bars]


def _simple_returns(prices: list[float]) -> list[float]:
    return [current / previous - 1.0 for previous, current in pairwise(prices)]


def _period(series: CleanPriceSeries) -> tuple[date, date]:
    return series.bars[0].date, series.bars[-1].date


def _insufficient_metric(
    *,
    name: str,
    unit: str,
    minimum: str,
    sample_size: int,
    series: CleanPriceSeries,
) -> Metric:
    start, end = _period(series)
    return unavailable_metric(
        name=name,
        unit=unit,
        sample_size=sample_size,
        period_start=start,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
        warnings=(warning("INSUFFICIENT_DATA", f"{name} requires {minimum}.", name),),
    )


def _unavailable_aligned_metric(
    *,
    name: str,
    unit: str,
    minimum: str,
    aligned: _AlignedReturns,
) -> Metric:
    period_start = aligned.dates[0] if aligned.dates else None
    period_end = aligned.dates[-1] if aligned.dates else None
    warnings = (
        *aligned.warnings,
        warning("INSUFFICIENT_DATA", f"{name} requires {minimum}.", name),
    )
    return unavailable_metric(
        name=name,
        unit=unit,
        sample_size=len(aligned.asset),
        period_start=period_start,
        period_end=period_end,
        snapshot_ids=aligned.snapshot_ids,
        warnings=warnings,
    )


def _available_aligned_metric(
    name: str,
    value: float,
    aligned: _AlignedReturns,
) -> Metric:
    return available_metric(
        name=name,
        value=value,
        unit="ratio",
        places=8,
        sample_size=len(aligned.asset),
        period_start=aligned.dates[0],
        period_end=aligned.dates[-1],
        snapshot_ids=aligned.snapshot_ids,
        warnings=aligned.warnings,
    )


def _zero_denominator_metric(
    *,
    name: str,
    message: str,
    sample_size: int,
    series: CleanPriceSeries,
) -> Metric:
    start, end = _period(series)
    return unavailable_metric(
        name=name,
        unit="ratio",
        sample_size=sample_size,
        period_start=start,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
        warnings=(warning("ZERO_DENOMINATOR", message, name),),
    )


def _zero_denominator_aligned_metric(
    *,
    name: str,
    message: str,
    aligned: _AlignedReturns,
) -> Metric:
    return unavailable_metric(
        name=name,
        unit="ratio",
        sample_size=len(aligned.asset),
        period_start=aligned.dates[0],
        period_end=aligned.dates[-1],
        snapshot_ids=aligned.snapshot_ids,
        warnings=(*aligned.warnings, warning("ZERO_DENOMINATOR", message, name)),
    )
