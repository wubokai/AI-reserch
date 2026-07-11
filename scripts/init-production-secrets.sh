#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${1:-"${ROOT_DIR}/.env.production"}
EXAMPLE_FILE="${ROOT_DIR}/.env.production.example"
SECRETS_DIR="${ROOT_DIR}/.secrets"

if [[ -e "${ENV_FILE}" ]]; then
  echo "Refusing to overwrite existing production environment: ${ENV_FILE}" >&2
  exit 1
fi

umask 077
cp "${EXAMPLE_FILE}" "${ENV_FILE}"
mkdir -p "${SECRETS_DIR}"

POSTGRES_SECRET=$(openssl rand -base64 48 | tr -d '\n')
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
LLM_HMAC_SECRET=$(openssl rand -hex 32 | tr -d '\n')

python3 - "${ENV_FILE}" "${POSTGRES_SECRET}" "${JWT_SECRET}" "${LLM_HMAC_SECRET}" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
replacements = {
    "POSTGRES_PASSWORD=": f"POSTGRES_PASSWORD={sys.argv[2]}",
    "SERVICE_JWT_HMAC_SECRET=": f"SERVICE_JWT_HMAC_SECRET={sys.argv[3]}",
    "OPENAI_SAFETY_HMAC_SECRET=": f"OPENAI_SAFETY_HMAC_SECRET={sys.argv[4]}",
}
lines = []
for line in path.read_text(encoding="utf-8").splitlines():
    lines.append(replacements.get(line, line))
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

openssl rand -base64 48 > "${SECRETS_DIR}/backup-passphrase"
chmod 600 "${ENV_FILE}" "${SECRETS_DIR}/backup-passphrase"
unset POSTGRES_SECRET JWT_SECRET LLM_HMAC_SECRET

echo "Created ${ENV_FILE} and a local backup passphrase."
echo "Fill only the remaining API keys and LanYi pricing fields; do not paste them into chat or Git."
