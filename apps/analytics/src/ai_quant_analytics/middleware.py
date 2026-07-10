"""Request correlation middleware with a validated, bounded identifier."""

from __future__ import annotations

import logging
import re
from time import perf_counter
from typing import TYPE_CHECKING, Final
from uuid import uuid4

from starlette.datastructures import Headers, MutableHeaders

from ai_quant_analytics.logging_config import reset_request_id, set_request_id

if TYPE_CHECKING:
    from starlette.types import ASGIApp, Message, Receive, Scope, Send

REQUEST_ID_HEADER: Final = "X-Request-Id"
_REQUEST_ID_PATTERN: Final = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_LOGGER = logging.getLogger("ai_quant_analytics.http")


def _request_id_from(value: str | None) -> str:
    if value is not None and _REQUEST_ID_PATTERN.fullmatch(value) is not None:
        return value
    return f"req_{uuid4().hex}"


class RequestIdMiddleware:
    """Attach a request ID to logs and every completed HTTP response."""

    def __init__(self, app: ASGIApp) -> None:
        """Wrap an ASGI application."""
        self._app = app

    async def __call__(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
    ) -> None:
        """Process HTTP requests while passing through non-HTTP scopes."""
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return

        headers = Headers(scope=scope)
        request_id = _request_id_from(headers.get(REQUEST_ID_HEADER))
        context_token = set_request_id(request_id)
        method = str(scope.get("method", ""))
        path = str(scope.get("path", ""))
        status_code = 500
        started_at = perf_counter()

        async def send_with_request_id(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
                response_headers = MutableHeaders(scope=message)
                response_headers[REQUEST_ID_HEADER] = request_id
            await send(message)

        _LOGGER.info(
            "HTTP request started",
            extra={"event": "http.request.started", "method": method, "path": path},
        )
        try:
            await self._app(scope, receive, send_with_request_id)
        finally:
            duration_ms = round((perf_counter() - started_at) * 1000, 3)
            _LOGGER.info(
                "HTTP request completed",
                extra={
                    "event": "http.request.completed",
                    "method": method,
                    "path": path,
                    "statusCode": status_code,
                    "durationMs": duration_ms,
                },
            )
            reset_request_id(context_token)
