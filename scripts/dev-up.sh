#!/usr/bin/env sh
set -eu

docker compose up --build -d
"$(dirname "$0")/smoke-test.sh"
