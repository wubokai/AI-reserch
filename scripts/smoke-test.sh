#!/usr/bin/env sh
set -eu

wait_for_url() {
  name="$1"
  url="$2"
  attempts="${3:-60}"
  count=1

  while [ "$count" -le "$attempts" ]; do
    if curl --fail --silent --show-error --max-time 3 "$url" >/dev/null 2>&1; then
      printf '%s: healthy\n' "$name"
      return 0
    fi
    count=$((count + 1))
    sleep 2
  done

  printf '%s: health check failed (%s)\n' "$name" "$url" >&2
  return 1
}

wait_for_url "Web" "${WEB_HEALTH_URL:-http://localhost:3000/api/health}"
wait_for_url "API" "${API_HEALTH_URL:-http://localhost:8080/api/v1/health}"
wait_for_url "Analytics" "${ANALYTICS_HEALTH_URL:-http://localhost:8000/analytics/v1/health}"

printf 'All application health checks passed.\n'
