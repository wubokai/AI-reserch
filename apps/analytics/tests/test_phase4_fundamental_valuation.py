"""Phase 4 golden and semantic-boundary tests for fundamentals and valuation."""

from __future__ import annotations

from copy import deepcopy
from typing import TYPE_CHECKING, cast

from tests.factories import (
    make_complete_fundamentals,
    make_payload,
    make_scenario_input,
)

if TYPE_CHECKING:
    from fastapi.testclient import TestClient


def _metrics(body: dict[str, object]) -> dict[str, dict[str, object]]:
    return {
        cast("str", metric["name"]): metric
        for metric in cast("list[dict[str, object]]", body["metrics"])
    }


def _set_value(
    fundamentals: list[dict[str, object]],
    name: str,
    period_end: str,
    value: str,
    *,
    unit: str | None = None,
) -> None:
    for item in fundamentals:
        if (
            item["name"] == name
            and item["periodEndDate"] == period_end
            and (unit is None or item["unit"] == unit)
        ):
            item["value"] = value
            return
    message = "fixture value was not found"
    raise AssertionError(message)


def _warning_codes(metric: dict[str, object]) -> set[str]:
    return {
        cast("str", item["code"]) for item in cast("list[dict[str, object]]", metric["warnings"])
    }


def test_reviewed_fundamental_golden_set_locks_every_metric(client: TestClient) -> None:
    response = client.post(
        "/analytics/v1/fundamentals",
        json=make_payload([100, 101], fundamentals=make_complete_fundamentals()),
    )
    metrics = _metrics(response.json())
    expected = {
        "revenue_growth_yoy": "0.25000000",
        "revenue_cagr": "0.18558091",
        "gross_margin": "0.45000000",
        "operating_margin": "0.20000000",
        "net_margin": "0.12000000",
        "free_cash_flow_margin": "0.16000000",
        "eps_growth": "0.50000000",
        "debt_to_equity": "0.56000000",
        "net_debt": "150.00",
        "current_ratio": "2.50000000",
        "interest_coverage": "8.00000000",
        "return_on_equity": "0.26666667",
        "return_on_assets": "0.11428571",
        "return_on_invested_capital": "0.24000000",
        "share_dilution": "0.05000000",
        "capex_trend_slope": "10.00",
    }

    assert response.status_code == 200
    assert {name: metrics[name]["value"] for name in expected} == expected
    assert all(metrics[name]["status"] == "AVAILABLE" for name in expected)
    assert metrics["capex_trend_slope"]["unit"] == "USD_PER_PERIOD"
    assert all(cast("int", metrics[name]["sampleSize"]) > 0 for name in expected)
    assert all(metrics[name]["periodStart"] is not None for name in expected)
    assert all(metrics[name]["periodEnd"] is not None for name in expected)


def test_reviewed_valuation_golden_set_uses_financial_period_and_price_date(
    client: TestClient,
) -> None:
    response = client.post(
        "/analytics/v1/valuation",
        json=make_payload(
            [100, 101],
            fundamentals=make_complete_fundamentals(),
            scenario_input=make_scenario_input(),
        ),
    )
    metrics = _metrics(response.json())
    expected = {
        "market_capitalization": "100.00",
        "price_to_earnings": "3.33333333",
        "forward_price_to_earnings": "2.50000000",
        "price_to_sales": "0.10000000",
        "price_to_book": "0.21276596",
        "enterprise_value": "280.00",
        "enterprise_value_to_revenue": "0.28000000",
        "enterprise_value_to_ebitda": "1.12000000",
        "free_cash_flow_yield": "1.60000000",
    }

    assert response.status_code == 200
    assert {name: metrics[name]["value"] for name in expected} == expected
    assert metrics["market_capitalization"]["periodStart"] == "2024-01-02"
    assert metrics["price_to_earnings"]["periodStart"] == "2024-12-31"
    assert metrics["price_to_earnings"]["periodEnd"] == "2024-01-02"
    assert set(cast("list[str]", metrics["enterprise_value"]["inputSnapshotIds"])) == {
        "snap_prices_v1",
        "snap_fundamentals_v1",
        "snap_fundamentals_2024-12-31_usd",
    }


def test_cross_zero_eps_negative_equity_and_zero_liabilities_are_classified(
    client: TestClient,
) -> None:
    fundamentals = make_complete_fundamentals()
    _set_value(fundamentals, "dilutedEPS", "2023-12-31", "0")
    _set_value(fundamentals, "dilutedEPS", "2024-12-31", "-1")
    _set_value(fundamentals, "totalEquity", "2024-12-31", "-1")
    _set_value(fundamentals, "commonEquity", "2024-12-31", "-1")
    _set_value(fundamentals, "currentLiabilities", "2024-12-31", "0")
    payload = make_payload(
        [100, 101],
        fundamentals=fundamentals,
        scenario_input=make_scenario_input(),
    )
    fundamental_metrics = _metrics(
        client.post("/analytics/v1/fundamentals", json=payload).json(),
    )
    valuation_metrics = _metrics(client.post("/analytics/v1/valuation", json=payload).json())

    assert fundamental_metrics["eps_growth"]["status"] == "NOT_APPLICABLE"
    assert _warning_codes(fundamental_metrics["eps_growth"]) == {"CROSS_ZERO_GROWTH"}
    assert fundamental_metrics["debt_to_equity"]["status"] == "NOT_APPLICABLE"
    assert _warning_codes(fundamental_metrics["debt_to_equity"]) == {"NON_POSITIVE_EQUITY"}
    assert fundamental_metrics["current_ratio"]["status"] == "NOT_AVAILABLE"
    assert _warning_codes(fundamental_metrics["current_ratio"]) == {"ZERO_DENOMINATOR"}
    assert valuation_metrics["price_to_earnings"]["status"] == "NOT_APPLICABLE"
    assert _warning_codes(valuation_metrics["price_to_earnings"]) == {"NEGATIVE_EARNINGS"}
    assert valuation_metrics["price_to_book"]["status"] == "NOT_APPLICABLE"


def test_invalid_tax_rate_and_non_positive_ebitda_are_not_silently_clipped(
    client: TestClient,
) -> None:
    fundamentals = make_complete_fundamentals()
    _set_value(fundamentals, "effectiveTaxRate", "2024-12-31", "1.2")
    _set_value(fundamentals, "ebitda", "2024-12-31", "-10")
    payload = make_payload(
        [100, 101],
        fundamentals=fundamentals,
        scenario_input=make_scenario_input(),
    )
    fundamental_metrics = _metrics(
        client.post("/analytics/v1/fundamentals", json=payload).json(),
    )
    valuation_metrics = _metrics(client.post("/analytics/v1/valuation", json=payload).json())

    assert fundamental_metrics["return_on_invested_capital"]["status"] == "NOT_AVAILABLE"
    assert _warning_codes(fundamental_metrics["return_on_invested_capital"]) == {"INVALID_TAX_RATE"}
    assert valuation_metrics["enterprise_value_to_ebitda"]["status"] == "NOT_APPLICABLE"
    assert _warning_codes(valuation_metrics["enterprise_value_to_ebitda"]) == {
        "NON_POSITIVE_EBITDA"
    }


def test_missing_forecast_and_source_unit_mismatch_are_explicit(client: TestClient) -> None:
    fundamentals = make_complete_fundamentals()
    fundamentals = [item for item in fundamentals if item["name"] != "forwardDilutedEPS"]
    for item in fundamentals:
        if item["name"] == "revenue":
            item["unit"] = "EUR"
    metrics = _metrics(
        client.post(
            "/analytics/v1/valuation",
            json=make_payload(
                [100, 101],
                fundamentals=fundamentals,
                scenario_input=make_scenario_input(),
            ),
        ).json(),
    )

    assert metrics["forward_price_to_earnings"]["status"] == "NOT_AVAILABLE"
    assert _warning_codes(metrics["forward_price_to_earnings"]) == {"FORECAST_DATA_UNAVAILABLE"}
    assert metrics["price_to_sales"]["status"] == "NOT_AVAILABLE"
    assert _warning_codes(metrics["price_to_sales"]) == {"SOURCE_UNIT_MISMATCH"}


def test_adjacent_ttm_periods_are_not_misreported_as_year_over_year(
    client: TestClient,
) -> None:
    fundamentals: list[dict[str, object]] = [
        {
            "name": "revenue",
            "value": value,
            "unit": "USD",
            "periodType": "TTM",
            "periodEndDate": period_end,
            "sourceSnapshotId": f"snap_{period_end}",
        }
        for period_end, value in (("2024-09-30", "900"), ("2024-12-31", "1000"))
    ]
    metrics = _metrics(
        client.post(
            "/analytics/v1/fundamentals",
            json=make_payload([100, 101], fundamentals=fundamentals),
        ).json(),
    )

    assert metrics["revenue_growth_yoy"]["status"] == "NOT_AVAILABLE"
    assert _warning_codes(metrics["revenue_growth_yoy"]) == {"INSUFFICIENT_DATA"}


def test_duplicate_fundamental_conflict_and_etf_capability_matrix(client: TestClient) -> None:
    fundamentals = make_complete_fundamentals()
    duplicate = deepcopy(fundamentals[-1])
    duplicate["value"] = "0.30"
    conflict = client.post(
        "/analytics/v1/fundamentals",
        json=make_payload([100, 101], fundamentals=[*fundamentals, duplicate]),
    )
    assert conflict.status_code == 422
    assert conflict.json()["code"] == "DUPLICATE_FUNDAMENTAL_CONFLICT"

    etf_payload = make_payload(
        [100, 101],
        fundamentals=fundamentals,
        scenario_input=make_scenario_input(),
    )
    etf_payload["securityType"] = "ETF"
    fundamental_metrics = _metrics(
        client.post("/analytics/v1/fundamentals", json=etf_payload).json(),
    )
    valuation_metrics = _metrics(
        client.post("/analytics/v1/valuation", json=etf_payload).json(),
    )
    assert all(item["status"] == "NOT_APPLICABLE" for item in fundamental_metrics.values())
    assert all(item["status"] == "NOT_APPLICABLE" for item in valuation_metrics.values())
    assert all(
        _warning_codes(item) == {"ETF_NOT_APPLICABLE"} for item in valuation_metrics.values()
    )
