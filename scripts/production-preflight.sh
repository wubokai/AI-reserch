#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${1:-"${ROOT_DIR}/.env.production"}

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing production environment file: ${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

required=(
  POSTGRES_PASSWORD SERVICE_JWT_HMAC_SECRET OPENAI_API_KEY
  OPENAI_REPORT_MODEL OPENAI_SAFETY_HMAC_SECRET OPENAI_PRICING_VERSION
  OPENAI_PRICING_EFFECTIVE_FROM OPENAI_INPUT_USD_PER_MILLION_TOKENS
  OPENAI_CACHED_INPUT_USD_PER_MILLION_TOKENS OPENAI_OUTPUT_USD_PER_MILLION_TOKENS
  TIINGO_API_KEY FRED_API_KEY SEC_USER_AGENT SERVICE_JWT_EMAIL
)

missing=0
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required production value: ${name}" >&2
    missing=1
  fi
done
if [[ "${missing}" -ne 0 ]]; then
  exit 1
fi

[[ "${OPENAI_BASE_URL}" == "https://lanyapi.com/v1/" ]] || {
  echo "OPENAI_BASE_URL must be the reviewed LanYi v1 endpoint" >&2
  exit 1
}
[[ "${MARKET_DATA_PROVIDER}" == "tiingo" ]] || exit 1
[[ "${MARKET_DATA_LICENSE_CONFIRMED}" == "true" ]] || exit 1
[[ "${DATA_MODE}" == "REAL" ]] || exit 1
[[ "${SPRING_PROFILES_ACTIVE}" == "production" ]] || exit 1

docker compose \
  --env-file "${ENV_FILE}" \
  -f "${ROOT_DIR}/docker-compose.yml" \
  -f "${ROOT_DIR}/compose.production.yml" \
  config --quiet

echo "Production configuration passed fail-closed preflight."
