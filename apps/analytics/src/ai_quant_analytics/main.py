"""FastAPI application factory for the deterministic analytics boundary."""

from __future__ import annotations

import logging

from fastapi import FastAPI

from ai_quant_analytics.analysis import router as analysis_router
from ai_quant_analytics.config import AnalyticsSettings
from ai_quant_analytics.errors import register_exception_handlers
from ai_quant_analytics.health import router as health_router
from ai_quant_analytics.logging_config import configure_json_logging
from ai_quant_analytics.middleware import RequestIdMiddleware


def create_app(settings: AnalyticsSettings | None = None) -> FastAPI:
    """Create an app without initializing Provider, database, Redis, or LLM clients."""
    resolved_settings = settings or AnalyticsSettings.from_environment()
    configure_json_logging(resolved_settings.log_level)

    application = FastAPI(
        title="AI Quant Analytics",
        description="Internal deterministic analytics service",
        version=resolved_settings.service_version,
    )
    application.state.settings = resolved_settings
    application.add_middleware(RequestIdMiddleware)
    register_exception_handlers(application)
    application.include_router(health_router)
    application.include_router(analysis_router)

    logging.getLogger(__name__).info(
        "Analytics application configured",
        extra={
            "event": "application.configured",
            "environment": resolved_settings.environment,
            "serviceVersion": resolved_settings.service_version,
        },
    )
    return application


app = create_app()
