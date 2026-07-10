#!/usr/bin/env sh
set -eu

pattern='(sk-[A-Za-z0-9_-]{20,}|[0-9]{8,10}:[A-Za-z0-9_-]{30,}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY)'

if command -v rg >/dev/null 2>&1; then
  matches=$(rg -n --hidden \
    -g '!.git/**' \
    -g '!**/node_modules/**' \
    -g '!**/.next/**' \
    -g '!**/target/**' \
    -g '!**/.venv/**' \
    -g '!scripts/check-no-secrets.sh' \
    "$pattern" . || true)
else
  matches=$(git grep -n -E "$pattern" -- ':!scripts/check-no-secrets.sh' || true)
fi

if [ -n "$matches" ]; then
  printf '%s\n' "$matches"
  printf 'Potential secret detected. Remove it before committing.\n' >&2
  exit 1
fi

printf 'No known secret patterns detected in source files.\n'
