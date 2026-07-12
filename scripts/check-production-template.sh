#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TEMP_ENV=$(mktemp)
trap 'rm -f "${TEMP_ENV}"' EXIT

python3 - "${ROOT_DIR}/.env.production.example" "${TEMP_ENV}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
values = {
    "POSTGRES_PASSWORD": "ci-postgres-password-32-characters",
    "SERVICE_JWT_HMAC_SECRET": "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    "OPENAI_API_KEY": "ci-lanyi-placeholder-key-never-sent",
    "OPENAI_SAFETY_HMAC_SECRET": "0" * 64,
    "OPENAI_PRICING_VERSION": "ci-pricing-v1",
    "OPENAI_PRICING_EFFECTIVE_FROM": "2026-07-11",
    "OPENAI_INPUT_USD_PER_MILLION_TOKENS": "1.00",
    "OPENAI_CACHED_INPUT_USD_PER_MILLION_TOKENS": "0.10",
    "OPENAI_OUTPUT_USD_PER_MILLION_TOKENS": "5.00",
    "TIINGO_API_KEY": "ci-tiingo-placeholder-token",
    "FRED_API_KEY": "a" * 32,
}
lines = []
for line in source.splitlines():
    name = line.split("=", 1)[0]
    lines.append(f"{name}={values[name]}" if name in values else line)
Path(sys.argv[2]).write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

"${ROOT_DIR}/scripts/production-preflight.sh" "${TEMP_ENV}"

python3 - "${TEMP_ENV}" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
zero_cost = {
    "OPENAI_API_KEY": "",
    "OPENAI_REPORT_MODEL": "",
    "OPENAI_VALIDATION_MODEL": "",
    "OPENAI_PRICING_VERSION": "",
    "OPENAI_PRICING_EFFECTIVE_FROM": "",
    "OPENAI_INPUT_USD_PER_MILLION_TOKENS": "",
    "OPENAI_CACHED_INPUT_USD_PER_MILLION_TOKENS": "",
    "OPENAI_OUTPUT_USD_PER_MILLION_TOKENS": "",
    "RESEARCH_MAX_LLM_COST_USD": "0",
    "RESEARCH_MAX_LLM_CALLS": "0",
}
lines = []
for line in path.read_text(encoding="utf-8").splitlines():
    name = line.split("=", 1)[0]
    lines.append(f"{name}={zero_cost[name]}" if name in zero_cost else line)
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

"${ROOT_DIR}/scripts/production-preflight.sh" "${TEMP_ENV}"
