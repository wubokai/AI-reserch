"""Deterministic chart-overlay and valuation-explanation tests."""

from __future__ import annotations

from typing import TYPE_CHECKING

from tests.factories import make_payload, make_scenario_input

if TYPE_CHECKING:
    from fastapi.testclient import TestClient


def test_insights_calculates_moving_averages_ranges_and_valuation(client: TestClient) -> None:
    payload = make_payload(
        list(range(100, 160)),
        scenario_input=make_scenario_input(),
    )

    response = client.post("/analytics/v1/insights", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["schemaVersion"] == "analytics_insights_response_v1"
    assert body["calculationVersion"] == "insights_v1"
    assert body["pricePoints"][18]["ma20"] is None
    assert body["pricePoints"][19]["ma20"] == "109.5"
    assert body["pricePoints"][49]["ma50"] == "124.5"
    assert body["technicalSummary"]["signal"] == "ABOVE_BOTH"
    assert [item["range"] for item in body["rangeStats"]] == ["3M", "1Y", "3Y", "MAX"]
    assert body["rangeStats"][-1]["periodReturn"] == "0.59"

    valuation = body["valuation"]
    assert valuation["weightedImpliedPrice"] == "119"
    assert valuation["marketImpliedRevenueGrowth"] == "-0.83333333"
    assert valuation["marketImpliedGrowthGap"] == "-0.93333333"
    assert valuation["sensitivity"]["rows"][2]["impliedPrices"][2] == "122"
    assert valuation["sensitivity"]["rows"][2]["upsideDownside"][2] == "11.2"


def test_insights_rejects_missing_scenario_input(client: TestClient) -> None:
    response = client.post("/analytics/v1/insights", json=make_payload([100, 101]))

    assert response.status_code == 422
    assert response.json()["code"] == "SCENARIO_INPUT_REQUIRED"
