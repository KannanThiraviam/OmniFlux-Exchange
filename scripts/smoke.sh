#!/usr/bin/env sh
set -eu

base_url="${OMNIFLUX_SMOKE_URL:-http://localhost:8080}"
base_url="${base_url%/}"
command -v curl >/dev/null 2>&1 || { echo "curl is required for the local smoke." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "Python 3 is required to read API responses." >&2; exit 1; }

json_field() {
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"
}

curl --fail --silent --show-error "$base_url/actuator/health" >/dev/null
curl --fail --silent --show-error -X POST "$base_url/api/data/seed" \
  -H 'Content-Type: application/json' -d '{"relation":"mock_customers","rows":3}' >/dev/null

job="$(curl --fail --silent --show-error -X POST "$base_url/api/exports" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: local-smoke-$(date +%s)-$$" \
  -d '{"relation":"mock_customers","columns":["id","full_name","email_address","country","membership_status"],"filters":[{"column":"id","operator":"LTE","value":3}],"format":"CSV","csvMode":"SPREADSHEET_SAFE"}')"
job_id="$(printf '%s' "$job" | json_field id)"
[ -n "$job_id" ] || { echo "Export submission returned no job id: $job" >&2; exit 1; }

attempt=0
status=QUEUED
while [ "$attempt" -lt 90 ]; do
  status_json="$(curl --fail --silent --show-error "$base_url/api/jobs/$job_id")"
  status="$(printf '%s' "$status_json" | json_field status)"
  case "$status" in COMPLETED|FAILED|CANCELLED) break ;; esac
  attempt=$((attempt + 1))
  sleep 1
done
[ "$status" = COMPLETED ] || { echo "CSV export smoke failed: $status_json" >&2; exit 1; }
rows="$(printf '%s' "$status_json" | json_field rowCount)"
[ "$rows" -eq 3 ] || { echo "CSV export returned $rows rows; expected the 3 rows with id <= 3." >&2; exit 1; }

download="$(curl --fail --silent --show-error "$base_url/api/jobs/$job_id/download")"
url="$(printf '%s' "$download" | json_field url)"
[ -n "$url" ] || { echo "Download URL response was empty: $download" >&2; exit 1; }
csv="$(curl --fail --silent --show-error "$url")"
[ -n "$csv" ] || { echo "CSV download was empty." >&2; exit 1; }

echo "CSV export smoke passed: $rows rows, $(printf '%s' "$csv" | wc -c | tr -d ' ') bytes."
