"""Golden and boundary tests for Phase 3 quantitative metrics."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ai_quant_analytics.serialization import decimal_text
from tests.factories import make_payload

if TYPE_CHECKING:
    from fastapi.testclient import TestClient


def test_golden_core_metrics_match_reviewed_values(client: TestClient) -> None:
    payload = make_payload(list(range(100, 140)))
    response = client.post("/analytics/v1/full-analysis", json=payload)

    assert response.status_code == 200
    metrics = {metric["name"]: metric for metric in response.json()["metrics"]}
    assert {
        name: metrics[name]["value"]
        for name in (
            "total_return",
            "cagr",
            "annualized_volatility",
            "max_drawdown",
            "sharpe_ratio",
            "rsi_14",
            "macd",
            "macd_signal",
        )
    } == {
        "total_return": "0.39000000",
        "cagr": "20.84545563",
        "annualized_volatility": "0.01303829",
        "max_drawdown": "0.00000000",
        "sharpe_ratio": "160.88576736",
        "rsi_14": "100.00000000",
        "macd": "6.3867",
        "macd_signal": "6.1439",
    }


def test_max_drawdown_uses_negative_peak_to_trough_convention(client: TestClient) -> None:
    response = client.post(
        "/analytics/v1/risk",
        json=make_payload([100, 120, 90, 110]),
    )
    metrics = {metric["name"]: metric for metric in response.json()["metrics"]}

    assert metrics["max_drawdown"]["value"] == "-0.25000000"
    assert metrics["annualized_volatility"]["value"] is None
    assert metrics["annualized_volatility"]["status"] == "NOT_AVAILABLE"


def test_flat_prices_produce_rsi_50_and_no_nan_or_negative_zero(client: TestClient) -> None:
    payload = make_payload([100] * 40)
    technicals = client.post("/analytics/v1/technicals", json=payload).json()
    risk = client.post("/analytics/v1/risk", json=payload).json()
    technical_metrics = {metric["name"]: metric for metric in technicals["metrics"]}
    risk_metrics = {metric["name"]: metric for metric in risk["metrics"]}

    assert technical_metrics["rsi_14"]["value"] == "50.00000000"
    assert risk_metrics["max_drawdown"]["value"] == "0.00000000"
    assert risk_metrics["sharpe_ratio"]["value"] is None
    assert risk_metrics["sharpe_ratio"]["warnings"][0]["code"] == "ZERO_DENOMINATOR"
    assert "NaN" not in str(technicals)
    assert "Infinity" not in str(risk)


def test_half_even_decimal_boundary_is_explicit() -> None:
    assert decimal_text(1.005, places=2) == "1.00"
    assert decimal_text(1.015, places=2) == "1.02"
    assert decimal_text(-0.0, places=8) == "0.00000000"


def test_identical_duplicate_is_removed_but_conflict_fails(client: TestClient) -> None:
    payload = make_payload([100, 101, 102])
    bars = payload["prices"]
    assert isinstance(bars, list)
    bars.append(dict(bars[1]))
    response = client.post("/analytics/v1/returns", json=payload)

    assert response.status_code == 200
    assert response.json()["sampleSize"] == 3
    assert response.json()["warnings"][0]["code"] == "DUPLICATE_DATE_REMOVED"

    conflicting = make_payload([100, 101, 102])
    conflict_bars = conflicting["prices"]
    assert isinstance(conflict_bars, list)
    duplicate = dict(conflict_bars[1])
    duplicate["adjustedClose"] = "99"
    conflict_bars.append(duplicate)
    conflict = client.post("/analytics/v1/returns", json=conflicting)
    assert conflict.status_code == 422
    assert conflict.json()["code"] == "DUPLICATE_DATE_CONFLICT"
