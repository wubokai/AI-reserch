"""Phase 4 technical-indicator and deterministic-trend boundary tests."""

from __future__ import annotations

from typing import TYPE_CHECKING, cast

from tests.factories import make_payload

if TYPE_CHECKING:
    from fastapi.testclient import TestClient


def _metrics(body: dict[str, object]) -> dict[str, dict[str, object]]:
    return {
        cast("str", metric["name"]): metric
        for metric in cast("list[dict[str, object]]", body["metrics"])
    }


def test_technical_windows_change_from_unavailable_at_exact_boundaries(
    client: TestClient,
) -> None:
    nineteen = _metrics(
        client.post("/analytics/v1/technicals", json=make_payload(list(range(100, 119)))).json(),
    )
    twenty = _metrics(
        client.post("/analytics/v1/technicals", json=make_payload(list(range(100, 120)))).json(),
    )
    forty_nine = _metrics(
        client.post("/analytics/v1/technicals", json=make_payload(list(range(100, 149)))).json(),
    )
    fifty = _metrics(
        client.post("/analytics/v1/technicals", json=make_payload(list(range(100, 150)))).json(),
    )
    one_ninety_nine = client.post(
        "/analytics/v1/technicals",
        json=make_payload(list(range(100, 299))),
    ).json()
    two_hundred = client.post(
        "/analytics/v1/technicals",
        json=make_payload(list(range(100, 300))),
    ).json()

    assert nineteen["sma_20"]["status"] == "NOT_AVAILABLE"
    assert twenty["sma_20"]["status"] == "AVAILABLE"
    assert nineteen["bollinger_middle_20"]["status"] == "NOT_AVAILABLE"
    assert twenty["bollinger_middle_20"]["status"] == "AVAILABLE"
    assert forty_nine["sma_50"]["status"] == "NOT_AVAILABLE"
    assert fifty["sma_50"]["status"] == "AVAILABLE"
    assert _metrics(one_ninety_nine)["sma_200"]["status"] == "NOT_AVAILABLE"
    assert one_ninety_nine["trend"] is None
    assert _metrics(two_hundred)["sma_200"]["status"] == "AVAILABLE"
    assert two_hundred["trend"] is not None


def test_reviewed_increasing_series_locks_technical_values_and_trend(
    client: TestClient,
) -> None:
    response = client.post(
        "/analytics/v1/technicals",
        json=make_payload(list(range(100, 300))),
    )
    body = response.json()
    metrics = _metrics(body)

    assert response.status_code == 200
    assert metrics["sma_20"]["value"] == "289.5000"
    assert metrics["sma_50"]["value"] == "274.5000"
    assert metrics["sma_200"]["value"] == "199.5000"
    assert metrics["rsi_14"]["value"] == "100.00000000"
    assert metrics["bollinger_upper_20"]["value"] == "301.3322"
    assert metrics["bollinger_middle_20"]["value"] == "289.5000"
    assert metrics["bollinger_lower_20"]["value"] == "277.6678"
    assert metrics["atr_14"]["value"] == "2.0000"
    assert metrics["distance_from_52_week_high"]["value"] == "0.00000000"
    assert metrics["distance_from_52_week_low"]["value"] == "1.99000000"
    assert metrics["volume_moving_average_20"]["value"] == "1000.00"
    assert metrics["trend_score"]["value"] == "5"
    assert body["trend"] == {
        "classification": "STRONG_UPTREND",
        "score": 5,
        "signals": {
            "closeAboveSma20": 1,
            "sma20AboveSma50": 1,
            "sma50AboveSma200": 1,
            "sma50Slope20": 1,
            "closeDistanceSma200": 1,
        },
        "sampleSize": 200,
        "periodStart": "2024-01-01",
        "periodEnd": "2024-07-18",
        "calculationVersion": "quant_v1",
        "inputSnapshotIds": ["snap_prices_v1"],
        "warnings": [],
    }


def test_decreasing_and_flat_series_have_deterministic_labels(client: TestClient) -> None:
    decreasing = client.post(
        "/analytics/v1/technicals",
        json=make_payload(list(range(300, 100, -1))),
    ).json()
    flat = client.post(
        "/analytics/v1/technicals",
        json=make_payload([100] * 200),
    ).json()

    assert decreasing["trend"]["classification"] == "STRONG_DOWNTREND"
    assert decreasing["trend"]["score"] == -5
    assert flat["trend"]["classification"] == "DOWNTREND"
    assert flat["trend"]["score"] == -3
    assert _metrics(flat)["rsi_14"]["value"] == "50.00000000"


def test_negative_volume_and_invalid_ohlc_are_rejected(client: TestClient) -> None:
    negative_volume = make_payload([100, 101])
    cast("list[dict[str, object]]", negative_volume["prices"])[1]["volume"] = "-1"
    response = client.post("/analytics/v1/technicals", json=negative_volume)
    assert response.status_code == 422
    assert response.json()["code"] == "NEGATIVE_VOLUME"

    invalid_ohlc = make_payload([100, 101])
    cast("list[dict[str, object]]", invalid_ohlc["prices"])[1]["high"] = "100"
    response = client.post("/analytics/v1/technicals", json=invalid_ohlc)
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_OHLC"
