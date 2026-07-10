"""Deterministic normalized fundamental ratios and data-backed valuation metrics."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date  # noqa: TC003 - dataclass annotations are runtime metadata.
from decimal import Decimal
from typing import TYPE_CHECKING

from ai_quant_analytics.contracts import (
    FundamentalValue,
    Metric,
    MetricStatus,
    PeriodType,
    ScenarioInput,
    SecurityType,
)
from ai_quant_analytics.errors import AnalyticsDomainError
from ai_quant_analytics.normalization import CleanPriceSeries, decimal_from_string
from ai_quant_analytics.serialization import available_metric, unavailable_metric, warning

if TYPE_CHECKING:
    from collections.abc import Iterable

_NAME_PATTERN = re.compile(r"[^a-z0-9]")
_FUNDAMENTAL_ORDER = (
    "revenue_growth_yoy",
    "revenue_cagr",
    "gross_margin",
    "operating_margin",
    "net_margin",
    "free_cash_flow_margin",
    "eps_growth",
    "debt_to_equity",
    "net_debt",
    "current_ratio",
    "interest_coverage",
    "return_on_equity",
    "return_on_assets",
    "return_on_invested_capital",
    "share_dilution",
    "capex_trend_slope",
)
_VALUATION_ORDER = (
    "market_capitalization",
    "price_to_earnings",
    "forward_price_to_earnings",
    "price_to_sales",
    "price_to_book",
    "enterprise_value",
    "enterprise_value_to_revenue",
    "enterprise_value_to_ebitda",
    "free_cash_flow_yield",
)
_PERIOD_PRIORITY = {
    PeriodType.TTM: 3,
    PeriodType.ANNUAL: 2,
    PeriodType.QUARTER: 1,
    PeriodType.POINT_IN_TIME: 0,
}


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
    security_type: SecurityType = SecurityType.COMMON_STOCK,
) -> tuple[Metric, ...]:
    """Calculate the complete quant_v1 fundamental set without period or unit mixing."""
    if security_type is SecurityType.ETF:
        return tuple(_not_applicable_company_metric(name) for name in _FUNDAMENTAL_ORDER)
    groups = _group_fundamentals(fundamentals)
    return (
        _growth_metric("revenue_growth_yoy", "revenue", groups),
        _cagr_metric(groups),
        _margin_metric("gross_margin", "grossprofit", groups),
        _margin_metric("operating_margin", "operatingincome", groups),
        _margin_metric("net_margin", "netincome", groups),
        _free_cash_flow_margin(groups),
        _growth_metric("eps_growth", "dilutedeps", groups, require_positive_prior=True),
        _simple_ratio_metric(
            "debt_to_equity",
            "totaldebt",
            "totalequity",
            groups,
            non_positive_denominator_code="NON_POSITIVE_EQUITY",
            non_positive_status=MetricStatus.NOT_APPLICABLE,
        ),
        _net_debt_metric(groups),
        _simple_ratio_metric(
            "current_ratio",
            "currentassets",
            "currentliabilities",
            groups,
        ),
        _interest_coverage_metric(groups),
        _average_balance_ratio("return_on_equity", "netincome", "totalequity", groups),
        _average_balance_ratio("return_on_assets", "netincome", "totalassets", groups),
        _roic_metric(groups),
        _growth_metric("share_dilution", "dilutedshares", groups),
        _capex_trend_metric(groups),
    )


def calculate_valuation_metrics(
    scenario_input: ScenarioInput | None,
    series: CleanPriceSeries,
    fundamentals: tuple[FundamentalValue, ...] = (),
    security_type: SecurityType = SecurityType.COMMON_STOCK,
) -> tuple[Metric, ...]:
    """Calculate every supported valuation metric or publish an explicit absence rule."""
    if security_type is SecurityType.ETF:
        return tuple(_not_applicable_valuation_metric(name, series) for name in _VALUATION_ORDER)
    if scenario_input is None:
        return tuple(_missing_valuation_metric(name, series) for name in _VALUATION_ORDER)

    groups = _group_fundamentals(fundamentals)
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
    price_date = series.bars[-1].date
    base_snapshot_ids = tuple(
        sorted(set(series.snapshot_ids) | set(scenario_input.source_snapshot_ids)),
    )
    market_cap_metric = available_metric(
        name="market_capitalization",
        value=market_cap,
        unit=scenario_input.currency,
        places=2,
        sample_size=2,
        period_start=price_date,
        period_end=price_date,
        snapshot_ids=base_snapshot_ids,
    )
    enterprise_value, enterprise_value_metric = _enterprise_value_metrics(
        market_cap,
        net_debt,
        scenario_input.currency,
        groups,
        price_date,
        base_snapshot_ids,
    )
    return (
        market_cap_metric,
        _per_share_valuation(
            "price_to_earnings",
            "dilutedeps",
            current_price,
            scenario_input.currency,
            groups,
            price_date,
            base_snapshot_ids,
            negative_code="NEGATIVE_EARNINGS",
        ),
        _per_share_valuation(
            "forward_price_to_earnings",
            "forwarddilutedeps",
            current_price,
            scenario_input.currency,
            groups,
            price_date,
            base_snapshot_ids,
            negative_code="NEGATIVE_EARNINGS",
            missing_code="FORECAST_DATA_UNAVAILABLE",
        ),
        _multiple_metric(
            "price_to_sales",
            market_cap,
            "revenue",
            groups,
            price_date,
            base_snapshot_ids,
            required_unit=scenario_input.currency,
        ),
        _multiple_metric(
            "price_to_book",
            market_cap,
            "commonequity",
            groups,
            price_date,
            base_snapshot_ids,
            required_unit=scenario_input.currency,
            fallback_name="totalequity",
            non_positive_code="NON_POSITIVE_EQUITY",
            non_positive_status=MetricStatus.NOT_APPLICABLE,
        ),
        enterprise_value_metric,
        _multiple_metric(
            "enterprise_value_to_revenue",
            enterprise_value,
            "revenue",
            groups,
            price_date,
            base_snapshot_ids,
            required_unit=scenario_input.currency,
        ),
        _multiple_metric(
            "enterprise_value_to_ebitda",
            enterprise_value,
            "ebitda",
            groups,
            price_date,
            base_snapshot_ids,
            required_unit=scenario_input.currency,
            non_positive_code="NON_POSITIVE_EBITDA",
            non_positive_status=MetricStatus.NOT_APPLICABLE,
        ),
        _fcf_yield_metric(
            market_cap,
            groups,
            price_date,
            base_snapshot_ids,
            scenario_input.currency,
        ),
    )


def _growth_metric(
    metric_name: str,
    value_name: str,
    groups: tuple[_FundamentalGroup, ...],
    *,
    require_positive_prior: bool = False,
) -> Metric:
    pair = _growth_pair(groups, value_name)
    if pair is None:
        return _missing_fundamental_metric(metric_name)
    prior, current = pair
    prior_value = prior.values[value_name].value
    current_value = current.values[value_name].value
    snapshot_ids = _snapshot_ids_from_groups((prior, current), (value_name,))
    if require_positive_prior and (prior_value <= 0 or current_value < 0):
        return unavailable_metric(
            name=metric_name,
            unit="ratio",
            status=MetricStatus.NOT_APPLICABLE,
            sample_size=2,
            period_start=prior.period_end,
            period_end=current.period_end,
            snapshot_ids=snapshot_ids,
            warnings=(
                warning(
                    "CROSS_ZERO_GROWTH",
                    f"{metric_name} is not interpretable when EPS crosses or starts at zero.",
                    metric_name,
                ),
            ),
        )
    if prior_value == 0:
        return _unavailable_for_denominator(
            metric_name,
            "ratio",
            "ZERO_DENOMINATOR",
            f"{metric_name} is unavailable because the prior value is zero.",
            2,
            prior.period_end,
            current.period_end,
            snapshot_ids,
        )
    return available_metric(
        name=metric_name,
        value=current_value / prior_value - 1,
        unit="ratio",
        places=8,
        sample_size=2,
        period_start=prior.period_end,
        period_end=current.period_end,
        snapshot_ids=snapshot_ids,
    )


def _cagr_metric(groups: tuple[_FundamentalGroup, ...]) -> Metric:
    series = _best_series(groups, "revenue", minimum=2, annual_only=True)
    if series is None:
        return _missing_fundamental_metric("revenue_cagr")
    start, end = series[0], series[-1]
    start_value = start.values["revenue"].value
    end_value = end.values["revenue"].value
    years = Decimal((end.period_end - start.period_end).days) / Decimal("365.2425")
    snapshot_ids = _snapshot_ids_from_groups((start, end), ("revenue",))
    if start_value <= 0 or end_value <= 0 or years <= 0:
        return unavailable_metric(
            name="revenue_cagr",
            unit="ratio",
            status=MetricStatus.NOT_APPLICABLE,
            sample_size=len(series),
            period_start=start.period_end,
            period_end=end.period_end,
            snapshot_ids=snapshot_ids,
            warnings=(
                warning(
                    "NON_POSITIVE_GROWTH_BASE",
                    "revenue_cagr requires positive start/end revenue and a positive time span.",
                    "revenue_cagr",
                ),
            ),
        )
    value = float(end_value / start_value) ** (1.0 / float(years)) - 1.0
    return available_metric(
        name="revenue_cagr",
        value=value,
        unit="ratio",
        places=8,
        sample_size=len(series),
        period_start=start.period_end,
        period_end=end.period_end,
        snapshot_ids=snapshot_ids,
    )


def _margin_metric(
    metric_name: str,
    numerator_name: str,
    groups: tuple[_FundamentalGroup, ...],
) -> Metric:
    if metric_name == "gross_margin":
        direct = _latest_complete_group(groups, ("grossmargin",), required_unit="RATIO")
        if direct is not None:
            point = direct.values["grossmargin"]
            return available_metric(
                name=metric_name,
                value=point.value,
                unit="ratio",
                places=8,
                sample_size=1,
                period_start=direct.period_end,
                period_end=direct.period_end,
                snapshot_ids=(point.source_snapshot_id,),
            )
    group = _latest_complete_group(groups, (numerator_name, "revenue"))
    if group is None:
        return _missing_fundamental_metric(metric_name)
    return _ratio_from_group(metric_name, numerator_name, "revenue", group)


def _free_cash_flow_margin(groups: tuple[_FundamentalGroup, ...]) -> Metric:
    direct = _latest_complete_group(groups, ("freecashflow", "revenue"))
    if direct is not None:
        return _ratio_from_group("free_cash_flow_margin", "freecashflow", "revenue", direct)
    group = _latest_complete_group(
        groups,
        ("operatingcashflow", "capitalexpenditure", "revenue"),
    )
    if group is None:
        return _missing_fundamental_metric("free_cash_flow_margin")
    revenue = group.values["revenue"].value
    snapshot_ids = _snapshot_ids(group, ("operatingcashflow", "capitalexpenditure", "revenue"))
    if revenue == 0:
        return _unavailable_for_denominator(
            "free_cash_flow_margin",
            "ratio",
            "ZERO_DENOMINATOR",
            "free_cash_flow_margin is unavailable because revenue is zero.",
            3,
            group.period_end,
            group.period_end,
            snapshot_ids,
        )
    free_cash_flow = group.values["operatingcashflow"].value - abs(
        group.values["capitalexpenditure"].value,
    )
    return available_metric(
        name="free_cash_flow_margin",
        value=free_cash_flow / revenue,
        unit="ratio",
        places=8,
        sample_size=3,
        period_start=group.period_end,
        period_end=group.period_end,
        snapshot_ids=snapshot_ids,
    )


def _simple_ratio_metric(  # noqa: PLR0913 - keeps ratio policy inputs explicit.
    metric_name: str,
    numerator_name: str,
    denominator_name: str,
    groups: tuple[_FundamentalGroup, ...],
    *,
    non_positive_denominator_code: str | None = None,
    non_positive_status: MetricStatus = MetricStatus.NOT_AVAILABLE,
) -> Metric:
    group = _latest_complete_group(groups, (numerator_name, denominator_name))
    if group is None:
        return _missing_fundamental_metric(metric_name)
    return _ratio_from_group(
        metric_name,
        numerator_name,
        denominator_name,
        group,
        non_positive_denominator_code=non_positive_denominator_code,
        non_positive_status=non_positive_status,
    )


def _ratio_from_group(  # noqa: PLR0913 - keeps ratio policy inputs explicit.
    metric_name: str,
    numerator_name: str,
    denominator_name: str,
    group: _FundamentalGroup,
    *,
    non_positive_denominator_code: str | None = None,
    non_positive_status: MetricStatus = MetricStatus.NOT_AVAILABLE,
) -> Metric:
    denominator = group.values[denominator_name].value
    snapshot_ids = _snapshot_ids(group, (numerator_name, denominator_name))
    if denominator == 0 or (non_positive_denominator_code is not None and denominator < 0):
        code = non_positive_denominator_code or "ZERO_DENOMINATOR"
        relation = "non-positive" if non_positive_denominator_code is not None else "zero"
        return unavailable_metric(
            name=metric_name,
            unit="ratio",
            status=non_positive_status,
            sample_size=2,
            period_start=group.period_end,
            period_end=group.period_end,
            snapshot_ids=snapshot_ids,
            warnings=(
                warning(
                    code,
                    f"{metric_name} is unavailable because {denominator_name} is {relation}.",
                    metric_name,
                ),
            ),
        )
    return available_metric(
        name=metric_name,
        value=group.values[numerator_name].value / denominator,
        unit="ratio",
        places=8,
        sample_size=2,
        period_start=group.period_end,
        period_end=group.period_end,
        snapshot_ids=snapshot_ids,
    )


def _net_debt_metric(groups: tuple[_FundamentalGroup, ...]) -> Metric:
    group = _latest_complete_group(groups, ("totaldebt", "cashandequivalents"))
    if group is None:
        return _missing_fundamental_metric("net_debt", unit="currency")
    return available_metric(
        name="net_debt",
        value=group.values["totaldebt"].value - group.values["cashandequivalents"].value,
        unit=group.unit,
        places=2,
        sample_size=2,
        period_start=group.period_end,
        period_end=group.period_end,
        snapshot_ids=_snapshot_ids(group, ("totaldebt", "cashandequivalents")),
    )


def _interest_coverage_metric(groups: tuple[_FundamentalGroup, ...]) -> Metric:
    group = _latest_complete_group(groups, ("ebit", "interestexpense"))
    if group is None:
        return _missing_fundamental_metric("interest_coverage")
    interest = abs(group.values["interestexpense"].value)
    if interest == 0:
        return _unavailable_for_denominator(
            "interest_coverage",
            "ratio",
            "ZERO_DENOMINATOR",
            "interest_coverage is unavailable because interest expense is zero.",
            2,
            group.period_end,
            group.period_end,
            _snapshot_ids(group, ("ebit", "interestexpense")),
        )
    return available_metric(
        name="interest_coverage",
        value=group.values["ebit"].value / interest,
        unit="ratio",
        places=8,
        sample_size=2,
        period_start=group.period_end,
        period_end=group.period_end,
        snapshot_ids=_snapshot_ids(group, ("ebit", "interestexpense")),
    )


def _average_balance_ratio(
    metric_name: str,
    numerator_name: str,
    balance_name: str,
    groups: tuple[_FundamentalGroup, ...],
) -> Metric:
    current = _latest_complete_group(groups, (numerator_name, balance_name))
    if current is None:
        return _missing_fundamental_metric(metric_name)
    prior = _prior_group(groups, current, balance_name)
    if prior is None:
        return _missing_fundamental_metric(metric_name)
    average_balance = (prior.values[balance_name].value + current.values[balance_name].value) / 2
    snapshot_ids = _snapshot_ids_from_groups((prior, current), (numerator_name, balance_name))
    if average_balance <= 0:
        return unavailable_metric(
            name=metric_name,
            unit="ratio",
            status=MetricStatus.NOT_APPLICABLE,
            sample_size=3,
            period_start=prior.period_end,
            period_end=current.period_end,
            snapshot_ids=snapshot_ids,
            warnings=(
                warning(
                    "NON_POSITIVE_BALANCE",
                    f"{metric_name} is unavailable because the average balance is non-positive.",
                    metric_name,
                ),
            ),
        )
    return available_metric(
        name=metric_name,
        value=current.values[numerator_name].value / average_balance,
        unit="ratio",
        places=8,
        sample_size=3,
        period_start=prior.period_end,
        period_end=current.period_end,
        snapshot_ids=snapshot_ids,
    )


def _roic_metric(groups: tuple[_FundamentalGroup, ...]) -> Metric:
    current = _latest_complete_group(groups, ("ebit", "investedcapital"))
    if current is None:
        return _missing_fundamental_metric("return_on_invested_capital")
    prior = _prior_group(groups, current, "investedcapital")
    tax_group = _matching_group(groups, current, "effectivetaxrate", required_unit="RATIO")
    if prior is None or tax_group is None:
        return _missing_fundamental_metric("return_on_invested_capital")
    tax_rate = tax_group.values["effectivetaxrate"].value
    snapshot_ids = tuple(
        sorted(
            set(_snapshot_ids_from_groups((prior, current), ("ebit", "investedcapital")))
            | {tax_group.values["effectivetaxrate"].source_snapshot_id},
        ),
    )
    if tax_rate < 0 or tax_rate > 1:
        return unavailable_metric(
            name="return_on_invested_capital",
            unit="ratio",
            sample_size=4,
            period_start=prior.period_end,
            period_end=current.period_end,
            snapshot_ids=snapshot_ids,
            warnings=(
                warning(
                    "INVALID_TAX_RATE",
                    "return_on_invested_capital requires effectiveTaxRate in [0, 1].",
                    "return_on_invested_capital",
                ),
            ),
        )
    average_capital = (
        prior.values["investedcapital"].value + current.values["investedcapital"].value
    ) / 2
    if average_capital <= 0:
        return unavailable_metric(
            name="return_on_invested_capital",
            unit="ratio",
            status=MetricStatus.NOT_APPLICABLE,
            sample_size=4,
            period_start=prior.period_end,
            period_end=current.period_end,
            snapshot_ids=snapshot_ids,
            warnings=(
                warning(
                    "NON_POSITIVE_INVESTED_CAPITAL",
                    "return_on_invested_capital requires positive average invested capital.",
                    "return_on_invested_capital",
                ),
            ),
        )
    nopat = current.values["ebit"].value * (Decimal(1) - tax_rate)
    return available_metric(
        name="return_on_invested_capital",
        value=nopat / average_capital,
        unit="ratio",
        places=8,
        sample_size=4,
        period_start=prior.period_end,
        period_end=current.period_end,
        snapshot_ids=snapshot_ids,
    )


def _capex_trend_metric(groups: tuple[_FundamentalGroup, ...]) -> Metric:
    series = _best_series(groups, "capitalexpenditure", minimum=4)
    if series is None:
        return _missing_fundamental_metric("capex_trend_slope", unit="currency_per_period")
    values = [abs(group.values["capitalexpenditure"].value) for group in series]
    count = Decimal(len(values))
    mean_x = (count - 1) / 2
    mean_y = sum(values) / count
    numerator = sum(
        (Decimal(index) - mean_x) * (value - mean_y) for index, value in enumerate(values)
    )
    denominator = sum((Decimal(index) - mean_x) ** 2 for index in range(len(values)))
    return available_metric(
        name="capex_trend_slope",
        value=numerator / denominator,
        unit=f"{series[-1].unit}_PER_PERIOD",
        places=2,
        sample_size=len(series),
        period_start=series[0].period_end,
        period_end=series[-1].period_end,
        snapshot_ids=_snapshot_ids_from_groups(series, ("capitalexpenditure",)),
    )


def _enterprise_value_metrics(  # noqa: PLR0913 - preserves complete valuation lineage.
    market_cap: Decimal,
    fallback_net_debt: Decimal,
    currency: str,
    groups: tuple[_FundamentalGroup, ...],
    price_date: date,
    base_snapshot_ids: tuple[str, ...],
) -> tuple[Decimal, Metric]:
    balance_group = _latest_complete_group(
        groups,
        ("totaldebt", "cashandequivalents"),
        required_unit=currency,
    )
    if balance_group is None:
        value = market_cap + fallback_net_debt
        any_balance_group = _latest_complete_group(
            groups,
            ("totaldebt", "cashandequivalents"),
        )
        metric_warnings = (
            (
                warning(
                    "SOURCE_UNIT_MISMATCH",
                    "enterprise_value used scenario net debt because the balance-sheet "
                    "currency did not match.",
                    "enterprise_value",
                ),
            )
            if any_balance_group is not None
            else ()
        )
        return value, available_metric(
            name="enterprise_value",
            value=value,
            unit=currency,
            places=2,
            sample_size=3,
            period_start=price_date,
            period_end=price_date,
            snapshot_ids=base_snapshot_ids,
            warnings=metric_warnings,
        )
    preferred = balance_group.values.get("preferredequity")
    minority = balance_group.values.get("minorityinterest")
    value = (
        market_cap
        + balance_group.values["totaldebt"].value
        + (Decimal(0) if preferred is None else preferred.value)
        + (Decimal(0) if minority is None else minority.value)
        - balance_group.values["cashandequivalents"].value
    )
    names = ["totaldebt", "cashandequivalents"]
    if preferred is not None:
        names.append("preferredequity")
    if minority is not None:
        names.append("minorityinterest")
    snapshot_ids = tuple(
        sorted(set(base_snapshot_ids) | set(_snapshot_ids(balance_group, tuple(names)))),
    )
    return value, available_metric(
        name="enterprise_value",
        value=value,
        unit=currency,
        places=2,
        sample_size=1 + len(names),
        period_start=balance_group.period_end,
        period_end=price_date,
        snapshot_ids=snapshot_ids,
    )


def _per_share_valuation(  # noqa: PLR0913 - preserves complete valuation lineage.
    metric_name: str,
    value_name: str,
    price: Decimal,
    currency: str,
    groups: tuple[_FundamentalGroup, ...],
    price_date: date,
    base_snapshot_ids: tuple[str, ...],
    *,
    negative_code: str,
    missing_code: str = "INSUFFICIENT_DATA",
) -> Metric:
    any_group = _latest_complete_group(groups, (value_name,))
    group = _latest_complete_group(
        groups,
        (value_name,),
        required_unit=f"{currency}_PER_SHARE",
    )
    if group is None:
        return _missing_valuation_metric_for_data(
            metric_name,
            price_date,
            base_snapshot_ids,
            missing_code if any_group is None else "SOURCE_UNIT_MISMATCH",
        )
    denominator = group.values[value_name].value
    snapshot_ids = tuple(
        sorted(set(base_snapshot_ids) | {group.values[value_name].source_snapshot_id}),
    )
    if denominator <= 0:
        return unavailable_metric(
            name=metric_name,
            unit="ratio",
            status=MetricStatus.NOT_APPLICABLE,
            sample_size=2,
            period_start=group.period_end,
            period_end=price_date,
            snapshot_ids=snapshot_ids,
            warnings=(
                warning(
                    negative_code,
                    f"{metric_name} is not applicable because earnings per share is non-positive.",
                    metric_name,
                ),
            ),
        )
    return available_metric(
        name=metric_name,
        value=price / denominator,
        unit="ratio",
        places=8,
        sample_size=2,
        period_start=group.period_end,
        period_end=price_date,
        snapshot_ids=snapshot_ids,
    )


def _multiple_metric(  # noqa: PLR0913 - preserves complete valuation lineage.
    metric_name: str,
    numerator: Decimal,
    denominator_name: str,
    groups: tuple[_FundamentalGroup, ...],
    price_date: date,
    base_snapshot_ids: tuple[str, ...],
    *,
    required_unit: str,
    fallback_name: str | None = None,
    non_positive_code: str | None = None,
    non_positive_status: MetricStatus = MetricStatus.NOT_AVAILABLE,
) -> Metric:
    effective_name = denominator_name
    any_group = _latest_complete_group(groups, (effective_name,))
    group = _latest_complete_group(groups, (effective_name,), required_unit=required_unit)
    if group is None and fallback_name is not None:
        effective_name = fallback_name
        any_group = _latest_complete_group(groups, (effective_name,))
        group = _latest_complete_group(groups, (effective_name,), required_unit=required_unit)
    if group is None:
        return _missing_valuation_metric_for_data(
            metric_name,
            price_date,
            base_snapshot_ids,
            "INSUFFICIENT_DATA" if any_group is None else "SOURCE_UNIT_MISMATCH",
        )
    denominator = group.values[effective_name].value
    snapshot_ids = tuple(
        sorted(set(base_snapshot_ids) | {group.values[effective_name].source_snapshot_id}),
    )
    if denominator == 0 or (non_positive_code is not None and denominator < 0):
        code = non_positive_code or "ZERO_DENOMINATOR"
        return unavailable_metric(
            name=metric_name,
            unit="ratio",
            status=non_positive_status,
            sample_size=2,
            period_start=group.period_end,
            period_end=price_date,
            snapshot_ids=snapshot_ids,
            warnings=(
                warning(
                    code,
                    f"{metric_name} is unavailable because {effective_name} is non-positive.",
                    metric_name,
                ),
            ),
        )
    return available_metric(
        name=metric_name,
        value=numerator / denominator,
        unit="ratio",
        places=8,
        sample_size=2,
        period_start=group.period_end,
        period_end=price_date,
        snapshot_ids=snapshot_ids,
    )


def _fcf_yield_metric(
    market_cap: Decimal,
    groups: tuple[_FundamentalGroup, ...],
    price_date: date,
    base_snapshot_ids: tuple[str, ...],
    currency: str,
) -> Metric:
    names: tuple[str, ...]
    direct = _latest_complete_group(groups, ("freecashflow",), required_unit=currency)
    if direct is not None:
        free_cash_flow = direct.values["freecashflow"].value
        names = ("freecashflow",)
        group = direct
    else:
        derived = _latest_complete_group(
            groups,
            ("operatingcashflow", "capitalexpenditure"),
            required_unit=currency,
        )
        if derived is None:
            any_group = _latest_complete_group(groups, ("freecashflow",)) or _latest_complete_group(
                groups,
                ("operatingcashflow", "capitalexpenditure"),
            )
            return _missing_valuation_metric_for_data(
                "free_cash_flow_yield",
                price_date,
                base_snapshot_ids,
                "INSUFFICIENT_DATA" if any_group is None else "SOURCE_UNIT_MISMATCH",
            )
        free_cash_flow = derived.values["operatingcashflow"].value - abs(
            derived.values["capitalexpenditure"].value,
        )
        names = ("operatingcashflow", "capitalexpenditure")
        group = derived
    snapshot_ids = tuple(sorted(set(base_snapshot_ids) | set(_snapshot_ids(group, names))))
    return available_metric(
        name="free_cash_flow_yield",
        value=free_cash_flow / market_cap,
        unit="ratio",
        places=8,
        sample_size=1 + len(names),
        period_start=group.period_end,
        period_end=price_date,
        snapshot_ids=snapshot_ids,
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
    *,
    required_unit: str | None = None,
) -> _FundamentalGroup | None:
    candidates = [
        group
        for group in groups
        if all(name in group.values for name in required_names)
        and (required_unit is None or group.unit.upper() == required_unit)
    ]
    if not candidates:
        return None
    return max(
        candidates,
        key=lambda group: (_PERIOD_PRIORITY[group.period_type], group.period_end, group.unit),
    )


def _matching_group(
    groups: tuple[_FundamentalGroup, ...],
    reference: _FundamentalGroup,
    name: str,
    *,
    required_unit: str | None = None,
) -> _FundamentalGroup | None:
    return next(
        (
            group
            for group in groups
            if group.period_type is reference.period_type
            and group.period_end == reference.period_end
            and name in group.values
            and (required_unit is None or group.unit.upper() == required_unit)
        ),
        None,
    )


def _prior_group(
    groups: tuple[_FundamentalGroup, ...],
    current: _FundamentalGroup,
    name: str,
) -> _FundamentalGroup | None:
    candidates = [
        group
        for group in groups
        if group.period_type is current.period_type
        and group.unit == current.unit
        and group.period_end < current.period_end
        and name in group.values
    ]
    return max(candidates, key=lambda group: group.period_end, default=None)


def _growth_pair(
    groups: tuple[_FundamentalGroup, ...],
    name: str,
) -> tuple[_FundamentalGroup, _FundamentalGroup] | None:
    candidates: list[tuple[int, date, _FundamentalGroup, _FundamentalGroup]] = []
    for period_type in (PeriodType.TTM, PeriodType.ANNUAL, PeriodType.QUARTER):
        for unit in sorted({group.unit for group in groups if group.period_type is period_type}):
            series = sorted(
                (
                    group
                    for group in groups
                    if group.period_type is period_type
                    and group.unit == unit
                    and name in group.values
                ),
                key=lambda group: group.period_end,
            )
            offset = 4 if period_type is PeriodType.QUARTER else 1
            if len(series) > offset:
                candidates.append(
                    (
                        _PERIOD_PRIORITY[period_type],
                        series[-1].period_end,
                        series[-1 - offset],
                        series[-1],
                    ),
                )
    if not candidates:
        return None
    selected = max(candidates, key=lambda item: (item[0], item[1]))
    return selected[2], selected[3]


def _best_series(
    groups: tuple[_FundamentalGroup, ...],
    name: str,
    *,
    minimum: int,
    annual_only: bool = False,
) -> tuple[_FundamentalGroup, ...] | None:
    allowed_types = (
        (PeriodType.ANNUAL,)
        if annual_only
        else (
            PeriodType.TTM,
            PeriodType.ANNUAL,
            PeriodType.QUARTER,
        )
    )
    candidates: list[tuple[int, date, tuple[_FundamentalGroup, ...]]] = []
    for period_type in allowed_types:
        for unit in sorted({group.unit for group in groups if group.period_type is period_type}):
            series = tuple(
                sorted(
                    (
                        group
                        for group in groups
                        if group.period_type is period_type
                        and group.unit == unit
                        and name in group.values
                    ),
                    key=lambda group: group.period_end,
                ),
            )
            if len(series) >= minimum:
                candidates.append((_PERIOD_PRIORITY[period_type], series[-1].period_end, series))
    if not candidates:
        return None
    return max(candidates, key=lambda item: (item[0], item[1]))[2]


def _snapshot_ids(group: _FundamentalGroup, names: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(sorted({group.values[name].source_snapshot_id for name in names}))


def _snapshot_ids_from_groups(
    groups: Iterable[_FundamentalGroup],
    names: tuple[str, ...],
) -> tuple[str, ...]:
    return tuple(
        sorted(
            {
                group.values[name].source_snapshot_id
                for group in groups
                for name in names
                if name in group.values
            },
        ),
    )


def _unavailable_for_denominator(  # noqa: PLR0913 - mirrors the Metric lineage contract.
    name: str,
    unit: str,
    code: str,
    message: str,
    sample_size: int,
    period_start: date,
    period_end: date,
    snapshot_ids: tuple[str, ...],
) -> Metric:
    return unavailable_metric(
        name=name,
        unit=unit,
        sample_size=sample_size,
        period_start=period_start,
        period_end=period_end,
        snapshot_ids=snapshot_ids,
        warnings=(warning(code, message, name),),
    )


def _missing_fundamental_metric(name: str, *, unit: str = "ratio") -> Metric:
    return unavailable_metric(
        name=name,
        unit=unit,
        sample_size=0,
        period_start=None,
        period_end=None,
        snapshot_ids=(),
        warnings=(
            warning(
                "INSUFFICIENT_DATA",
                f"{name} requires normalized values from compatible fiscal periods and units.",
                name,
            ),
        ),
    )


def _not_applicable_company_metric(name: str) -> Metric:
    return unavailable_metric(
        name=name,
        unit="ratio",
        status=MetricStatus.NOT_APPLICABLE,
        sample_size=0,
        period_start=None,
        period_end=None,
        snapshot_ids=(),
        warnings=(
            warning(
                "ETF_NOT_APPLICABLE",
                f"{name} is a company fundamental metric and does not apply to ETFs.",
                name,
            ),
        ),
    )


def _missing_valuation_metric(name: str, series: CleanPriceSeries) -> Metric:
    return unavailable_metric(
        name=name,
        unit="ratio",
        sample_size=0,
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
        warnings=(
            warning(
                "INSUFFICIENT_DATA",
                f"{name} requires explicit valuation inputs and compatible fundamentals.",
                name,
            ),
        ),
    )


def _missing_valuation_metric_for_data(
    name: str,
    price_date: date,
    snapshot_ids: tuple[str, ...],
    code: str,
) -> Metric:
    message = (
        f"{name} requires a traceable forecast input."
        if code == "FORECAST_DATA_UNAVAILABLE"
        else (
            f"{name} received a fundamental value in an incompatible source unit."
            if code == "SOURCE_UNIT_MISMATCH"
            else f"{name} requires a compatible normalized fundamental value."
        )
    )
    return unavailable_metric(
        name=name,
        unit="ratio",
        sample_size=0,
        period_start=price_date,
        period_end=price_date,
        snapshot_ids=snapshot_ids,
        warnings=(warning(code, message, name),),
    )


def _not_applicable_valuation_metric(name: str, series: CleanPriceSeries) -> Metric:
    return unavailable_metric(
        name=name,
        unit="ratio",
        status=MetricStatus.NOT_APPLICABLE,
        sample_size=0,
        period_start=series.bars[0].date,
        period_end=series.bars[-1].date,
        snapshot_ids=series.snapshot_ids,
        warnings=(
            warning(
                "ETF_NOT_APPLICABLE",
                f"{name} is a company valuation metric and does not apply to ETFs.",
                name,
            ),
        ),
    )
