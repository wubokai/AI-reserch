"""Typed settings tests."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from ai_quant_analytics.config import AnalyticsSettings


def test_settings_load_only_supported_environment_values() -> None:
    settings = AnalyticsSettings.from_environment(
        {
            "ANALYTICS_SERVICE_NAME": "analytics-test",
            "ANALYTICS_ENVIRONMENT": "test",
            "ANALYTICS_LOG_LEVEL": "WARNING",
            "UNSUPPORTED_SETTING": "must-not-be-read",
        },
    )

    assert settings.service_name == "analytics-test"
    assert settings.environment == "test"
    assert settings.log_level == "WARNING"


def test_settings_reject_invalid_log_level() -> None:
    with pytest.raises(ValidationError, match="log_level"):
        AnalyticsSettings.from_environment({"ANALYTICS_LOG_LEVEL": "VERBOSE"})
