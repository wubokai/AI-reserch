"""Deterministic normalized fundamental and minimal valuation metrics."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date  # noqa: TC003 - dataclass annotations are runtime metadata.
from decimal import Decimal  # noqa: TC003 - dataclass annotations are runtime metadata.

from ai_quant_analytics.contracts import FundamentalValue, Metric, PeriodType, ScenarioInput
from ai_quant_analytics.errors import AnalyticsDomainError
from ai_quant_analytics.normalization import CleanPriceSeries, decimal_from_string
from ai_quant_analytics.serialization import available_metric, unavailable_metric, warning

_NAME_PATTERN = re.compile(r"[^a-z0-9]")
_FUNDAMENTAL_METRICS = (
    ("gross_margin", ("grossprofit", "revenue")),
    ("operating_margin", ("operatingincome", "revenue")),
    ("net_margin", ("netincome", "revenue")),
    ("free_cash_flow_margin", ("operatingcashflow", "capitalexpenditure", "revenue")),
)


@dataclass(frozen=True, slots=True)
class _FundamentalPoint:
    value: Decimal
    source_snapshot_id: str


@dataclass(frozen=True, slots=True)
class _FundamentalGroup:
    period_type: PeriodType
    period_end: date
    unit: str
    values: dict[str, _FundamentalPoint]


def calculate_fundamental_metrics(
    fundamentals: tuple[FundamentalValue, ...],
) -> tuple[Metric, ...]:
    """Calculate latest same-period margins without mixing units or fiscal periods."""
    groups = _group_fundamentals(fundamentals)
    metrics: list[Metric] = []
    for metric_name, required_names in _FUNDAMENTAL_METRICS:
        effective_names = required_names
        direct_metric = _direct_normalized_metric(metric_name, groups)
        if direct_metric is not None:
            metrics.append(direct_metric)
            continue
        if metric_name == "free_cash_flow_margin":
            direct_names = ("freecashflow", "revenue")
            if _latest_complete_group(groups, direct_names) is not None:
                effective_names = direct_names
        group = _latest_complete_group(groups, effective_names)
        if group is None:
            metrics.append(_missing_fundamental_metric(metric_name))
            continue
        revenue = group.values["revenue"].value
        if revenue == 0:
            metrics.append(
                unavailable_metric(
                    name=metric_name,
                    unit="ratio",
                    sample_size=len(effective_names),
                    period_start=group.period_end,
                    period_end=group.period_end,
                    snapshot_ids=_snapshot_ids(group, effective_names),
                    warnings=(
                        warning(
                            "ZERO_DENOMINATOR",
                            f"{metric_name} is unavailable because revenue is zero.",
                            metric_name,
                        ),
                    ),
                ),
            )
            continue
        if metric_name == "free_cash_flow_margin" and "freecashflow" in group.values:
            numerator = group.values["freecashflow"].value
        elif metric_name == "free_cash_flow_margin":
            numerator = group.values["operatingcashflow"].value - abs(
                group.values["capitalexpenditure"].value
            )
        else:
            numerator = group.values[effective_names[0]].value
        metrics.append(
            available_metric(
                name=metric_name,
                value=numerator / revenue,
                unit="ratio",
                places=8,
                sample_size=len(effective_names),
                period_start=group.period_end,
                period_end=group.period_end,
                snapshot_ids=_snapshot_ids(group, effective_names),
            ),
        )
    return tuple(metrics)


def _direct_normalized_metric(
    metric_name: str,
    groups: tuple[_FundamentalGroup, ...],
) -> Metric | None:
    if metric_name != "gross_margin":
        return None
    group = _latest_complete_group(groups, ("grossmargin",))
    if group is None or group.unit.upper() != "RATIO":
        return None
    point = group.values["grossmargin"]
    return available_metric(
        name=metric_name,
        value=point.value,
        unit="ratio",
        places=8,
        sample_size=1,
        period_start=group.period_end,
        period_end=group.period_end,
        snapshot_ids=(point.source_snapshot_id,),
    )


def calculate_valuation_metrics(
    scenario_input: ScenarioInput | None,
    series: CleanPriceSeries,
) -> tuple[Metric, ...]:
    """Calculate market capitalization and enterprise value from explicit scenario inputs."""
    metric_names = ("market_capitalization", "enterprise_value")
    if scenario_input is None:
        return tuple(_missing_valuation_metric(name, series) for name in metric_names)
    current_price = decimal_from_string(scenario_input.current_price, "scenarioInput.currentPrice")
    diluted_shares = decimal_from_string(
        scenario_input.diluted_shares,
        "scenarioInput.dilutedShares",
    )
    net_debt = decimal_from_string(scenario_input.net_debt, "scenarioInput.netDebt")
    if current_price <= 0 or diluted_shares <= 0:
        raise AnalyticsDomainError(
            "INVALID_VALUATION_BASE",
            "Valuation currentPrice and dilutedShares must be positive.",
        )
    market_cap = current_price * diluted_shares
    enterprise_value = market_cap + net_debt
    snapshot_ids = tuple(
        sorted(set(series.snapshot_ids) | set(scenario_input.source_snapshot_ids)),
    )
    period_start = series.bars[0].date
    period_end = series.bars[-1].date
    return (
        available_metric(
            name="market_capitalization",
            value=market_cap,
            unit=scenario_input.currency,
            places=2,
            sample_size=2,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=snapshot_ids,
        ),
        available_metric(
            name="enterprise_value",
            value=enterprise_value,
            unit=scenario_input.currency,
            places=2,
            sample_size=3,
            period_start=period_start,
            period_end=period_end,
            snapshot_ids=snapshot_ids,
        ),
    )


def _group_fundamentals(
    fundamentals: tuple[FundamentalValue, ...],
) -> tuple[_FundamentalGroup, ...]:
    raw_groups: dict[tuple[PeriodType, date, str], dict[str, _FundamentalPoint]] = {}
    for item in fundamentals:
        key = item.period_type, item.period_end_date, item.unit
        canonical_name = _NAME_PATTERN.sub("", item.name.lower())
        point = _FundamentalPoint(
            value=decimal_from_string(item.value, f"fundamentals.{item.name}.value"),
            source_snapshot_id=item.source_snapshot_id,
        )
        values = raw_groups.setdefault(key, {})
        existing = values.get(canonical_name)
        if existing is not None and existing.value != point.value:
            raise AnalyticsDomainError(
                "DUPLICATE_FUNDAMENTAL_CONFLICT",
                "Conflicting normalized fundamental values were supplied for one period.",
                details={
                    "name": item.name,
                    "periodEndDate": item.period_end_date.isoformat(),
                },
            )
        if existing is None or point.source_snapshot_id < existing.source_snapshot_id:
            values[canonical_name] = point
    return tuple(
        _FundamentalGroup(period_type=key[0], period_end=key[1], unit=key[2], values=values)
        for key, values in raw_groups.items()
    )


def _latest_complete_group(
    groups: tuple[_FundamentalGroup, ...],
    required_names: tuple[str, ...],
) -> _FundamentalGroup | None:
    candidates = [group for group in groups if all(name in group.values for name in required_names)]
    if not candidates:
        return None
    priority = {
        PeriodType.TTM: 3,
        PeriodType.ANNUAL: 2,
        PeriodType.QUARTER: 1,
        PeriodType.POINT_IN_TIME: 0,
    }
    return max(
        candidates,
        key=lambda group: (priority[group.period_type], group.period_end, group.unit),
    )


def _snapshot_ids(group: _FundamentalGroup, names: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(sorted({group.values[name].source_snapshot_id for name in names}))


def _missing_fundamental_metric(name: str) -> Metric:
    return unavailable_metric(
        name=name,
        unit="ratio",
        sample_size=0,
        period_start=None,
        period_end=None,
        snapshot_ids=(),
        warnings=(
            warning(
                "INSUFFICIENT_DATA",
                f"{name} requires normalized values from one common fiscal period and unit.",
                name,
            ),
        ),
    )


def _missing_valuation_metric(name: str, series: CleanPriceSeries) -> Metric:
    return unavailable_metric(
        name=name,
        unit="currency",
        sample_size=0,
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
        warnings=(
            warning(
                "INSUFFICIENT_DATA",
                f"{name} requires explicit scenario valuation inputs.",
                name,
            ),
        ),
    )
