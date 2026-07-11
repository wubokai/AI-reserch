#!/usr/bin/env sh
set -eu

# Flyway owns deterministic seed data. Starting the API applies migrations and
# V5 inserts the five immutable Mock security-master fixtures idempotently.
docker compose up -d api

attempt=1
while [ "$attempt" -le 60 ]; do
  if curl --fail --silent --max-time 3 http://localhost:8080/api/v1/health >/dev/null 2>&1; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done

if [ "$attempt" -gt 60 ]; then
  echo "API did not become healthy while applying seed migrations." >&2
  exit 1
fi

count="$(docker compose exec -T postgres sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align --command "select count(*) from securities where is_demo_data and symbol in ('"'"'MU'"'"','"'"'NVDA'"'"','"'"'RKLB'"'"','"'"'SPY'"'"','"'"'QQQ'"'"')"')"

if [ "${count}" != "5" ]; then
  echo "Expected five deterministic Mock securities, found ${count}." >&2
  exit 1
fi

echo "Deterministic Mock seed is ready: MU, NVDA, RKLB, SPY, QQQ."
