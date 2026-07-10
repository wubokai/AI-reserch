"""Deterministic request factories used by analytics tests."""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Sequence


def make_payload(  # noqa: PLR0913 - test factory keeps every request dimension explicit.
    prices: Sequence[int | str | Decimal],
    *,
    start: date = date(2024, 1, 1),
    day_step: int = 1,
    benchmark_prices: Sequence[int | str | Decimal] | None = None,
    benchmark_start: date | None = None,
    fundamentals: list[dict[str, object]] | None = None,
    scenario_input: dict[str, object] | None = None,
) -> dict[str, object]:
    bars = _make_bars(prices, start, day_step, "snap_prices_v1")
    benchmark_bars = (
        []
        if benchmark_prices is None
        else _make_bars(
            benchmark_prices,
            start if benchmark_start is None else benchmark_start,
            day_step,
            "snap_benchmark_v1",
        )
    )
    return {
        "schemaVersion": "analytics_full_request_v1",
        "calculationVersion": "quant_v1",
        "inputHash": "a" * 64,
        "symbol": "MU",
        "securityType": "COMMON_STOCK",
        "periodStart": bars[0]["date"],
        "periodEnd": bars[-1]["date"],
        "prices": bars,
        "benchmarkSymbol": "SPY" if benchmark_prices is not None else None,
        "benchmarkPrices": benchmark_bars,
        "riskFreeRateAnnual": "0.04",
        "minimumAcceptedReturnAnnual": "0",
        "fundamentals": [] if fundamentals is None else fundamentals,
        "scenarioInput": scenario_input,
    }


def _make_bars(
    prices: Sequence[int | str | Decimal],
    start: date,
    day_step: int,
    snapshot_id: str,
) -> list[dict[str, object]]:
    bars: list[dict[str, object]] = []
    for index, raw_price in enumerate(prices):
        price = Decimal(str(raw_price))
        observed = start + timedelta(days=index * day_step)
        bars.append(
            {
                "date": observed.isoformat(),
                "open": format(price, "f"),
                "high": format(price + Decimal(1), "f"),
                "low": format(price - Decimal(1), "f"),
                "close": format(price, "f"),
                "adjustedClose": format(price, "f"),
                "volume": "1000",
                "sourceSnapshotId": snapshot_id,
            },
        )
    return bars


def make_scenario_input() -> dict[str, object]:
    return {
        "baseRevenue": "1000",
        "currentPrice": "10",
        "netDebt": "100",
        "dilutedShares": "10",
        "currency": "USD",
        "sourceSnapshotIds": ["snap_fundamentals_v1"],
        "scenarios": [
            {
                "name": "BULL",
                "revenueGrowth": "0.20",
                "targetEbitdaMargin": "0.20",
                "evToEbitdaMultiple": "10",
                "probability": "0.20",
            },
            {
                "name": "BASE",
                "revenueGrowth": "0.10",
                "targetEbitdaMargin": "0.15",
                "evToEbitdaMultiple": "8",
                "probability": "0.50",
            },
            {
                "name": "BEAR",
                "revenueGrowth": "0",
                "targetEbitdaMargin": "0.10",
                "evToEbitdaMultiple": "5",
                "probability": "0.30",
            },
        ],
    }


def make_fundamentals() -> list[dict[str, object]]:
    values = {
        "revenue": "1000",
        "grossProfit": "400",
        "operatingIncome": "200",
        "netIncome": "100",
        "operatingCashFlow": "250",
        "capitalExpenditure": "100",
    }
    return [
        {
            "name": name,
            "value": value,
            "unit": "USD",
            "periodType": "TTM",
            "periodEndDate": "2024-12-31",
            "sourceSnapshotId": "snap_fundamentals_v1",
        }
        for name, value in values.items()
    ]


def make_complete_fundamentals() -> list[dict[str, object]]:
    """Return a reviewed multi-period fixture covering every Phase 4 ratio."""
    result: list[dict[str, object]] = []
    annual_values = (
        ("2021-12-31", "600", "30"),
        ("2022-12-31", "700", "40"),
        ("2023-12-31", "800", "50"),
        ("2024-12-31", "1000", "60"),
    )
    for period_end, revenue, capex in annual_values:
        result.extend(
            _fundamental_rows(
                {"revenue": revenue, "capitalExpenditure": capex},
                unit="USD",
                period_type="ANNUAL",
                period_end=period_end,
            ),
        )

    result.extend(
        _fundamental_rows(
            {
                "revenue": "800",
                "grossProfit": "320",
                "operatingIncome": "120",
                "netIncome": "80",
                "operatingCashFlow": "140",
                "capitalExpenditure": "40",
                "totalDebt": "300",
                "totalEquity": "400",
                "commonEquity": "380",
                "cashAndEquivalents": "100",
                "currentAssets": "250",
                "currentLiabilities": "125",
                "ebit": "120",
                "interestExpense": "20",
                "totalAssets": "1000",
                "investedCapital": "600",
                "ebitda": "160",
                "preferredEquity": "10",
                "minorityInterest": "20",
            },
            unit="USD",
            period_type="TTM",
            period_end="2023-12-31",
        ),
    )
    result.extend(
        _fundamental_rows(
            {
                "revenue": "1000",
                "grossProfit": "450",
                "operatingIncome": "200",
                "netIncome": "120",
                "operatingCashFlow": "220",
                "capitalExpenditure": "60",
                "totalDebt": "280",
                "totalEquity": "500",
                "commonEquity": "470",
                "cashAndEquivalents": "130",
                "currentAssets": "300",
                "currentLiabilities": "120",
                "ebit": "200",
                "interestExpense": "25",
                "totalAssets": "1100",
                "investedCapital": "650",
                "ebitda": "250",
                "preferredEquity": "10",
                "minorityInterest": "20",
            },
            unit="USD",
            period_type="TTM",
            period_end="2024-12-31",
        ),
    )
    for period_end, diluted_eps, forward_eps, shares, tax_rate in (
        ("2023-12-31", "2", "3", "100", "0.25"),
        ("2024-12-31", "3", "4", "105", "0.25"),
    ):
        result.extend(
            _fundamental_rows(
                {"dilutedEPS": diluted_eps, "forwardDilutedEPS": forward_eps},
                unit="USD_PER_SHARE",
                period_type="TTM",
                period_end=period_end,
            ),
        )
        result.extend(
            _fundamental_rows(
                {"dilutedShares": shares},
                unit="SHARES",
                period_type="TTM",
                period_end=period_end,
            ),
        )
        result.extend(
            _fundamental_rows(
                {"effectiveTaxRate": tax_rate},
                unit="RATIO",
                period_type="TTM",
                period_end=period_end,
            ),
        )
    return result


def _fundamental_rows(
    values: dict[str, str],
    *,
    unit: str,
    period_type: str,
    period_end: str,
) -> list[dict[str, object]]:
    return [
        {
            "name": name,
            "value": value,
            "unit": unit,
            "periodType": period_type,
            "periodEndDate": period_end,
            "sourceSnapshotId": f"snap_fundamentals_{period_end}_{unit.lower()}",
        }
        for name, value in values.items()
    ]
