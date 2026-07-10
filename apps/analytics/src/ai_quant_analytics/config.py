"""Typed, side-effect-free application settings."""

from __future__ import annotations

import os
from typing import TYPE_CHECKING, ClassVar, Literal, Self

from pydantic import BaseModel, ConfigDict, Field

from ai_quant_analytics import __version__

if TYPE_CHECKING:
    from collections.abc import Mapping

type EnvironmentName = Literal["local", "test", "development", "staging", "production"]
type LogLevelName = Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]


class AnalyticsSettings(BaseModel):
    """Validated process settings loaded from a narrow environment allowlist."""

    model_config: ClassVar[ConfigDict] = ConfigDict(extra="forbid", frozen=True)

    service_name: str = Field(
        default="analytics",
        min_length=1,
        max_length=64,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$",
    )
    service_version: str = __version__
    environment: EnvironmentName = "local"
    log_level: LogLevelName = "INFO"

    @classmethod
    def from_environment(cls, environ: Mapping[str, str] | None = None) -> Self:
        """Build settings from supported variables without mutating process state."""
        source = os.environ if environ is None else environ
        values: dict[str, str] = {}
        variable_map = {
            "ANALYTICS_SERVICE_NAME": "service_name",
            "ANALYTICS_ENVIRONMENT": "environment",
            "ANALYTICS_LOG_LEVEL": "log_level",
        }

        for variable, field_name in variable_map.items():
            value = source.get(variable)
            if value is not None:
                values[field_name] = value

        return cls.model_validate(values)
