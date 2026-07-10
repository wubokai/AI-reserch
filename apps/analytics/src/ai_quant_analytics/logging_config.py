"""JSON logging configured without request bodies, headers, or secrets."""

from __future__ import annotations

import json
import logging
import sys
from contextvars import ContextVar, Token
from datetime import UTC, datetime
from types import MappingProxyType
from typing import TYPE_CHECKING, Final

if TYPE_CHECKING:
    from ai_quant_analytics.config import LogLevelName

_REQUEST_ID: ContextVar[str] = ContextVar("analytics_request_id", default="-")
_EXTRA_FIELDS: Final = (
    "event",
    "method",
    "path",
    "statusCode",
    "durationMs",
    "environment",
    "serviceVersion",
)
_LOG_LEVELS: Final = MappingProxyType(
    {
        "DEBUG": logging.DEBUG,
        "INFO": logging.INFO,
        "WARNING": logging.WARNING,
        "ERROR": logging.ERROR,
        "CRITICAL": logging.CRITICAL,
    },
)


def set_request_id(request_id: str) -> Token[str]:
    """Set the request-local ID and return a token suitable for reset."""
    return _REQUEST_ID.set(request_id)


def reset_request_id(token: Token[str]) -> None:
    """Restore the previous request-local ID."""
    _REQUEST_ID.reset(token)


class JsonFormatter(logging.Formatter):
    """Render a conservative allowlist of log fields as one JSON object."""

    def format(self, record: logging.LogRecord) -> str:
        """Serialize a log record with an RFC 3339 UTC timestamp."""
        payload: dict[str, object] = {
            "timestamp": datetime.now(tz=UTC)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "requestId": _REQUEST_ID.get(),
        }

        for field_name in _EXTRA_FIELDS:
            value = getattr(record, field_name, None)
            if value is not None:
                payload[field_name] = value

        if record.exc_info is not None:
            payload["exception"] = self.formatException(record.exc_info)

        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def configure_json_logging(log_level: LogLevelName) -> None:
    """Install one JSON handler for application and Uvicorn error logs."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())

    root_logger = logging.getLogger()
    root_logger.handlers.clear()
    root_logger.addHandler(handler)
    root_logger.setLevel(_LOG_LEVELS[log_level])

    for logger_name in ("uvicorn", "uvicorn.error"):
        logger = logging.getLogger(logger_name)
        logger.handlers.clear()
        logger.propagate = True

    logging.getLogger("uvicorn.access").disabled = True
