"""Deterministic validation and normalization of analytics inputs."""

# Domain errors intentionally pass stable machine-code string literals.
# ruff: noqa: EM101

from __future__ import annotations

from dataclasses import dataclass
from datetime import date  # noqa: TC003 - dataclass annotations are public runtime metadata.
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING

from ai_quant_analytics.contracts import AnalyticsWarning, PriceBar
from ai_quant_analytics.errors import AnalyticsDomainError

if TYPE_CHECKING:
    from collections.abc import Iterable


@dataclass(frozen=True, slots=True)
class NormalizedPriceBar:
    """A validated price bar with exact decimal values."""

    date: date
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    adjusted_close: Decimal
    volume: Decimal

    def value_identity(self) -> tuple[Decimal, ...]:
        """Return the fields that define whether a duplicate is identical."""
        return (
            self.open,
            self.high,
            self.low,
            self.close,
            self.adjusted_close,
            self.volume,
        )


@dataclass(frozen=True, slots=True)
class CleanPriceSeries:
    """A sorted, deduplicated price series and its complete lineage."""

    bars: tuple[NormalizedPriceBar, ...]
    snapshot_ids: tuple[str, ...]
    warnings: tuple[AnalyticsWarning, ...]


def decimal_from_string(value: str, field: str) -> Decimal:
    """Parse a finite decimal string or raise a classified input error."""
    try:
        result = Decimal(value)
    except InvalidOperation as exception:
        raise AnalyticsDomainError(
            "INVALID_DECIMAL",
            f"{field} is not a valid decimal value.",
        ) from exception
    if not result.is_finite():
        raise AnalyticsDomainError(
            "NON_FINITE_VALUE",
            f"{field} must be finite.",
        )
    return result


def clean_price_series(
    raw_bars: Iterable[PriceBar],
    *,
    period_start: date,
    period_end: date,
    series_name: str,
) -> CleanPriceSeries:
    """Validate, sort, and deterministically deduplicate one price series."""
    if period_start > period_end:
        raise AnalyticsDomainError(
            "INVALID_DATE_RANGE",
            "periodStart must not be after periodEnd.",
        )

    by_date: dict[date, NormalizedPriceBar] = {}
    snapshot_ids: set[str] = set()
    duplicate_count = 0
    for raw_bar in raw_bars:
        if raw_bar.date < period_start or raw_bar.date > period_end:
            raise AnalyticsDomainError(
                "PRICE_OUTSIDE_PERIOD",
                f"{series_name} contains a price bar outside the requested period.",
                details={"date": raw_bar.date.isoformat()},
            )
        bar = _normalize_price_bar(raw_bar, series_name)
        snapshot_ids.add(raw_bar.source_snapshot_id)
        existing = by_date.get(bar.date)
        if existing is None:
            by_date[bar.date] = bar
        elif existing.value_identity() == bar.value_identity():
            duplicate_count += 1
        else:
            raise AnalyticsDomainError(
                "DUPLICATE_DATE_CONFLICT",
                f"{series_name} contains conflicting price bars for one date.",
                details={"date": bar.date.isoformat()},
            )

    warnings: tuple[AnalyticsWarning, ...] = ()
    if duplicate_count:
        warnings = (
            AnalyticsWarning(
                code="DUPLICATE_DATE_REMOVED",
                message=f"Removed {duplicate_count} identical duplicate price bar(s).",
                metric_name=None,
            ),
        )
    return CleanPriceSeries(
        bars=tuple(by_date[key] for key in sorted(by_date)),
        snapshot_ids=tuple(sorted(snapshot_ids)),
        warnings=warnings,
    )


def _normalize_price_bar(raw_bar: PriceBar, series_name: str) -> NormalizedPriceBar:
    values = {
        "open": decimal_from_string(raw_bar.open, f"{series_name}.open"),
        "high": decimal_from_string(raw_bar.high, f"{series_name}.high"),
        "low": decimal_from_string(raw_bar.low, f"{series_name}.low"),
        "close": decimal_from_string(raw_bar.close, f"{series_name}.close"),
        "adjusted_close": decimal_from_string(
            raw_bar.adjusted_close,
            f"{series_name}.adjustedClose",
        ),
        "volume": decimal_from_string(raw_bar.volume, f"{series_name}.volume"),
    }
    prices = (
        values["open"],
        values["high"],
        values["low"],
        values["close"],
        values["adjusted_close"],
    )
    if any(value <= 0 for value in prices):
        raise AnalyticsDomainError(
            "NON_POSITIVE_PRICE",
            f"{series_name} contains a non-positive price.",
            details={"date": raw_bar.date.isoformat()},
        )
    if values["volume"] < 0:
        raise AnalyticsDomainError(
            "NEGATIVE_VOLUME",
            f"{series_name} contains negative volume.",
            details={"date": raw_bar.date.isoformat()},
        )
    if not (
        values["low"] <= min(values["open"], values["close"])
        and max(values["open"], values["close"]) <= values["high"]
    ):
        raise AnalyticsDomainError(
            "INVALID_OHLC",
            f"{series_name} contains an inconsistent OHLC bar.",
            details={"date": raw_bar.date.isoformat()},
        )
    return NormalizedPriceBar(date=raw_bar.date, **values)
