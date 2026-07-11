#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/postgres-TIMESTAMP.dump.enc" >&2
  exit 2
fi

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${ENV_FILE:-"${ROOT_DIR}/.env.production"}
BACKUP_FILE=$1

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

BACKUP_PASSPHRASE_FILE=${BACKUP_PASSPHRASE_FILE:-"${ROOT_DIR}/.secrets/backup-passphrase"}
OPENSSL_BIN=${OPENSSL_BIN:-openssl}
if [[ "${BACKUP_PASSPHRASE_FILE}" != /* ]]; then
  BACKUP_PASSPHRASE_FILE="${ROOT_DIR}/${BACKUP_PASSPHRASE_FILE}"
fi

[[ -r "${BACKUP_FILE}" ]] || { echo "Backup file is unreadable" >&2; exit 1; }
[[ -r "${BACKUP_FILE}.sha256" ]] || { echo "Checksum sidecar is missing" >&2; exit 1; }
[[ -r "${BACKUP_PASSPHRASE_FILE}" ]] || { echo "Passphrase file is unreadable" >&2; exit 1; }

(cd "$(dirname "${BACKUP_FILE}")" && sha256sum --check "$(basename "${BACKUP_FILE}.sha256")")
temporary=$(mktemp)
trap 'rm -f "${temporary}"' EXIT
"${OPENSSL_BIN}" enc -d -aes-256-cbc -pbkdf2 \
  -pass "file:${BACKUP_PASSPHRASE_FILE}" \
  -in "${BACKUP_FILE}" \
  -out "${temporary}"
docker run --rm -i postgres:17-alpine pg_restore --list < "${temporary}" >/dev/null

echo "Backup checksum, decryption, and pg_restore catalog verification passed."
