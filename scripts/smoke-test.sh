#!/usr/bin/env bash
set -Eeuo pipefail

WEB_BASE_URL="${WEB_BASE_URL:-http://localhost:3000}"
WEB_HEALTH_URL="${WEB_HEALTH_URL:-$WEB_BASE_URL/api/health}"
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
API_HEALTH_URL="${API_HEALTH_URL:-$API_BASE_URL/api/v1/health}"
ANALYTICS_HEALTH_URL="${ANALYTICS_HEALTH_URL:-http://localhost:8000/analytics/v1/health}"
DEMO_USERNAME="${DEMO_PRINCIPAL_USERNAME:-demo}"
DEMO_PASSWORD="${DEMO_PRINCIPAL_PASSWORD:-change_me_demo_only}"
RESEARCH_POLL_ATTEMPTS="${RESEARCH_POLL_ATTEMPTS:-120}"
RESEARCH_POLL_INTERVAL_SECONDS="${RESEARCH_POLL_INTERVAL_SECONDS:-2}"
PHASE3_CLOSED_LOOP_SMOKE="${PHASE3_CLOSED_LOOP_SMOKE:-false}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-60}"
  local count=1

  while (( count <= attempts )); do
    if curl --fail --silent --show-error --max-time 3 "$url" >/dev/null 2>&1; then
      printf '%s: healthy\n' "$name"
      return 0
    fi
    ((count += 1))
    sleep 2
  done

  printf '%s: health check failed (%s)\n' "$name" "$url" >&2
  return 1
}

api_request() {
  local label="$1"
  local method="$2"
  local path="$3"
  local output_file="$4"
  local headers_file="$5"
  local expected_status="$6"
  shift 6

  local status
  if ! status="$(curl \
    --silent \
    --show-error \
    --max-time 30 \
    --request "$method" \
    --user "$DEMO_USERNAME:$DEMO_PASSWORD" \
    --header 'Accept: application/json' \
    --output "$output_file" \
    --dump-header "$headers_file" \
    --write-out '%{http_code}' \
    "$@" \
    "$API_BASE_URL$path")"; then
    printf '%s: request failed (%s %s)\n' "$label" "$method" "$path" >&2
    return 1
  fi

  if [[ "$status" != "$expected_status" ]]; then
    printf '%s: expected HTTP %s, received %s (%s %s)\n' \
      "$label" "$expected_status" "$status" "$method" "$path" >&2
    if [[ -s "$output_file" ]]; then
      printf '%s\n' '--- response body ---' >&2
      sed -n '1,160p' "$output_file" >&2
    fi
    return 1
  fi
}

web_request() {
  local label="$1"
  local path="$2"
  local output_file="$3"
  local headers_file="$4"
  local status

  if ! status="$(curl \
    --silent \
    --show-error \
    --max-time 30 \
    --header 'Accept: application/json' \
    --output "$output_file" \
    --dump-header "$headers_file" \
    --write-out '%{http_code}' \
    "$WEB_BASE_URL$path")"; then
    printf '%s: Web BFF request failed (%s)\n' "$label" "$path" >&2
    return 1
  fi

  if [[ "$status" != "200" ]]; then
    printf '%s: expected HTTP 200, received %s (%s)\n' "$label" "$status" "$path" >&2
    if [[ -s "$output_file" ]]; then
      sed -n '1,160p' "$output_file" >&2
    fi
    return 1
  fi
}

wait_for_url "Web" "$WEB_HEALTH_URL"
wait_for_url "API" "$API_HEALTH_URL"
wait_for_url "Analytics" "$ANALYTICS_HEALTH_URL"

web_request \
  "Read aggregate system health" \
  "/api/system-health" \
  "$TMP_DIR/system-health.json" \
  "$TMP_DIR/system-health.headers"

python3 - "$TMP_DIR/system-health.json" <<'PY'
import json
import sys


with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)

assert payload.get("status") == "UP", payload
services = payload.get("services")
assert isinstance(services, dict), payload
assert set(services) >= {"web", "api", "analytics"}, services
for service_name in ("web", "api", "analytics"):
    service = services[service_name]
    assert isinstance(service, dict), service
    assert service.get("status") == "UP", f"{service_name} is not UP: {service}"
    if "dataMode" in service:
        assert service["dataMode"] == "MOCK", f"{service_name} is not in MOCK mode: {service}"
PY

if [[ "$PHASE3_CLOSED_LOOP_SMOKE" != "true" ]]; then
  printf 'All application and aggregate health checks passed.\n'
  exit 0
fi

printf 'Service and aggregate health checks passed; starting Phase 3 closed-loop smoke test.\n'

# A fixed request and idempotency key make repeated local runs safe. The second
# request explicitly proves that the API replays the original command.
CREATE_PAYLOAD='{"query":"Analyze NVIDIA durable growth drivers, valuation, catalysts, and material downside risks","symbol":"NVDA","locale":"en-US","benchmark":"SPY","period":"5y","reportDepth":"STANDARD","includeTechnicalAnalysis":true,"includeFundamentalAnalysis":true,"includeMacroAnalysis":true}'
IDEMPOTENCY_KEY="phase3-compose-smoke-v1"

api_request \
  "Create research" \
  POST \
  "/api/v1/research" \
  "$TMP_DIR/create.json" \
  "$TMP_DIR/create.headers" \
  202 \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data-binary "$CREATE_PAYLOAD"

api_request \
  "Replay research creation" \
  POST \
  "/api/v1/research" \
  "$TMP_DIR/replay.json" \
  "$TMP_DIR/replay.headers" \
  202 \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEMPOTENCY_KEY" \
  --data-binary "$CREATE_PAYLOAD"

RESEARCH_ID="$(python3 - \
  "$TMP_DIR/create.json" \
  "$TMP_DIR/replay.json" \
  "$TMP_DIR/replay.headers" <<'PY'
import json
import re
import sys


def load_json(path: str) -> dict:
    with open(path, encoding="utf-8") as handle:
        value = json.load(handle)
    assert isinstance(value, dict), f"expected JSON object in {path}"
    return value


def load_headers(path: str) -> dict[str, str]:
    result: dict[str, str] = {}
    with open(path, encoding="iso-8859-1") as handle:
        for line in handle:
            if ":" in line:
                name, value = line.split(":", 1)
                result[name.strip().lower()] = value.strip()
    return result


created = load_json(sys.argv[1])
replayed = load_json(sys.argv[2])
headers = load_headers(sys.argv[3])

research_id = created.get("researchId")
assert isinstance(research_id, str), "create response has no researchId"
assert re.fullmatch(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    research_id,
    re.IGNORECASE,
), f"invalid researchId: {research_id!r}"
assert created.get("dataMode") == "MOCK", created
assert replayed.get("researchId") == research_id, "idempotency replay created a different job"
assert replayed.get("dataMode") == "MOCK", replayed
assert headers.get("idempotency-replayed", "").lower() == "true", headers
print(research_id)
PY
)"

printf 'Research accepted and idempotency replay verified: %s\n' "$RESEARCH_ID"

STATUS=""
for (( attempt = 1; attempt <= RESEARCH_POLL_ATTEMPTS; attempt += 1 )); do
  api_request \
    "Poll research status" \
    GET \
    "/api/v1/research/$RESEARCH_ID/status" \
    "$TMP_DIR/status.json" \
    "$TMP_DIR/status.headers" \
    200

  STATUS="$(python3 - "$TMP_DIR/status.json" "$RESEARCH_ID" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)

assert payload.get("researchId") == sys.argv[2], payload
assert payload.get("dataMode") == "MOCK", payload
progress = payload.get("progress")
assert isinstance(progress, int) and 0 <= progress <= 100, payload
steps = payload.get("steps")
assert isinstance(steps, list) and steps, payload
print(payload.get("status", ""))
PY
)"

  printf 'Research poll %d/%d: %s\n' "$attempt" "$RESEARCH_POLL_ATTEMPTS" "$STATUS"
  case "$STATUS" in
    COMPLETED)
      break
      ;;
    PARTIALLY_COMPLETED | FAILED | CANCELLED)
      printf 'Research reached an unexpected terminal state: %s\n' "$STATUS" >&2
      sed -n '1,200p' "$TMP_DIR/status.json" >&2
      exit 1
      ;;
  esac

  sleep "$RESEARCH_POLL_INTERVAL_SECONDS"
done

if [[ "$STATUS" != "COMPLETED" ]]; then
  printf 'Research did not complete after %s polls.\n' "$RESEARCH_POLL_ATTEMPTS" >&2
  sed -n '1,200p' "$TMP_DIR/status.json" >&2
  exit 1
fi

python3 - "$TMP_DIR/status.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)

assert payload["progress"] == 100, payload
assert payload["completedSteps"] == payload["totalSteps"], payload
assert payload.get("error") is None, payload
steps = payload["steps"]
assert all(step.get("status") in {"SUCCEEDED", "SKIPPED"} for step in steps), steps
assert any(step.get("step") == "GENERATE_REPORT" and step.get("status") == "SUCCEEDED" for step in steps), steps
assert any(step.get("step") == "VALIDATE_REPORT" and step.get("status") == "SUCCEEDED" for step in steps), steps
PY

api_request \
  "Read evidence" \
  GET \
  "/api/v1/research/$RESEARCH_ID/evidence?size=100" \
  "$TMP_DIR/evidence.json" \
  "$TMP_DIR/evidence.headers" \
  200

api_request \
  "List report versions" \
  GET \
  "/api/v1/research/$RESEARCH_ID/reports?size=100" \
  "$TMP_DIR/reports.json" \
  "$TMP_DIR/reports.headers" \
  200

REPORT_VERSION="$(python3 - \
  "$TMP_DIR/evidence.json" \
  "$TMP_DIR/reports.json" \
  "$RESEARCH_ID" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    evidence = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    reports = json.load(handle)

assert evidence.get("dataMode") == "MOCK", evidence
items = evidence.get("items")
assert isinstance(items, list) and items, "completed report has no evidence"
assert evidence.get("page", {}).get("totalElements") == len(items), evidence.get("page")
assert all(item.get("isDemoData") is True for item in items), "non-demo evidence leaked into MOCK run"
assert all(re.fullmatch(r"[0-9a-f]{64}", item.get("rawDataHash", "")) for item in items), "invalid evidence hash"
assert any(item.get("relatedClaimIds") for item in items), "evidence is not linked to claims"

versions = reports.get("items")
assert isinstance(versions, list) and versions, "completed research has no published report"
assert reports.get("page", {}).get("totalElements") == len(versions), reports.get("page")
matching = [item for item in versions if item.get("researchId") == sys.argv[3]]
assert matching, versions
latest = max(matching, key=lambda item: item.get("version", 0))
assert latest.get("validationStatus") == "PASSED", latest
assert latest.get("dataMode") == "MOCK", latest
assert re.fullmatch(r"[0-9a-f]{64}", latest.get("contentHash", "")), latest
print(latest["version"])
PY
)"

api_request \
  "Read report" \
  GET \
  "/api/v1/research/$RESEARCH_ID/reports/$REPORT_VERSION" \
  "$TMP_DIR/report.json" \
  "$TMP_DIR/report.headers" \
  200

api_request \
  "Read research history" \
  GET \
  "/api/v1/research?symbol=NVDA&size=100&sort=createdAt,desc" \
  "$TMP_DIR/history.json" \
  "$TMP_DIR/history.headers" \
  200

python3 - \
  "$TMP_DIR/evidence.json" \
  "$TMP_DIR/report.json" \
  "$TMP_DIR/report.headers" \
  "$TMP_DIR/history.json" \
  "$RESEARCH_ID" \
  "$REPORT_VERSION" <<'PY'
import json
import re
import sys


def load_json(path: str) -> dict:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def load_headers(path: str) -> dict[str, str]:
    headers: dict[str, str] = {}
    with open(path, encoding="iso-8859-1") as handle:
        for line in handle:
            if ":" in line:
                name, value = line.split(":", 1)
                headers[name.strip().lower()] = value.strip()
    return headers


evidence = load_json(sys.argv[1])
envelope = load_json(sys.argv[2])
headers = load_headers(sys.argv[3])
history = load_json(sys.argv[4])
research_id = sys.argv[5]
version = int(sys.argv[6])

assert envelope.get("researchId") == research_id, envelope
assert envelope.get("version") == version, envelope
assert envelope.get("validationStatus") == "PASSED", envelope
content_hash = envelope.get("contentHash", "")
assert re.fullmatch(r"[0-9a-f]{64}", content_hash), envelope
assert headers.get("etag", "").strip('"') == content_hash, headers

report = envelope.get("report")
assert isinstance(report, dict), envelope
assert report.get("schemaVersion") == "research_report_v1", report
assert report.get("symbol") == "NVDA", report
assert report.get("dataMode") == "MOCK", report
assert "DEMO DATA — NOT REAL MARKET DATA" in report.get("disclaimer", ""), report
assert len(report.get("sections", [])) >= 4, report
scenarios = report.get("scenarioAnalysis", {}).get("scenarios", [])
assert {item.get("name") for item in scenarios} == {"BULL", "BASE", "BEAR"}, scenarios

claims: list[dict] = []
for section in report.get("sections", []):
    claims.extend(section.get("claims", []))
for name in ("bullCase", "bearCase", "catalysts", "conclusion"):
    claims.extend(report.get(name, []))
claims.extend(item.get("claim", {}) for item in report.get("risks", []))
claims.extend(report.get("scenarioAnalysis", {}).get("summaryClaims", []))
material_claims = [claim for claim in claims if claim.get("materiality") == "MATERIAL"]
assert material_claims, "report has no material claims"

evidence_ids = {item.get("evidenceId") for item in evidence["items"]}
for claim in material_claims:
    linked = claim.get("evidenceIds")
    assert isinstance(linked, list) and linked, f"unsupported material claim: {claim}"
    assert set(linked) <= evidence_ids, f"claim references unknown evidence: {claim}"

history_item = next(
    (item for item in history.get("items", []) if item.get("researchId") == research_id),
    None,
)
assert history_item is not None, "created job is missing from research history"
assert history_item.get("status") == "COMPLETED", history_item
assert history_item.get("latestReportVersion") == version, history_item
assert history_item.get("dataMode") == "MOCK", history_item
PY

printf 'Evidence, validated report v%s, and research history verified.\n' "$REPORT_VERSION"

download_export() {
  local format="$1"
  local extension="$2"
  local media_type="$3"
  local body_file="$TMP_DIR/report.$extension"
  local headers_file="$TMP_DIR/report.$extension.headers"

  api_request \
    "Download $format export" \
    GET \
    "/api/v1/research/$RESEARCH_ID/export?format=$format&reportVersion=$REPORT_VERSION" \
    "$body_file" \
    "$headers_file" \
    200

  python3 - \
    "$body_file" \
    "$headers_file" \
    "$media_type" \
    "$extension" \
    "$REPORT_VERSION" <<'PY'
import hashlib
import re
import sys


def load_headers(path: str) -> dict[str, str]:
    headers: dict[str, str] = {}
    with open(path, encoding="iso-8859-1") as handle:
        for line in handle:
            if ":" in line:
                name, value = line.split(":", 1)
                headers[name.strip().lower()] = value.strip()
    return headers


body_path, headers_path, media_type, extension, version = sys.argv[1:]
with open(body_path, "rb") as handle:
    content = handle.read()
headers = load_headers(headers_path)
digest = hashlib.sha256(content).hexdigest()

assert content, "export is empty"
assert headers.get("content-type", "").lower().startswith(media_type.lower()), headers
assert headers.get("x-report-version") == version, headers
assert headers.get("x-data-mode") == "MOCK", headers
assert headers.get("x-content-sha256") == digest, headers
assert headers.get("etag", "").strip('"') == digest, headers
disposition = headers.get("content-disposition", "")
assert re.search(rf'filename="NVDA-research-v{re.escape(version)}\.{re.escape(extension)}"', disposition), disposition

watermark = "DEMO DATA — NOT REAL MARKET DATA"
if extension == "md":
    text = content.decode("utf-8")
    assert text.startswith("# "), "Markdown export has no title"
    assert watermark in text, "Markdown export has no demo watermark"
    assert "## Sources" in text and "## Disclaimer" in text, "Markdown export is incomplete"
elif extension == "html":
    text = content.decode("utf-8")
    lowered = text.lower()
    assert lowered.startswith("<!doctype html>"), "HTML export is not a standalone document"
    assert watermark in text, "HTML export has no demo watermark"
    assert "<script" not in lowered and "javascript:" not in lowered, "HTML export contains active content"
    assert not re.search(r'''(?:src|href)\s*=\s*["'](?:https?:)?//''', lowered), "HTML export loads a remote resource"
elif extension == "pdf":
    assert len(content) > 1_000, "PDF export is unexpectedly small"
    assert content.startswith(b"%PDF-"), "PDF export has no PDF signature"
    assert content.rstrip().endswith(b"%%EOF"), "PDF export is truncated"
    assert b"/JavaScript" not in content, "PDF export contains JavaScript"
else:
    raise AssertionError(f"unsupported test extension: {extension}")
PY
}

download_export "MARKDOWN" "md" "text/markdown"
download_export "HTML" "html" "text/html"
download_export "PDF" "pdf" "application/pdf"

# A cached repeat must return byte-for-byte identical output and the same hash.
api_request \
  "Repeat PDF export" \
  GET \
  "/api/v1/research/$RESEARCH_ID/export?format=PDF&reportVersion=$REPORT_VERSION" \
  "$TMP_DIR/report-repeat.pdf" \
  "$TMP_DIR/report-repeat.pdf.headers" \
  200

if ! cmp --silent "$TMP_DIR/report.pdf" "$TMP_DIR/report-repeat.pdf"; then
  printf 'Repeated PDF export was not byte-for-byte deterministic.\n' >&2
  exit 1
fi

python3 - \
  "$TMP_DIR/report.pdf.headers" \
  "$TMP_DIR/report-repeat.pdf.headers" <<'PY'
import sys


def content_hash(path: str) -> str:
    with open(path, encoding="iso-8859-1") as handle:
        for line in handle:
            if line.lower().startswith("x-content-sha256:"):
                return line.split(":", 1)[1].strip()
    raise AssertionError(f"X-Content-SHA256 is missing from {path}")


assert content_hash(sys.argv[1]) == content_hash(sys.argv[2]), "cached PDF hash changed"
PY

web_request \
  "Read history through Web BFF" \
  "/api/research?symbol=NVDA&size=100&sort=createdAt,desc" \
  "$TMP_DIR/web-history.json" \
  "$TMP_DIR/web-history.headers"

web_request \
  "Download PDF through Web BFF" \
  "/api/research/$RESEARCH_ID/export?format=PDF&reportVersion=$REPORT_VERSION" \
  "$TMP_DIR/web-report.pdf" \
  "$TMP_DIR/web-report.pdf.headers"

python3 - \
  "$TMP_DIR/history.json" \
  "$TMP_DIR/web-history.json" \
  "$TMP_DIR/report.pdf" \
  "$TMP_DIR/web-report.pdf" \
  "$TMP_DIR/report.pdf.headers" \
  "$TMP_DIR/web-report.pdf.headers" <<'PY'
import json
import sys


def load_headers(path: str) -> dict[str, str]:
    headers: dict[str, str] = {}
    with open(path, encoding="iso-8859-1") as handle:
        for line in handle:
            if ":" in line:
                name, value = line.split(":", 1)
                headers[name.strip().lower()] = value.strip()
    return headers


with open(sys.argv[1], encoding="utf-8") as handle:
    api_history = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    web_history = json.load(handle)
assert web_history == api_history, "Web BFF changed the research history response"

with open(sys.argv[3], "rb") as handle:
    api_pdf = handle.read()
with open(sys.argv[4], "rb") as handle:
    web_pdf = handle.read()
assert web_pdf == api_pdf, "Web BFF changed the PDF export bytes"

api_headers = load_headers(sys.argv[5])
web_headers = load_headers(sys.argv[6])
for name in (
    "content-disposition",
    "content-type",
    "etag",
    "x-content-sha256",
    "x-data-mode",
    "x-report-version",
):
    assert web_headers.get(name) == api_headers.get(name), f"Web BFF changed {name}"
assert web_headers.get("x-request-id"), "Web BFF did not return a request id"
PY

printf 'Markdown, HTML, and deterministic PDF exports verified.\n'
printf 'Web BFF history and PDF proxy verified.\n'
printf 'Phase 3 Compose closed-loop smoke test passed.\n'
