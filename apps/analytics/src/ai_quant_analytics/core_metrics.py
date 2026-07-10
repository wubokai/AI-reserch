"""Deterministic Phase 3 return, risk, and technical indicators."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

import math
import statistics
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
_MINIMUM_RISK_RETURNS = 30
_MINIMUM_RSI_PRICES = 15
_MINIMUM_MACD_PRICES = 26
_MINIMUM_SIGNAL_VALUES = 9

if TYPE_CHECKING:
    from ai_quant_analytics.contracts import AnalyticsWarning, Metric


def calculate_return_metrics(series: CleanPriceSeries) -> tuple[Metric, ...]:
    """Calculate total return and CAGR in stable public order."""
    prices = _prices(series)
    start, end = _period(series)
    total_return = prices[-1] / prices[0] - 1.0
    total_metric = available_metric(
        name="total_return",
        value=total_return,
        unit="ratio",
        places=8,
        sample_size=len(prices),
        period_start=start,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
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
    return total_metric, cagr_metric


def calculate_risk_metrics(
    series: CleanPriceSeries,
    risk_free_rate_annual: str,
) -> tuple[Metric, ...]:
    """Calculate volatility, maximum drawdown, and Sharpe ratio."""
    prices = _prices(series)
    returns = _simple_returns(prices)
    start, end = _period(series)
    snapshot_ids = series.snapshot_ids

    if len(returns) < _MINIMUM_RISK_RETURNS:
        volatility_metric = _insufficient_metric(
            name="annualized_volatility",
            unit="ratio",
            minimum="30 daily returns",
            sample_size=len(returns),
            series=series,
        )
    else:
        daily_std = statistics.stdev(returns)
        volatility_metric = available_metric(
            name="annualized_volatility",
            value=daily_std * math.sqrt(TRADING_DAYS_PER_YEAR),
            unit="ratio",
            places=8,
            sample_size=len(returns),
            period_start=start,
            period_end=end,
            snapshot_ids=snapshot_ids,
        )

    running_max = prices[0]
    max_drawdown = 0.0
    for price in prices:
        running_max = max(running_max, price)
        max_drawdown = min(max_drawdown, price / running_max - 1.0)
    drawdown_metric = available_metric(
        name="max_drawdown",
        value=max_drawdown,
        unit="ratio",
        places=8,
        sample_size=len(prices),
        period_start=start,
        period_end=end,
        snapshot_ids=snapshot_ids,
    )

    sharpe_metric = _calculate_sharpe(
        returns,
        risk_free_rate_annual,
        series,
    )
    return volatility_metric, drawdown_metric, sharpe_metric


def calculate_technical_metrics(series: CleanPriceSeries) -> tuple[Metric, ...]:
    """Calculate final RSI-14, MACD, and MACD signal values."""
    prices = _prices(series)
    start, end = _period(series)
    snapshot_ids = series.snapshot_ids

    if len(prices) < _MINIMUM_RSI_PRICES:
        rsi_metric = _insufficient_metric(
            name="rsi_14",
            unit="index",
            minimum="15 prices",
            sample_size=len(prices),
            series=series,
        )
    else:
        rsi_metric = available_metric(
            name="rsi_14",
            value=_rsi_14(prices),
            unit="index",
            places=8,
            sample_size=len(prices),
            period_start=start,
            period_end=end,
            snapshot_ids=snapshot_ids,
        )

    if len(prices) < _MINIMUM_MACD_PRICES:
        macd_metric = _insufficient_metric(
            name="macd",
            unit="price",
            minimum="26 prices",
            sample_size=len(prices),
            series=series,
        )
        signal_metric = _insufficient_metric(
            name="macd_signal",
            unit="price",
            minimum="34 prices",
            sample_size=len(prices),
            series=series,
        )
    else:
        macd_values = _macd_values(prices)
        macd_metric = available_metric(
            name="macd",
            value=macd_values[-1],
            unit="price",
            places=4,
            sample_size=len(prices),
            period_start=start,
            period_end=end,
            snapshot_ids=snapshot_ids,
        )
        if len(macd_values) < _MINIMUM_SIGNAL_VALUES:
            signal_metric = _insufficient_metric(
                name="macd_signal",
                unit="price",
                minimum="34 prices",
                sample_size=len(prices),
                series=series,
            )
        else:
            signal_metric = available_metric(
                name="macd_signal",
                value=_ema(macd_values, 9)[-1],
                unit="price",
                places=4,
                sample_size=len(prices),
                period_start=start,
                period_end=end,
                snapshot_ids=snapshot_ids,
            )
    return rsi_metric, macd_metric, signal_metric


def _calculate_sharpe(
    returns: list[float],
    risk_free_rate_annual: str,
    series: CleanPriceSeries,
) -> Metric:
    if len(returns) < _MINIMUM_RISK_RETURNS:
        return _insufficient_metric(
            name="sharpe_ratio",
            unit="ratio",
            minimum="30 daily returns",
            sample_size=len(returns),
            series=series,
        )
    annual_rate = decimal_from_string(risk_free_rate_annual, "riskFreeRateAnnual")
    if annual_rate <= Decimal(-1):
        raise AnalyticsDomainError(
            "INVALID_RISK_FREE_RATE",
            "riskFreeRateAnnual must be greater than -1.",
        )
    daily_rate = (1.0 + float(annual_rate)) ** (1.0 / TRADING_DAYS_PER_YEAR) - 1.0
    daily_std = statistics.stdev(returns)
    if abs(daily_std) < ZERO_DENOMINATOR:
        metric_warning = warning(
            "ZERO_DENOMINATOR",
            "Sharpe ratio is unavailable because daily return volatility is zero.",
            "sharpe_ratio",
        )
        start, end = _period(series)
        return unavailable_metric(
            name="sharpe_ratio",
            unit="ratio",
            sample_size=len(returns),
            period_start=start,
            period_end=end,
            snapshot_ids=series.snapshot_ids,
            warnings=(metric_warning,),
        )
    value = (statistics.fmean(returns) - daily_rate) / daily_std
    return available_metric(
        name="sharpe_ratio",
        value=value * math.sqrt(TRADING_DAYS_PER_YEAR),
        unit="ratio",
        places=8,
        sample_size=len(returns),
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
    )


def _rsi_14(prices: list[float]) -> float:
    deltas = [current - previous for previous, current in pairwise(prices)]
    gains = [max(delta, 0.0) for delta in deltas]
    losses = [max(-delta, 0.0) for delta in deltas]
    average_gain = statistics.fmean(gains[:14])
    average_loss = statistics.fmean(losses[:14])
    for gain, loss in zip(gains[14:], losses[14:], strict=True):
        average_gain = (average_gain * 13.0 + gain) / 14.0
        average_loss = (average_loss * 13.0 + loss) / 14.0
    if average_gain < ZERO_DENOMINATOR and average_loss < ZERO_DENOMINATOR:
        return 50.0
    if average_loss < ZERO_DENOMINATOR:
        return 100.0
    if average_gain < ZERO_DENOMINATOR:
        return 0.0
    relative_strength = average_gain / average_loss
    return 100.0 - 100.0 / (1.0 + relative_strength)


def _macd_values(prices: list[float]) -> list[float]:
    ema_12 = _ema(prices, 12)
    ema_26 = _ema(prices, 26)
    return [ema_12[index] - ema_26[index] for index in range(25, len(prices))]


def _ema(values: list[float], span: int) -> list[float]:
    alpha = 2.0 / (span + 1.0)
    result = [values[0]]
    for value in values[1:]:
        result.append(alpha * value + (1.0 - alpha) * result[-1])
    return result


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
    metric_warning: AnalyticsWarning = warning(
        "INSUFFICIENT_DATA",
        f"{name} requires {minimum}.",
        name,
    )
    start, end = _period(series)
    return unavailable_metric(
        name=name,
        unit=unit,
        sample_size=sample_size,
        period_start=start,
        period_end=end,
        snapshot_ids=series.snapshot_ids,
        warnings=(metric_warning,),
    )
