"""Deterministic technical indicators and explainable trend classification."""

from __future__ import annotations

import statistics
from itertools import pairwise
from typing import TYPE_CHECKING, Literal

from ai_quant_analytics.contracts import (
    CALCULATION_VERSION,
    TrendClassification,
    TrendResult,
    TrendSignals,
)
from ai_quant_analytics.serialization import available_metric, unavailable_metric, warning

ZERO_DENOMINATOR = 1e-12
_RSI_MINIMUM_PRICES = 15
_MACD_MINIMUM_PRICES = 26
_MACD_SIGNAL_MINIMUM_VALUES = 9
_BOLLINGER_WINDOW = 20
_ATR_WINDOW = 14
_TREND_MINIMUM_PRICES = 200
_STRONG_UPTREND_MINIMUM = 4
_UPTREND_MINIMUM = 2
_DOWNTREND_MINIMUM = -3

if TYPE_CHECKING:
    from ai_quant_analytics.contracts import Metric
    from ai_quant_analytics.normalization import CleanPriceSeries


def calculate_technical_metrics(
    series: CleanPriceSeries,
) -> tuple[tuple[Metric, ...], TrendResult | None]:
    """Calculate the complete scalar technical set and deterministic trend label."""
    prices = [float(item.adjusted_close) for item in series.bars]
    volumes = [float(item.volume) for item in series.bars]
    metrics: list[Metric] = [
        _moving_metric(
            name=f"sma_{window}",
            window=window,
            values=prices,
            series=series,
            method="sma",
        )
        for window in (20, 50, 200)
    ]
    metrics.extend(
        _moving_metric(
            name=f"ema_{span}",
            window=span,
            values=prices,
            series=series,
            method="ema",
        )
        for span in (12, 26)
    )

    metrics.extend(_momentum_metrics(prices, series))
    metrics.extend(_bollinger_metrics(prices, series))
    metrics.append(_atr_metric(series))
    metrics.extend(_range_position_metrics(prices, series))
    metrics.append(
        _moving_metric(
            name="volume_moving_average_20",
            window=20,
            values=volumes,
            series=series,
            method="sma",
            unit="shares",
            places=2,
        ),
    )
    trend_score, trend = _trend_metrics(prices, series)
    metrics.append(trend_score)
    return tuple(metrics), trend


def _moving_metric(  # noqa: PLR0913 - the moving-window contract is explicit.
    *,
    name: str,
    window: int,
    values: list[float],
    series: CleanPriceSeries,
    method: Literal["sma", "ema"],
    unit: str = "price",
    places: int = 4,
) -> Metric:
    if len(values) < window:
        return _insufficient_metric(name, unit, window, len(values), series)
    value = statistics.fmean(values[-window:]) if method == "sma" else _ema(values, window)[-1]
    return available_metric(
        name=name,
        value=value,
        unit=unit,
        places=places,
        sample_size=window if method == "sma" else len(values),
        period_start=series.bars[-window].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
    )


def _momentum_metrics(
    prices: list[float],
    series: CleanPriceSeries,
) -> tuple[Metric, ...]:
    if len(prices) < _RSI_MINIMUM_PRICES:
        rsi = _insufficient_metric(
            "rsi_14",
            "index",
            _RSI_MINIMUM_PRICES,
            len(prices),
            series,
        )
    else:
        rsi = available_metric(
            name="rsi_14",
            value=_rsi_14(prices),
            unit="index",
            places=8,
            sample_size=len(prices),
            period_start=series.bars[0].date,
            period_end=series.bars[-1].date,
            snapshot_ids=series.snapshot_ids,
        )
    if len(prices) < _MACD_MINIMUM_PRICES:
        return (
            rsi,
            _insufficient_metric(
                "macd",
                "price",
                _MACD_MINIMUM_PRICES,
                len(prices),
                series,
            ),
            _insufficient_metric("macd_signal", "price", 34, len(prices), series),
        )
    macd_values = _macd_values(prices)
    macd = available_metric(
        name="macd",
        value=macd_values[-1],
        unit="price",
        places=4,
        sample_size=len(prices),
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
    )
    if len(macd_values) < _MACD_SIGNAL_MINIMUM_VALUES:
        signal = _insufficient_metric("macd_signal", "price", 34, len(prices), series)
    else:
        signal = available_metric(
            name="macd_signal",
            value=_ema(macd_values, 9)[-1],
            unit="price",
            places=4,
            sample_size=len(macd_values),
            period_start=series.bars[25].date,
            period_end=series.bars[-1].date,
            snapshot_ids=series.snapshot_ids,
        )
    return rsi, macd, signal


def _bollinger_metrics(
    prices: list[float],
    series: CleanPriceSeries,
) -> tuple[Metric, Metric, Metric]:
    names = (
        "bollinger_upper_20",
        "bollinger_middle_20",
        "bollinger_lower_20",
    )
    if len(prices) < _BOLLINGER_WINDOW:
        return tuple(
            _insufficient_metric(
                name,
                "price",
                _BOLLINGER_WINDOW,
                len(prices),
                series,
            )
            for name in names
        )  # type: ignore[return-value]
    window = prices[-_BOLLINGER_WINDOW:]
    middle = statistics.fmean(window)
    band = 2.0 * statistics.stdev(window)
    period_start = series.bars[-_BOLLINGER_WINDOW].date
    period_end = series.bars[-1].date
    return (
        available_metric(
            name=names[0],
            value=middle + band,
            unit="price",
            places=4,
            sample_size=_BOLLINGER_WINDOW,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=series.snapshot_ids,
        ),
        available_metric(
            name=names[1],
            value=middle,
            unit="price",
            places=4,
            sample_size=_BOLLINGER_WINDOW,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=series.snapshot_ids,
        ),
        available_metric(
            name=names[2],
            value=middle - band,
            unit="price",
            places=4,
            sample_size=_BOLLINGER_WINDOW,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=series.snapshot_ids,
        ),
    )


def _atr_metric(series: CleanPriceSeries) -> Metric:
    if len(series.bars) < _ATR_WINDOW:
        return _insufficient_metric(
            "atr_14",
            "price",
            _ATR_WINDOW,
            len(series.bars),
            series,
        )
    adjusted: list[tuple[float, float, float]] = []
    for item in series.bars:
        factor = float(item.adjusted_close / item.close)
        adjusted.append(
            (
                float(item.high) * factor,
                float(item.low) * factor,
                float(item.close) * factor,
            ),
        )
    true_ranges: list[float] = []
    for index, (high, low, _close) in enumerate(adjusted):
        if index == 0:
            true_ranges.append(high - low)
        else:
            previous_close = adjusted[index - 1][2]
            true_ranges.append(
                max(high - low, abs(high - previous_close), abs(low - previous_close)),
            )
    atr = statistics.fmean(true_ranges[:_ATR_WINDOW])
    for value in true_ranges[_ATR_WINDOW:]:
        atr = (atr * (_ATR_WINDOW - 1.0) + value) / _ATR_WINDOW
    return available_metric(
        name="atr_14",
        value=atr,
        unit="price",
        places=4,
        sample_size=len(true_ranges),
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
    )


def _range_position_metrics(
    prices: list[float],
    series: CleanPriceSeries,
) -> tuple[Metric, Metric]:
    window_size = min(252, len(prices))
    window = prices[-window_size:]
    current = prices[-1]
    highest = max(window)
    lowest = min(window)
    period_start = series.bars[-window_size].date
    period_end = series.bars[-1].date
    return (
        available_metric(
            name="distance_from_52_week_high",
            value=current / highest - 1.0,
            unit="ratio",
            places=8,
            sample_size=window_size,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=series.snapshot_ids,
        ),
        available_metric(
            name="distance_from_52_week_low",
            value=current / lowest - 1.0,
            unit="ratio",
            places=8,
            sample_size=window_size,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=series.snapshot_ids,
        ),
    )


def _trend_metrics(
    prices: list[float],
    series: CleanPriceSeries,
) -> tuple[Metric, TrendResult | None]:
    if len(prices) < _TREND_MINIMUM_PRICES:
        return (
            _insufficient_metric(
                "trend_score",
                "score",
                _TREND_MINIMUM_PRICES,
                len(prices),
                series,
            ),
            None,
        )
    close = prices[-1]
    sma20 = statistics.fmean(prices[-20:])
    sma50 = statistics.fmean(prices[-50:])
    sma200 = statistics.fmean(prices[-200:])
    prior_sma50 = statistics.fmean(prices[-70:-20])
    slope = sma50 / prior_sma50 - 1.0
    distance = close / sma200 - 1.0
    signals = TrendSignals(
        close_above_sma20=1 if close > sma20 else -1,
        sma20_above_sma50=1 if sma20 > sma50 else -1,
        sma50_above_sma200=1 if sma50 > sma200 else -1,
        sma50_slope_20=_threshold_signal(slope, 0.01),
        close_distance_sma200=_threshold_signal(distance, 0.05),
    )
    score = sum(
        (
            signals.close_above_sma20,
            signals.sma20_above_sma50,
            signals.sma50_above_sma200,
            signals.sma50_slope_20,
            signals.close_distance_sma200,
        ),
    )
    classification = _classification(score)
    score_metric = available_metric(
        name="trend_score",
        value=float(score),
        unit="score",
        places=0,
        sample_size=len(prices),
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
    )
    trend = TrendResult(
        classification=classification,
        score=score,
        signals=signals,
        sample_size=len(prices),
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        calculation_version=CALCULATION_VERSION,
        input_snapshot_ids=series.snapshot_ids,
        warnings=(),
    )
    return score_metric, trend


def _threshold_signal(value: float, threshold: float) -> Literal[-1, 0, 1]:
    if value > threshold:
        return 1
    if value < -threshold:
        return -1
    return 0


def _classification(score: int) -> TrendClassification:
    if score >= _STRONG_UPTREND_MINIMUM:
        return TrendClassification.STRONG_UPTREND
    if score >= _UPTREND_MINIMUM:
        return TrendClassification.UPTREND
    if score >= -1:
        return TrendClassification.RANGE
    if score >= _DOWNTREND_MINIMUM:
        return TrendClassification.DOWNTREND
    return TrendClassification.STRONG_DOWNTREND


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


def _insufficient_metric(
    name: str,
    unit: str,
    minimum: int,
    sample_size: int,
    series: CleanPriceSeries,
) -> Metric:
    return unavailable_metric(
        name=name,
        unit=unit,
        sample_size=sample_size,
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
        warnings=(
            warning(
                "INSUFFICIENT_DATA",
                f"{name} requires at least {minimum} prices.",
                name,
            ),
        ),
    )
