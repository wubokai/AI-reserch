"""Phase 4 golden, threshold, alignment, and numerical-boundary market tests."""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal, getcontext
from pathlib import Path
from typing import TYPE_CHECKING, cast

from tests.factories import make_payload

if TYPE_CHECKING:
    from fastapi.testclient import TestClient

_GOLDEN_PATH = Path(__file__).parent / "fixtures/phase4_market_golden.json"


def _golden_payload() -> tuple[dict[str, object], dict[str, str]]:
    fixture = json.loads(_GOLDEN_PATH.read_text(encoding="utf-8"))
    getcontext().prec = 40

    def expand(initial_key: str, cycle_key: str) -> list[Decimal]:
        current = Decimal(fixture[initial_key])
        result = [current]
        cycle = [Decimal(item) for item in fixture[cycle_key]]
        for daily_return in cycle * fixture["cycleCount"]:
            current *= Decimal(1) + daily_return
            result.append(current)
        return result

    payload = make_payload(
        expand("initialAssetPrice", "assetReturnCycle"),
        benchmark_prices=expand("initialBenchmarkPrice", "benchmarkReturnCycle"),
    )
    return payload, cast("dict[str, str]", fixture["expected"])


def _metrics(body: dict[str, object]) -> dict[str, dict[str, object]]:
    return {
        cast("str", metric["name"]): metric
        for metric in cast("list[dict[str, object]]", body["metrics"])
    }


def test_reviewed_market_golden_set_locks_all_return_and_risk_values(
    client: TestClient,
) -> None:
    payload, expected = _golden_payload()
    returns = client.post("/analytics/v1/returns", json=payload)
    risk = client.post("/analytics/v1/risk", json=payload)

    assert returns.status_code == risk.status_code == 200
    actual = _metrics(returns.json()) | _metrics(risk.json())
    assert {name: actual[name]["value"] for name in expected} == expected
    assert all(actual[name]["status"] == "AVAILABLE" for name in expected)
    assert all(actual[name]["calculationVersion"] == "quant_v1" for name in expected)
    assert all(cast("int", actual[name]["sampleSize"]) > 0 for name in expected)


def test_hand_calculated_returns_drawdown_and_duration(client: TestClient) -> None:
    payload = make_payload([100, 120, 90, 110])
    returns = _metrics(client.post("/analytics/v1/returns", json=payload).json())
    risk = _metrics(client.post("/analytics/v1/risk", json=payload).json())

    assert returns["latest_daily_return"]["value"] == "0.22222222"
    assert returns["total_return"]["value"] == "0.10000000"
    assert risk["max_drawdown"]["value"] == "-0.25000000"
    assert risk["drawdown_duration"]["value"] == "2"
    duration_warnings = cast("list[dict[str, object]]", risk["drawdown_duration"]["warnings"])
    assert duration_warnings[0]["code"] == "OPEN_DRAWDOWN"


def test_minimum_sample_thresholds_are_inclusive(client: TestClient) -> None:
    twenty_nine = _metrics(
        client.post("/analytics/v1/risk", json=make_payload(list(range(100, 130)))).json(),
    )
    thirty = _metrics(
        client.post("/analytics/v1/risk", json=make_payload(list(range(100, 131)))).json(),
    )
    ninety_nine = _metrics(
        client.post("/analytics/v1/risk", json=make_payload(list(range(100, 200)))).json(),
    )
    one_hundred = _metrics(
        client.post("/analytics/v1/risk", json=make_payload(list(range(100, 201)))).json(),
    )

    assert twenty_nine["annualized_volatility"]["status"] == "NOT_AVAILABLE"
    assert thirty["annualized_volatility"]["status"] == "AVAILABLE"
    assert ninety_nine["historical_var_95"]["status"] == "NOT_AVAILABLE"
    assert one_hundred["historical_var_95"]["status"] == "AVAILABLE"


def test_benchmark_alignment_and_paired_thresholds_are_explicit(client: TestClient) -> None:
    aligned_sixty = make_payload(
        list(range(100, 161)),
        benchmark_prices=list(range(200, 261)),
    )
    shifted = make_payload(
        list(range(100, 161)),
        benchmark_prices=list(range(200, 261)),
        benchmark_start=date(2024, 1, 2),
    )
    shifted_bars = cast("list[dict[str, object]]", shifted["benchmarkPrices"])
    shifted["periodEnd"] = shifted_bars[-1]["date"]
    aligned_metrics = _metrics(client.post("/analytics/v1/risk", json=aligned_sixty).json())
    shifted_metrics = _metrics(client.post("/analytics/v1/risk", json=shifted).json())

    assert aligned_metrics["beta"]["status"] == "AVAILABLE"
    assert aligned_metrics["beta"]["sampleSize"] == 60
    assert shifted_metrics["beta"]["status"] == "NOT_AVAILABLE"
    assert shifted_metrics["beta"]["sampleSize"] == 59
    shifted_warnings = cast("list[dict[str, object]]", shifted_metrics["beta"]["warnings"])
    assert {item["code"] for item in shifted_warnings} == {
        "BENCHMARK_ALIGNMENT_REDUCED_SAMPLE",
        "INSUFFICIENT_DATA",
    }


def test_zero_denominators_and_missing_benchmark_never_publish_invalid_numbers(
    client: TestClient,
) -> None:
    flat = make_payload([100] * 101, benchmark_prices=[200] * 101)
    flat_metrics = _metrics(client.post("/analytics/v1/risk", json=flat).json())
    missing_metrics = _metrics(
        client.post("/analytics/v1/risk", json=make_payload(list(range(100, 161)))).json(),
    )

    for name in ("sharpe_ratio", "sortino_ratio", "calmar_ratio", "beta", "alpha"):
        assert flat_metrics[name]["status"] == "NOT_AVAILABLE"
        metric_warnings = cast("list[dict[str, object]]", flat_metrics[name]["warnings"])
        assert metric_warnings[-1]["code"] == "ZERO_DENOMINATOR"
    assert missing_metrics["beta"]["status"] == "NOT_AVAILABLE"
    assert missing_metrics["beta"]["sampleSize"] == 0
    assert "NaN" not in str(flat_metrics)
    assert "Infinity" not in str(flat_metrics)


def test_market_invariants_and_input_order_hold(client: TestClient) -> None:
    payload, _expected = _golden_payload()
    ordered = client.post("/analytics/v1/full-analysis", json=payload)
    shuffled = json.loads(json.dumps(payload))
    shuffled["prices"].reverse()
    shuffled["benchmarkPrices"].reverse()
    reordered = client.post("/analytics/v1/full-analysis", json=shuffled)

    assert ordered.content == reordered.content
    metrics = _metrics(ordered.json())
    assert Decimal(cast("str", metrics["max_drawdown"]["value"])) <= 0
    assert Decimal(cast("str", metrics["annualized_volatility"]["value"])) >= 0
    assert Decimal(cast("str", metrics["historical_var_95"]["value"])) >= 0
    assert Decimal(cast("str", metrics["historical_cvar_95"]["value"])) >= 0
    assert Decimal(-1) <= Decimal(cast("str", metrics["correlation"]["value"])) <= 1


def test_invalid_market_boundaries_fail_with_stable_codes(client: TestClient) -> None:
    empty = make_payload([100, 101])
    empty["prices"] = []
    assert client.post("/analytics/v1/returns", json=empty).status_code == 400

    single = make_payload([100, 101])
    single["prices"] = cast("list[object]", single["prices"])[:1]
    assert client.post("/analytics/v1/returns", json=single).status_code == 400

    missing = make_payload([100, 101])
    del cast("list[dict[str, object]]", missing["prices"])[1]["adjustedClose"]
    assert client.post("/analytics/v1/returns", json=missing).status_code == 400

    non_positive = client.post("/analytics/v1/returns", json=make_payload([100, 0]))
    assert non_positive.status_code == 422
    assert non_positive.json()["code"] == "NON_POSITIVE_PRICE"

    invalid_rate = make_payload(list(range(100, 131)))
    invalid_rate["riskFreeRateAnnual"] = "-1"
    response = client.post("/analytics/v1/risk", json=invalid_rate)
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_ANNUAL_RATE"
