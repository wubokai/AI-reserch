"""HTTP and strict-contract tests for analytics endpoints."""

from __future__ import annotations

import json
from pathlib import Path
from typing import TYPE_CHECKING

from ai_quant_analytics.contracts import AnalysisResponse, FullAnalysisRequest, InsightsResponse
from tests.factories import make_fundamentals, make_payload, make_scenario_input

if TYPE_CHECKING:
    from fastapi.testclient import TestClient

_REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


def test_all_analytics_endpoints_publish_canonical_metric_order(client: TestClient) -> None:
    payload = make_payload(
        list(range(100, 140)),
        fundamentals=make_fundamentals(),
        scenario_input=make_scenario_input(),
    )
    expected = {
        "/analytics/v1/returns": [
            "latest_daily_return",
            "total_return",
            "annualized_return",
            "cagr",
            "rolling_return_20",
            "benchmark_total_return",
            "excess_total_return",
            "benchmark_annualized_return",
            "excess_annualized_return",
        ],
        "/analytics/v1/risk": [
            "daily_volatility",
            "annualized_volatility",
            "downside_deviation",
            "max_drawdown",
            "drawdown_duration",
            "historical_var_95",
            "historical_cvar_95",
            "sharpe_ratio",
            "sortino_ratio",
            "calmar_ratio",
            "beta",
            "alpha",
            "correlation",
        ],
        "/analytics/v1/technicals": [
            "sma_20",
            "sma_50",
            "sma_200",
            "ema_12",
            "ema_26",
            "rsi_14",
            "macd",
            "macd_signal",
            "bollinger_upper_20",
            "bollinger_middle_20",
            "bollinger_lower_20",
            "atr_14",
            "distance_from_52_week_high",
            "distance_from_52_week_low",
            "volume_moving_average_20",
            "trend_score",
        ],
        "/analytics/v1/fundamentals": [
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
        ],
        "/analytics/v1/valuation": [
            "market_capitalization",
            "price_to_earnings",
            "forward_price_to_earnings",
            "price_to_sales",
            "price_to_book",
            "enterprise_value",
            "enterprise_value_to_revenue",
            "enterprise_value_to_ebitda",
            "free_cash_flow_yield",
        ],
        "/analytics/v1/scenarios": [
            "scenario_bull_raw_implied_price",
            "scenario_bull_implied_price",
            "scenario_bull_upside_downside",
            "scenario_base_raw_implied_price",
            "scenario_base_implied_price",
            "scenario_base_upside_downside",
            "scenario_bear_raw_implied_price",
            "scenario_bear_implied_price",
            "scenario_bear_upside_downside",
            "weighted_scenario_value",
        ],
    }
    for path, metric_names in expected.items():
        response = client.post(path, json=payload)
        assert response.status_code == 200
        body = response.json()
        assert body["schemaVersion"] == "analytics_full_response_v1"
        assert body["calculationVersion"] == "quant_v1"
        assert [metric["name"] for metric in body["metrics"]] == metric_names


def test_full_analysis_is_byte_stable_for_identical_input(client: TestClient) -> None:
    payload = make_payload(
        list(range(100, 140)),
        fundamentals=make_fundamentals(),
        scenario_input=make_scenario_input(),
    )
    first = client.post("/analytics/v1/full-analysis", json=payload)
    second = client.post("/analytics/v1/full-analysis", json=payload)

    assert first.status_code == 200
    assert first.content == second.content


def test_contract_rejects_unknown_fields_numeric_decimals_and_wrong_version(
    client: TestClient,
) -> None:
    unknown = make_payload([100, 101])
    unknown["unexpected"] = True
    assert client.post("/analytics/v1/returns", json=unknown).status_code == 400

    numeric_decimal = make_payload([100, 101])
    numeric_decimal["riskFreeRateAnnual"] = 0.04
    assert client.post("/analytics/v1/risk", json=numeric_decimal).status_code == 400

    wrong_version = make_payload([100, 101])
    wrong_version["calculationVersion"] = "quant_v2"
    response = client.post("/analytics/v1/returns", json=wrong_version)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"


def test_error_envelope_uses_effective_request_and_safe_research_ids(client: TestClient) -> None:
    payload = make_payload([100, 101])
    payload["riskFreeRateAnnual"] = "NaN"
    response = client.post(
        "/analytics/v1/risk",
        json=payload,
        headers={"X-Request-Id": "req_contract-1", "X-Research-Id": "research-1"},
    )

    assert response.status_code == 400
    assert response.json() == {
        "timestamp": response.json()["timestamp"],
        "status": 400,
        "code": "INVALID_REQUEST",
        "message": "The analytics request does not match the required schema.",
        "requestId": "req_contract-1",
        "researchId": "research-1",
        "details": {
            "issues": [
                {"path": "body.riskFreeRateAnnual", "type": "string_pattern_mismatch"},
            ],
        },
    }


def test_pydantic_envelopes_match_canonical_schema_fields() -> None:
    schema_directory = _REPOSITORY_ROOT / "packages/shared-schemas/analytics"
    pairs = (
        (
            FullAnalysisRequest,
            schema_directory / "full-analysis-request.schema.json",
        ),
        (
            AnalysisResponse,
            schema_directory / "full-analysis-response.schema.json",
        ),
        (
            InsightsResponse,
            schema_directory / "insights-response.schema.json",
        ),
    )
    for model, path in pairs:
        canonical = json.loads(path.read_text(encoding="utf-8"))
        generated = model.model_json_schema(by_alias=True)
        assert canonical["$schema"] == "https://json-schema.org/draft/2020-12/schema"
        assert set(canonical["required"]) == set(generated["required"])
        assert set(canonical["properties"]) == set(generated["properties"])
