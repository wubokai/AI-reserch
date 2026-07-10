"""Health endpoint and request-correlation tests."""

from __future__ import annotations

import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from fastapi.testclient import TestClient

_GENERATED_REQUEST_ID = re.compile(r"^req_[0-9a-f]{32}$")


def test_health_returns_typed_process_metadata(client: TestClient) -> None:
    response = client.get("/analytics/v1/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "analytics",
        "version": "0.1.0",
    }
    assert _GENERATED_REQUEST_ID.fullmatch(response.headers["X-Request-Id"]) is not None


def test_health_propagates_valid_request_id(client: TestClient) -> None:
    response = client.get(
        "/analytics/v1/health",
        headers={"X-Request-Id": "req_test-123"},
    )

    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "req_test-123"


def test_health_replaces_invalid_request_id(client: TestClient) -> None:
    response = client.get(
        "/analytics/v1/health",
        headers={"X-Request-Id": "invalid request id"},
    )

    request_id = response.headers["X-Request-Id"]
    assert request_id != "invalid request id"
    assert _GENERATED_REQUEST_ID.fullmatch(request_id) is not None


def test_unknown_route_still_returns_request_id(client: TestClient) -> None:
    response = client.get("/analytics/v1/unknown")

    assert response.status_code == 404
    assert _GENERATED_REQUEST_ID.fullmatch(response.headers["X-Request-Id"]) is not None
