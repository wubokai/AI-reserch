"""Process health contract with no external dependency checks."""

from __future__ import annotations

from typing import ClassVar, Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict

from ai_quant_analytics.config import AnalyticsSettings

router = APIRouter(prefix="/analytics/v1", tags=["system"])


class HealthResponse(BaseModel):
    """Pydantic response returned when the analytics process is alive."""

    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True)

    status: Literal["ok"] = "ok"
    service: str
    version: str


def _settings_from(request: Request) -> AnalyticsSettings:
    settings = request.app.state.settings
    if not isinstance(settings, AnalyticsSettings):
        msg = "Analytics settings are not initialized"
        raise TypeError(msg)
    return settings


@router.get(
    "/health",
    response_model=HealthResponse,
    response_model_exclude_none=True,
    summary="Get analytics process health",
)
def get_health(request: Request) -> HealthResponse:
    """Return process metadata without touching Provider, DB, Redis, or LLM."""
    settings = _settings_from(request)
    return HealthResponse(service=settings.service_name, version=settings.service_version)
