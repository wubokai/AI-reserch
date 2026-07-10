"""Transparent Bull, Base, and Bear EV/EBITDA scenario calculations."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

from datetime import date  # noqa: TC003 - public helper signatures are runtime metadata.
from decimal import Decimal, localcontext

from ai_quant_analytics.contracts import AnalyticsWarning, Metric, ScenarioInput, ScenarioName
from ai_quant_analytics.errors import AnalyticsDomainError
from ai_quant_analytics.normalization import decimal_from_string
from ai_quant_analytics.serialization import available_metric, unavailable_metric, warning

_SCENARIO_METRIC_SUFFIXES = (
    ("raw_implied_price", "currency_per_share", 4),
    ("implied_price", "currency_per_share", 4),
    ("upside_downside", "ratio", 8),
)


def calculate_scenario_metrics(  # noqa: C901 - mirrors the documented scenario formula.
    scenario_input: ScenarioInput | None,
    *,
    period_start: date,
    period_end: date,
) -> tuple[Metric, ...]:
    """Calculate scenario prices, upside/downside, and the probability-weighted value."""
    if scenario_input is None:
        return _unavailable_scenario_metrics(period_start, period_end)

    base_revenue = decimal_from_string(scenario_input.base_revenue, "scenarioInput.baseRevenue")
    current_price = decimal_from_string(scenario_input.current_price, "scenarioInput.currentPrice")
    net_debt = decimal_from_string(scenario_input.net_debt, "scenarioInput.netDebt")
    diluted_shares = decimal_from_string(
        scenario_input.diluted_shares,
        "scenarioInput.dilutedShares",
    )
    if base_revenue <= 0 or current_price <= 0 or diluted_shares <= 0:
        raise AnalyticsDomainError(
            "INVALID_SCENARIO_BASE",
            "Scenario baseRevenue, currentPrice, and dilutedShares must be positive.",
        )

    scenarios_by_name = {scenario.name: scenario for scenario in scenario_input.scenarios}
    if set(scenarios_by_name) != set(ScenarioName):
        raise AnalyticsDomainError(
            "INVALID_SCENARIO_SET",
            "Scenarios must contain exactly one BULL, BASE, and BEAR entry.",
        )

    probabilities: list[Decimal] = []
    parsed: dict[ScenarioName, tuple[Decimal, Decimal, Decimal, Decimal]] = {}
    for name in ScenarioName:
        scenario = scenarios_by_name[name]
        growth = decimal_from_string(
            scenario.revenue_growth,
            f"scenarioInput.scenarios.{name.value}.revenueGrowth",
        )
        margin = decimal_from_string(
            scenario.target_ebitda_margin,
            f"scenarioInput.scenarios.{name.value}.targetEbitdaMargin",
        )
        multiple = decimal_from_string(
            scenario.ev_to_ebitda_multiple,
            f"scenarioInput.scenarios.{name.value}.evToEbitdaMultiple",
        )
        probability = decimal_from_string(
            scenario.probability,
            f"scenarioInput.scenarios.{name.value}.probability",
        )
        if growth <= Decimal(-1):
            raise AnalyticsDomainError(
                "INVALID_SCENARIO_GROWTH",
                "Scenario revenueGrowth must be greater than -1.",
            )
        if multiple < 0:
            raise AnalyticsDomainError(
                "INVALID_SCENARIO_MULTIPLE",
                "Scenario EV/EBITDA multiples must not be negative.",
            )
        if not Decimal(0) <= probability <= Decimal(1):
            raise AnalyticsDomainError(
                "INVALID_SCENARIO_PROBABILITY",
                "Each scenario probability must be between 0 and 1.",
            )
        parsed[name] = growth, margin, multiple, probability
        probabilities.append(probability)
    if abs(sum(probabilities, start=Decimal(0)) - Decimal(1)) > Decimal("1e-8"):
        raise AnalyticsDomainError(
            "INVALID_SCENARIO_PROBABILITY",
            "Scenario probabilities must sum to 1 within a 1e-8 tolerance.",
        )

    currency_unit = f"{scenario_input.currency}/share"
    snapshot_ids = tuple(sorted(set(scenario_input.source_snapshot_ids)))
    metrics: list[Metric] = []
    weighted_value = Decimal(0)
    with localcontext() as context:
        context.prec = 50
        for name in ScenarioName:
            growth, margin, multiple, probability = parsed[name]
            forecast_revenue = base_revenue * (Decimal(1) + growth)
            forecast_ebitda = forecast_revenue * margin
            enterprise_value = forecast_ebitda * multiple
            equity_value = enterprise_value - net_debt
            raw_price = equity_value / diluted_shares
            implied_price = max(Decimal(0), raw_price)
            upside_downside = implied_price / current_price - Decimal(1)
            weighted_value += implied_price * probability
            scenario_warnings: tuple[AnalyticsWarning, ...] = ()
            if forecast_ebitda <= 0:
                scenario_warnings = (
                    warning(
                        "NON_POSITIVE_FORECAST_EBITDA",
                        "The scenario has non-positive forecast EBITDA; "
                        "the displayed price is floored at zero.",
                        f"scenario_{name.value.lower()}_implied_price",
                    ),
                )
            prefix = f"scenario_{name.value.lower()}"
            metrics.extend(
                (
                    available_metric(
                        name=f"{prefix}_raw_implied_price",
                        value=raw_price,
                        unit=currency_unit,
                        places=4,
                        sample_size=1,
                        period_start=period_start,
                        period_end=period_end,
                        snapshot_ids=snapshot_ids,
                        warnings=scenario_warnings,
                    ),
                    available_metric(
                        name=f"{prefix}_implied_price",
                        value=implied_price,
                        unit=currency_unit,
                        places=4,
                        sample_size=1,
                        period_start=period_start,
                        period_end=period_end,
                        snapshot_ids=snapshot_ids,
                        warnings=scenario_warnings,
                    ),
                    available_metric(
                        name=f"{prefix}_upside_downside",
                        value=upside_downside,
                        unit="ratio",
                        places=8,
                        sample_size=1,
                        period_start=period_start,
                        period_end=period_end,
                        snapshot_ids=snapshot_ids,
                        warnings=scenario_warnings,
                    ),
                ),
            )
    metrics.append(
        available_metric(
            name="weighted_scenario_value",
            value=weighted_value,
            unit=currency_unit,
            places=4,
            sample_size=3,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=snapshot_ids,
        ),
    )
    return tuple(metrics)


def _unavailable_scenario_metrics(period_start: date, period_end: date) -> tuple[Metric, ...]:
    values: list[Metric] = []
    for name in ScenarioName:
        for suffix, unit, _places in _SCENARIO_METRIC_SUFFIXES:
            metric_name = f"scenario_{name.value.lower()}_{suffix}"
            values.append(
                unavailable_metric(
                    name=metric_name,
                    unit=unit,
                    sample_size=0,
                    period_start=period_start,
                    period_end=period_end,
                    snapshot_ids=(),
                    warnings=(
                        warning(
                            "INSUFFICIENT_DATA",
                            "Scenario inputs were not supplied.",
                            metric_name,
                        ),
                    ),
                ),
            )
    values.append(
        unavailable_metric(
            name="weighted_scenario_value",
            unit="currency_per_share",
            sample_size=0,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=(),
            warnings=(
                warning(
                    "INSUFFICIENT_DATA",
                    "Scenario inputs were not supplied.",
                    "weighted_scenario_value",
                ),
            ),
        ),
    )
    return tuple(values)
