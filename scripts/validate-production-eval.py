#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "evals" / "production-matrix.json"


def main() -> None:
    payload = json.loads(MATRIX.read_text(encoding="utf-8"))
    assert payload["schemaVersion"] == "production_eval_matrix_v1"
    assert payload["symbols"] == ["MU", "NVDA", "RKLB"]
    assert payload["locales"] == ["en-US", "zh-CN"]
    scenarios = payload["scenarios"]
    assert len(scenarios) == 5
    assert len({item["id"] for item in scenarios}) == 5
    assert {item["period"] for item in scenarios} == {"1y", "3y", "5y"}
    assert {item["reportDepth"] for item in scenarios} == {
        "QUICK",
        "STANDARD",
        "DEEP",
    }
    for scenario in scenarios:
        assert set(scenario["question"]) == set(payload["locales"])
        assert all(scenario["question"][locale].strip() for locale in payload["locales"])
    expanded = len(payload["symbols"]) * len(payload["locales"]) * len(scenarios)
    assert expanded == payload["thresholds"]["expandedCases"] == 30
    assert payload["thresholds"]["schemaValidRate"] == 1.0
    assert payload["thresholds"]["materialClaimEvidenceRate"] == 1.0
    assert payload["thresholds"]["numericReferenceExactRate"] == 1.0
    assert payload["thresholds"]["unsupportedMaterialClaims"] == 0
    print(f"Production evaluation matrix is valid: {expanded} cases")


if __name__ == "__main__":
    main()
