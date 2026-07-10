"""Stable decimal formatting and metric construction helpers."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

import math
from datetime import date  # noqa: TC003 - helper signatures are public runtime metadata.
from decimal import ROUND_HALF_EVEN, Decimal, InvalidOperation, localcontext

from ai_quant_analytics.contracts import (
    CALCULATION_VERSION,
    AnalyticsWarning,
    Metric,
    MetricStatus,
)
from ai_quant_analytics.errors import AnalyticsDomainError


def decimal_text(value: float | Decimal, *, places: int) -> str:
    """Serialize a finite numeric value with HALF_EVEN rounding and no exponent."""
    if isinstance(value, float):
        if not math.isfinite(value):
            raise AnalyticsDomainError(
                "NON_FINITE_RESULT",
                "A calculation produced a non-finite result.",
            )
        decimal_value = Decimal(str(value))
    else:
        decimal_value = value
    if not decimal_value.is_finite():
        raise AnalyticsDomainError(
            "NON_FINITE_RESULT",
            "A calculation produced a non-finite result.",
        )
    quantum = Decimal(1).scaleb(-places)
    try:
        with localcontext() as context:
            context.prec = max(50, len(decimal_value.as_tuple().digits) + places + 4)
            rounded = decimal_value.quantize(quantum, rounding=ROUND_HALF_EVEN)
    except InvalidOperation as exception:
        raise AnalyticsDomainError(
            "RESULT_OUT_OF_RANGE",
            "A calculation result exceeds the supported decimal range.",
        ) from exception
    if rounded == 0:
        rounded = abs(rounded)
    return format(rounded, "f")


def warning(code: str, message: str, metric_name: str | None) -> AnalyticsWarning:
    """Create one immutable warning."""
    return AnalyticsWarning(code=code, message=message, metric_name=metric_name)


def available_metric(  # noqa: PLR0913 - mirrors the explicit Metric lineage contract.
    *,
    name: str,
    value: float | Decimal,
    unit: str,
    places: int,
    sample_size: int,
    period_start: date | None,
    period_end: date | None,
    snapshot_ids: tuple[str, ...],
    warnings: tuple[AnalyticsWarning, ...] = (),
) -> Metric:
    """Build a deterministic AVAILABLE metric."""
    return Metric(
        name=name,
        value=decimal_text(value, places=places),
        unit=unit,
        status=MetricStatus.AVAILABLE,
        sample_size=sample_size,
        period_start=period_start,
        period_end=period_end,
        calculation_version=CALCULATION_VERSION,
        input_snapshot_ids=tuple(sorted(set(snapshot_ids))),
        warnings=sort_warnings(warnings),
    )


def unavailable_metric(  # noqa: PLR0913 - mirrors the explicit Metric lineage contract.
    *,
    name: str,
    unit: str,
    status: MetricStatus = MetricStatus.NOT_AVAILABLE,
    sample_size: int,
    period_start: date | None,
    period_end: date | None,
    snapshot_ids: tuple[str, ...],
    warnings: tuple[AnalyticsWarning, ...],
) -> Metric:
    """Build a deterministic metric without a fabricated numeric value."""
    return Metric(
        name=name,
        value=None,
        unit=unit,
        status=status,
        sample_size=sample_size,
        period_start=period_start,
        period_end=period_end,
        calculation_version=CALCULATION_VERSION,
        input_snapshot_ids=tuple(sorted(set(snapshot_ids))),
        warnings=sort_warnings(warnings),
    )


def sort_warnings(values: tuple[AnalyticsWarning, ...]) -> tuple[AnalyticsWarning, ...]:
    """Sort and deduplicate warnings by their complete public identity."""
    keyed = {(item.code, item.metric_name or "", item.message): item for item in values}
    return tuple(keyed[key] for key in sorted(keyed))
