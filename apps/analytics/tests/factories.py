"""Deterministic request factories used by analytics tests."""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal


def make_payload(
    prices: list[int | str | Decimal],
    *,
    start: date = date(2024, 1, 1),
    day_step: int = 1,
    fundamentals: list[dict[str, object]] | None = None,
    scenario_input: dict[str, object] | None = None,
) -> dict[str, object]:
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
                "sourceSnapshotId": "snap_prices_v1",
            },
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
        "benchmarkSymbol": None,
        "benchmarkPrices": [],
        "riskFreeRateAnnual": "0.04",
        "minimumAcceptedReturnAnnual": "0",
        "fundamentals": [] if fundamentals is None else fundamentals,
        "scenarioInput": scenario_input,
    }


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
