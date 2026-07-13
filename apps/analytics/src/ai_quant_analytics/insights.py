"""Deterministic chart overlays and valuation-explanation calculations."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

import calendar
from collections import deque
from datetime import date
from decimal import Decimal, localcontext
from typing import Literal

from ai_quant_analytics.contracts import (
    INSIGHTS_CALCULATION_VERSION,
    INSIGHTS_RESPONSE_SCHEMA_VERSION,
    FullAnalysisRequest,
    InsightPricePoint,
    InsightRangeStat,
    InsightSensitivityMatrix,
    InsightSensitivityRow,
    InsightsResponse,
    InsightTechnicalSummary,
    InsightValuation,
    ScenarioAssumption,
    ScenarioInput,
    ScenarioName,
)
from ai_quant_analytics.errors import AnalyticsDomainError
from ai_quant_analytics.normalization import (
    NormalizedPriceBar,
    clean_price_series,
    decimal_from_string,
)

_DISPLAY_PLACES = Decimal("0.00000001")
_SHORT_WINDOW = 20
_LONG_WINDOW = 50


def calculate_insights(request: FullAnalysisRequest) -> InsightsResponse:
    """Calculate moving-average overlays and scenario explanation from registered inputs."""
    if request.scenario_input is None:
        raise AnalyticsDomainError(
            "SCENARIO_INPUT_REQUIRED",
            "scenarioInput is required for the insights endpoint.",
        )
    series = clean_price_series(
        request.prices,
        period_start=request.period_start,
        period_end=request.period_end,
        series_name="prices",
    )
    price_points = _moving_averages(series.bars)
    technical_summary = _technical_summary(price_points, series.bars[-1].adjusted_close)
    valuation = _valuation(request.scenario_input)
    return InsightsResponse(
        schema_version=INSIGHTS_RESPONSE_SCHEMA_VERSION,
        calculation_version=INSIGHTS_CALCULATION_VERSION,
        input_hash=request.input_hash,
        symbol=request.symbol,
        price_points=price_points,
        range_stats=_range_stats(series.bars),
        technical_summary=technical_summary,
        valuation=valuation,
    )


def _range_stats(bars: tuple[NormalizedPriceBar, ...]) -> tuple[InsightRangeStat, ...]:
    latest = bars[-1].date
    starts: tuple[tuple[Literal["3M", "1Y", "3Y", "MAX"], date], ...] = (
        ("3M", _subtract_months(latest, 3)),
        ("1Y", _subtract_years(latest, 1)),
        ("3Y", _subtract_years(latest, 3)),
        ("MAX", bars[0].date),
    )
    result: list[InsightRangeStat] = []
    for range_name, start in starts:
        selected = tuple(bar for bar in bars if bar.date >= start)
        first = selected[0].adjusted_close
        last = selected[-1].adjusted_close
        result.append(
            InsightRangeStat(
                range=range_name,
                period_start=selected[0].date,
                period_end=selected[-1].date,
                first_price=_decimal(first),
                last_price=_decimal(last),
                period_return=_decimal(last / first - 1),
                high=_decimal(max(bar.high for bar in selected)),
                low=_decimal(min(bar.low for bar in selected)),
                average_volume=_decimal(
                    sum((bar.volume for bar in selected), start=Decimal(0))
                    / Decimal(len(selected)),
                ),
            ),
        )
    return tuple(result)


def _subtract_months(value: date, months: int) -> date:
    total = value.year * 12 + value.month - 1 - months
    year, zero_based_month = divmod(total, 12)
    month = zero_based_month + 1
    return date(year, month, min(value.day, calendar.monthrange(year, month)[1]))


def _subtract_years(value: date, years: int) -> date:
    year = value.year - years
    return date(year, value.month, min(value.day, calendar.monthrange(year, value.month)[1]))


def _moving_averages(bars: tuple[NormalizedPriceBar, ...]) -> tuple[InsightPricePoint, ...]:
    window20: deque[Decimal] = deque()
    window50: deque[Decimal] = deque()
    sum20 = Decimal(0)
    sum50 = Decimal(0)
    result: list[InsightPricePoint] = []
    for bar in bars:
        adjusted_close = bar.adjusted_close
        sum20 += adjusted_close
        window20.append(adjusted_close)
        if len(window20) > _SHORT_WINDOW:
            sum20 -= window20.popleft()
        sum50 += adjusted_close
        window50.append(adjusted_close)
        if len(window50) > _LONG_WINDOW:
            sum50 -= window50.popleft()
        result.append(
            InsightPricePoint(
                date=bar.date,
                ma20=_decimal(sum20 / Decimal(_SHORT_WINDOW))
                if len(window20) == _SHORT_WINDOW
                else None,
                ma50=_decimal(sum50 / Decimal(_LONG_WINDOW))
                if len(window50) == _LONG_WINDOW
                else None,
            ),
        )
    return tuple(result)


def _technical_summary(
    points: tuple[InsightPricePoint, ...],
    current_price: Decimal,
) -> InsightTechnicalSummary:
    latest = points[-1]
    ma20 = None if latest.ma20 is None else Decimal(latest.ma20)
    ma50 = None if latest.ma50 is None else Decimal(latest.ma50)
    versus20 = _ratio_difference(current_price, ma20)
    versus50 = _ratio_difference(current_price, ma50)
    signal: Literal["ABOVE_BOTH", "BELOW_BOTH", "MIXED", "INSUFFICIENT_HISTORY"]
    if versus20 is None or versus50 is None:
        signal = "INSUFFICIENT_HISTORY"
    elif versus20 > 0 and versus50 > 0:
        signal = "ABOVE_BOTH"
    elif versus20 < 0 and versus50 < 0:
        signal = "BELOW_BOTH"
    else:
        signal = "MIXED"
    return InsightTechnicalSummary(
        current_price=_decimal(current_price),
        price_vs_ma20=None if versus20 is None else _decimal(versus20),
        price_vs_ma50=None if versus50 is None else _decimal(versus50),
        signal=signal,
    )


def _valuation(scenario_input: ScenarioInput) -> InsightValuation:
    base_revenue = decimal_from_string(scenario_input.base_revenue, "baseRevenue")
    current_price = decimal_from_string(scenario_input.current_price, "currentPrice")
    net_debt = decimal_from_string(scenario_input.net_debt, "netDebt")
    diluted_shares = decimal_from_string(scenario_input.diluted_shares, "dilutedShares")
    scenarios = {item.name: item for item in scenario_input.scenarios}
    if set(scenarios) != set(ScenarioName):
        raise AnalyticsDomainError(
            "INVALID_SCENARIO_SET",
            "Scenarios must contain exactly one BULL, BASE, and BEAR entry.",
        )
    if base_revenue <= 0 or diluted_shares <= 0 or current_price <= 0:
        raise AnalyticsDomainError(
            "INVALID_SCENARIO_BASE",
            "baseRevenue, currentPrice, and dilutedShares must be positive.",
        )
    base = scenarios[ScenarioName.BASE]
    method = base.valuation_method
    base_growth = decimal_from_string(base.revenue_growth, "base.revenueGrowth")
    base_margin = decimal_from_string(base.target_ebitda_margin, "base.targetEbitdaMargin")
    base_multiple = _multiple(base)
    market_enterprise_value = current_price * diluted_shares + net_debt
    denominator = (
        base_revenue * base_multiple
        if method == "EV_REVENUE"
        else base_revenue * base_margin * base_multiple
    )
    market_growth = None if denominator <= 0 else market_enterprise_value / denominator - 1
    weighted_price = sum(
        (
            _scenario_price(base_revenue, net_debt, diluted_shares, item)
            * decimal_from_string(item.probability, f"{item.name.value}.probability")
            for item in scenarios.values()
        ),
        start=Decimal(0),
    )
    growth_levels = _levels(
        tuple(
            decimal_from_string(item.revenue_growth, "revenueGrowth") for item in scenarios.values()
        ),
        base_growth,
        Decimal("0.10"),
        floor=None,
    )
    multiple_levels = _levels(
        tuple(_multiple(item) for item in scenarios.values()),
        base_multiple,
        Decimal(2),
        floor=Decimal("0.10"),
    )
    rows = tuple(
        InsightSensitivityRow(
            revenue_growth_rate=_decimal(growth),
            implied_prices=tuple(
                _decimal(
                    _price(
                        base_revenue,
                        net_debt,
                        diluted_shares,
                        growth,
                        base_margin,
                        multiple,
                        method,
                    ),
                )
                for multiple in multiple_levels
            ),
            upside_downside=tuple(
                _decimal(
                    _price(
                        base_revenue,
                        net_debt,
                        diluted_shares,
                        growth,
                        base_margin,
                        multiple,
                        method,
                    )
                    / current_price
                    - 1,
                )
                for multiple in multiple_levels
            ),
        )
        for growth in growth_levels
    )
    return InsightValuation(
        current_price=_decimal(current_price),
        weighted_implied_price=_decimal(weighted_price),
        premium_discount_to_weighted_value=_decimal(current_price / weighted_price - 1)
        if weighted_price > 0
        else "0",
        market_implied_revenue_growth=None if market_growth is None else _decimal(market_growth),
        market_implied_growth_gap=None
        if market_growth is None
        else _decimal(market_growth - base_growth),
        valuation_method=method,
        base_revenue_growth=_decimal(base_growth),
        base_ebitda_margin=_decimal(base_margin),
        base_valuation_multiple=_decimal(base_multiple),
        sensitivity=InsightSensitivityMatrix(
            revenue_growth_rates=tuple(_decimal(value) for value in growth_levels),
            valuation_multiples=tuple(_decimal(value) for value in multiple_levels),
            rows=rows,
        ),
    )


def _scenario_price(
    revenue: Decimal,
    net_debt: Decimal,
    diluted_shares: Decimal,
    scenario: ScenarioAssumption,
) -> Decimal:
    return _price(
        revenue,
        net_debt,
        diluted_shares,
        decimal_from_string(scenario.revenue_growth, "revenueGrowth"),
        decimal_from_string(scenario.target_ebitda_margin, "targetEbitdaMargin"),
        _multiple(scenario),
        scenario.valuation_method,
    )


def _price(  # noqa: PLR0913 - the transparent formula keeps each input explicit.
    revenue: Decimal,
    net_debt: Decimal,
    diluted_shares: Decimal,
    growth: Decimal,
    margin: Decimal,
    multiple: Decimal,
    method: str,
) -> Decimal:
    forecast_revenue = revenue * (1 + growth)
    enterprise_value = (
        forecast_revenue * multiple
        if method == "EV_REVENUE"
        else forecast_revenue * margin * multiple
    )
    return max(Decimal(0), (enterprise_value - net_debt) / diluted_shares)


def _multiple(scenario: ScenarioAssumption) -> Decimal:
    value = scenario.valuation_multiple or scenario.ev_to_ebitda_multiple
    return decimal_from_string(value, "valuationMultiple")


def _levels(
    values: tuple[Decimal, ...],
    base: Decimal,
    fallback_spread: Decimal,
    floor: Decimal | None,
) -> tuple[Decimal, ...]:
    minimum = min(values)
    maximum = max(values)
    if minimum == maximum:
        minimum = base - fallback_spread
        maximum = base + fallback_spread
    if floor is not None:
        minimum = max(floor, minimum)
    return (
        minimum,
        (minimum + base) / 2,
        base,
        (base + maximum) / 2,
        maximum,
    )


def _ratio_difference(value: Decimal, basis: Decimal | None) -> Decimal | None:
    return None if basis is None or basis == 0 else value / basis - 1


def _decimal(value: Decimal) -> str:
    with localcontext() as context:
        context.prec = 50
        quantized = value.quantize(_DISPLAY_PLACES).normalize()
    rendered = format(quantized, "f")
    return "0" if rendered in {"-0", ""} else rendered
