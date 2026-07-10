"""Shared test fixtures for the analytics service."""

from __future__ import annotations

from typing import TYPE_CHECKING

import pytest
from fastapi.testclient import TestClient

from ai_quant_analytics.config import AnalyticsSettings
from ai_quant_analytics.main import create_app

if TYPE_CHECKING:
    from collections.abc import Iterator


@pytest.fixture
def client() -> Iterator[TestClient]:
    settings = AnalyticsSettings(environment="test", log_level="CRITICAL")
    with TestClient(create_app(settings)) as test_client:
        yield test_client
