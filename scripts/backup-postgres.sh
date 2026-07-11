#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${ENV_FILE:-"${ROOT_DIR}/.env.production"}

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

BACKUP_DIR=${BACKUP_DIR:-/var/backups/ai-quant-research}
BACKUP_RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-90}
BACKUP_PASSPHRASE_FILE=${BACKUP_PASSPHRASE_FILE:-"${ROOT_DIR}/.secrets/backup-passphrase"}
if [[ "${BACKUP_PASSPHRASE_FILE}" != /* ]]; then
  BACKUP_PASSPHRASE_FILE="${ROOT_DIR}/${BACKUP_PASSPHRASE_FILE}"
fi
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
destination="${BACKUP_DIR}/postgres-${timestamp}.dump.enc"
temporary="${destination}.partial"

if [[ ! -r "${BACKUP_PASSPHRASE_FILE}" ]]; then
  echo "Backup passphrase file is missing or unreadable" >&2
  exit 1
fi

umask 077
mkdir -p "${BACKUP_DIR}"
trap 'rm -f "${temporary}"' EXIT

docker compose \
  --env-file "${ENV_FILE}" \
  -f "${ROOT_DIR}/docker-compose.yml" \
  -f "${ROOT_DIR}/compose.production.yml" \
  exec -T postgres pg_dump \
    --username "${POSTGRES_USER}" \
    --dbname "${POSTGRES_DB}" \
    --format custom \
    --no-owner \
  | openssl enc -aes-256-cbc -pbkdf2 -salt \
      -pass "file:${BACKUP_PASSPHRASE_FILE}" \
      -out "${temporary}"

mv "${temporary}" "${destination}"
sha256sum "${destination}" > "${destination}.sha256"
git -C "${ROOT_DIR}" rev-parse HEAD > "${destination}.commit"

if [[ -n "${BACKUP_REMOTE:-}" ]]; then
  command -v rclone >/dev/null 2>&1 || {
    echo "BACKUP_REMOTE is set but rclone is unavailable" >&2
    exit 1
  }
  for artifact in "${destination}" "${destination}.sha256" "${destination}.commit"; do
    rclone copyto "${artifact}" "${BACKUP_REMOTE}/$(basename "${artifact}")" --immutable
  done
fi

find "${BACKUP_DIR}" -type f \
  \( -name 'postgres-*.dump.enc' -o -name 'postgres-*.dump.enc.sha256' \
     -o -name 'postgres-*.dump.enc.commit' \) \
  -mtime "+${BACKUP_RETENTION_DAYS}" -delete

echo "Encrypted PostgreSQL backup completed: ${destination}"
