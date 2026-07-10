"""JSON log formatter tests."""

from __future__ import annotations

import json
import logging

from ai_quant_analytics.logging_config import JsonFormatter


def test_json_formatter_emits_expected_safe_fields() -> None:
    record = logging.makeLogRecord(
        {
            "name": "analytics.test",
            "levelno": logging.INFO,
            "levelname": "INFO",
            "msg": "healthy",
            "event": "health.checked",
            "authorization": "must-not-be-logged",
        },
    )

    payload = json.loads(JsonFormatter().format(record))

    assert payload["level"] == "INFO"
    assert payload["message"] == "healthy"
    assert payload["event"] == "health.checked"
    assert payload["requestId"] == "-"
    assert "authorization" not in payload
