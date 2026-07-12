"""Golden and semantic tests for fundamental, valuation, and scenario modules."""

from __future__ import annotations

from typing import TYPE_CHECKING

from tests.factories import make_fundamentals, make_payload, make_scenario_input

if TYPE_CHECKING:
    from fastapi.testclient import TestClient


def test_golden_fundamental_margins_use_one_common_period(client: TestClient) -> None:
    response = client.post(
        "/analytics/v1/fundamentals",
        json=make_payload([100, 101], fundamentals=make_fundamentals()),
    )
    metrics = {metric["name"]: metric["value"] for metric in response.json()["metrics"]}

    assert {
        name: metrics[name]
        for name in (
            "gross_margin",
            "operating_margin",
            "net_margin",
            "free_cash_flow_margin",
        )
    } == {
        "gross_margin": "0.40000000",
        "operating_margin": "0.20000000",
        "net_margin": "0.10000000",
        "free_cash_flow_margin": "0.15000000",
    }


def test_mock_provider_normalized_gross_margin_and_fcf_are_consumed(client: TestClient) -> None:
    fundamentals: list[dict[str, object]] = [
        {
            "name": "revenue",
            "value": "1000",
            "unit": "USD",
            "periodType": "TTM",
            "periodEndDate": "2024-12-31",
            "sourceSnapshotId": "snap_fundamentals_v1",
        },
        {
            "name": "grossMargin",
            "value": "0.40",
            "unit": "RATIO",
            "periodType": "TTM",
            "periodEndDate": "2024-12-31",
            "sourceSnapshotId": "snap_fundamentals_v1",
        },
        {
            "name": "freeCashFlow",
            "value": "150",
            "unit": "USD",
            "periodType": "TTM",
            "periodEndDate": "2024-12-31",
            "sourceSnapshotId": "snap_fundamentals_v1",
        },
    ]
    response = client.post(
        "/analytics/v1/fundamentals",
        json=make_payload([100, 101], fundamentals=fundamentals),
    )
    metrics = {metric["name"]: metric for metric in response.json()["metrics"]}

    assert metrics["gross_margin"]["value"] == "0.40000000"
    assert metrics["free_cash_flow_margin"]["value"] == "0.15000000"
    assert metrics["operating_margin"]["status"] == "NOT_AVAILABLE"


def test_golden_scenario_values_follow_documented_ev_ebitda_formula(client: TestClient) -> None:
    payload = make_payload([100, 101], scenario_input=make_scenario_input())
    response = client.post("/analytics/v1/scenarios", json=payload)
    metrics = {metric["name"]: metric["value"] for metric in response.json()["metrics"]}

    assert response.status_code == 200
    assert metrics == {
        "scenario_bull_raw_implied_price": "230.0000",
        "scenario_bull_implied_price": "230.0000",
        "scenario_bull_upside_downside": "22.00000000",
        "scenario_base_raw_implied_price": "122.0000",
        "scenario_base_implied_price": "122.0000",
        "scenario_base_upside_downside": "11.20000000",
        "scenario_bear_raw_implied_price": "40.0000",
        "scenario_bear_implied_price": "40.0000",
        "scenario_bear_upside_downside": "3.00000000",
        "weighted_scenario_value": "119.0000",
    }
    implied_values = [
        float(metrics[f"scenario_{name}_implied_price"]) for name in ("bull", "base", "bear")
    ]
    assert min(implied_values) <= float(metrics["weighted_scenario_value"]) <= max(implied_values)


def test_valuation_uses_explicit_scenario_currency_and_net_debt(client: TestClient) -> None:
    response = client.post(
        "/analytics/v1/valuation",
        json=make_payload([100, 101], scenario_input=make_scenario_input()),
    )
    metrics = {metric["name"]: metric for metric in response.json()["metrics"]}

    assert metrics["market_capitalization"]["value"] == "100.00"
    assert metrics["market_capitalization"]["unit"] == "USD"
    assert metrics["enterprise_value"]["value"] == "200.00"


def test_scenario_probability_and_name_set_are_semantically_validated(
    client: TestClient,
) -> None:
    wrong_probability = make_scenario_input()
    scenarios = wrong_probability["scenarios"]
    assert isinstance(scenarios, list)
    scenarios[0]["probability"] = "0.21"
    response = client.post(
        "/analytics/v1/scenarios",
        json=make_payload([100, 101], scenario_input=wrong_probability),
    )
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_SCENARIO_PROBABILITY"

    duplicate_name = make_scenario_input()
    duplicate_scenarios = duplicate_name["scenarios"]
    assert isinstance(duplicate_scenarios, list)
    duplicate_scenarios[2]["name"] = "BULL"
    response = client.post(
        "/analytics/v1/scenarios",
        json=make_payload([100, 101], scenario_input=duplicate_name),
    )
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_SCENARIO_SET"


def test_negative_scenario_equity_preserves_raw_value_and_floors_display(
    client: TestClient,
) -> None:
    scenario_input = make_scenario_input()
    scenarios = scenario_input["scenarios"]
    assert isinstance(scenarios, list)
    scenarios[2]["targetEbitdaMargin"] = "-0.10"
    response = client.post(
        "/analytics/v1/scenarios",
        json=make_payload([100, 101], scenario_input=scenario_input),
    )
    metrics = {metric["name"]: metric for metric in response.json()["metrics"]}

    assert metrics["scenario_bear_raw_implied_price"]["value"] == "-60.0000"
    assert metrics["scenario_bear_implied_price"]["value"] == "0.0000"
    assert metrics["scenario_bear_upside_downside"]["value"] == "-1.00000000"
    assert metrics["scenario_bear_implied_price"]["warnings"][0]["code"] == (
        "NON_POSITIVE_FORECAST_EBITDA"
    )


def test_unprofitable_company_uses_revenue_multiple_without_zero_price(
    client: TestClient,
) -> None:
    scenario_input = make_scenario_input()
    scenarios = scenario_input["scenarios"]
    assert isinstance(scenarios, list)
    for scenario in scenarios:
        scenario["targetEbitdaMargin"] = "-0.25"
        scenario["valuationMethod"] = "EV_REVENUE"
        scenario["valuationMultiple"] = "2"

    response = client.post(
        "/analytics/v1/scenarios",
        json=make_payload([100, 101], scenario_input=scenario_input),
    )
    metrics = {metric["name"]: metric for metric in response.json()["metrics"]}

    assert response.status_code == 200
    assert metrics["scenario_bull_implied_price"]["value"] == "230.0000"
    assert metrics["scenario_base_implied_price"]["value"] == "210.0000"
    assert metrics["scenario_bear_implied_price"]["value"] == "190.0000"
    assert metrics["scenario_bear_implied_price"]["warnings"] == []
