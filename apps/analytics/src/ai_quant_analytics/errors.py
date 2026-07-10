"""Safe analytics domain and HTTP error handling."""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from ai_quant_analytics.contracts import AnalyticsErrorResponse

if TYPE_CHECKING:
    from fastapi import FastAPI, Request

_LOGGER = logging.getLogger(__name__)
_MAX_RESEARCH_ID_LENGTH = 100


class AnalyticsDomainError(ValueError):
    """A safe, classified failure caused by semantically invalid input."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int = 422,
        details: dict[str, object] | None = None,
    ) -> None:
        """Initialize a classified error with only response-safe context."""
        super().__init__(message)
        self.code = code
        self.safe_message = message
        self.status_code = status_code
        self.details = {} if details is None else details


def register_exception_handlers(application: FastAPI) -> None:
    """Install stable error envelopes without exposing request bodies or stack traces."""
    application.add_exception_handler(RequestValidationError, _validation_exception)
    application.add_exception_handler(AnalyticsDomainError, _domain_error)
    application.add_exception_handler(Exception, _unexpected_error)


def _validation_exception(
    request: Request,
    exception: Exception,
) -> JSONResponse:
    if not isinstance(exception, RequestValidationError):
        raise TypeError from exception
    issues = sorted(
        (
            {
                "path": ".".join(str(part) for part in error["loc"]),
                "type": str(error["type"]),
            }
            for error in exception.errors()
        ),
        key=lambda item: (str(item["path"]), str(item["type"])),
    )
    return _response(
        request,
        status_code=400,
        code="INVALID_REQUEST",
        message="The analytics request does not match the required schema.",
        details={"issues": issues},
    )


def _domain_error(request: Request, exception: Exception) -> JSONResponse:
    if not isinstance(exception, AnalyticsDomainError):
        raise TypeError from exception
    return _response(
        request,
        status_code=exception.status_code,
        code=exception.code,
        message=exception.safe_message,
        details=exception.details,
    )


def _unexpected_error(request: Request, exception: Exception) -> JSONResponse:
    _LOGGER.exception(
        "Unexpected analytics request failure",
        exc_info=exception,
        extra={"event": "analytics.request.failed"},
    )
    return _response(
        request,
        status_code=500,
        code="INTERNAL_ERROR",
        message="The analytics request could not be completed.",
        details={},
    )


def _response(
    request: Request,
    *,
    status_code: int,
    code: str,
    message: str,
    details: dict[str, object],
) -> JSONResponse:
    request_id = str(getattr(request.state, "request_id", "-"))
    research_id = _safe_research_id(request.headers.get("X-Research-Id"))
    payload = AnalyticsErrorResponse(
        timestamp=datetime.now(tz=UTC).isoformat().replace("+00:00", "Z"),
        status=status_code,
        code=code,
        message=message,
        request_id=request_id,
        research_id=research_id,
        details=details,
    )
    return JSONResponse(
        status_code=status_code,
        content=payload.model_dump(mode="json", by_alias=True),
    )


def _safe_research_id(value: str | None) -> str | None:
    if value is None or not 1 <= len(value) <= _MAX_RESEARCH_ID_LENGTH:
        return None
    return value if all(character.isalnum() or character in "-_:" for character in value) else None
