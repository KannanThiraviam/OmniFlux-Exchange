#!/usr/bin/env bash
set -euo pipefail

base_url="${OMNIFLUX_SMOKE_URL:-http://localhost:8080}"
curl --fail --silent --show-error "$base_url/actuator/health" >/dev/null
curl --fail --silent --show-error "$base_url/api/system/resources" >/dev/null
curl --fail --silent --show-error "$base_url/api/meta/relations" >/dev/null

command -v jq >/dev/null 2>&1 || { echo "jq is required for the export smoke." >&2; exit 1; }
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
curl --fail --silent --show-error -X POST "$base_url/api/data/seed" \
  -H 'Content-Type: application/json' \
  -d '{"relation":"mock_customers","rows":5}' >/dev/null

for format in CSV XLSX; do
  key="smoke-${format}-$(date +%s)-${RANDOM:-0}"
  request='{"relation":"mock_customers","columns":["id","full_name","email_address","country","membership_status"],"filters":[{"column":"id","operator":"LTE","value":5}],"format":"'"$format"'"}'
  if [ "$format" = CSV ]; then
    request="${request%?},\"csvMode\":\"RAW\"}"
  fi
  job="$(curl --fail --silent --show-error -X POST "$base_url/api/exports" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$request")"
  job_id="$(printf '%s' "$job" | jq -r '.id')"
  [ -n "$job_id" ] && [ "$job_id" != null ] || { echo "Export submission did not return a job id: $job" >&2; exit 1; }

  status=QUEUED
  attempts=0
  while [ "$attempts" -lt 90 ]; do
    status_json="$(curl --fail --silent --show-error "$base_url/api/jobs/$job_id")"
    status="$(printf '%s' "$status_json" | jq -r '.status')"
    case "$status" in COMPLETED|FAILED|CANCELLED) break ;; esac
    attempts=$((attempts + 1))
    sleep 1
  done
  [ "$status" = COMPLETED ] || { echo "$format export did not complete: $status_json" >&2; exit 1; }
  rows="$(printf '%s' "$status_json" | jq -r '.rowCount')"
  [ "$rows" = 5 ] || { echo "$format export returned $rows rows, expected 5." >&2; exit 1; }

  download_json="$(curl --fail --silent --show-error "$base_url/api/jobs/$job_id/download")"
  url="$(printf '%s' "$download_json" | jq -r '.url')"
  output="$tmp_dir/$format"
  curl --fail --silent --show-error "$url" -o "$output"
  [ -s "$output" ] || { echo "$format download was empty." >&2; exit 1; }
  if [ "$format" = XLSX ]; then
    command -v unzip >/dev/null 2>&1 || { echo "unzip is required to validate XLSX output." >&2; exit 1; }
    unzip -t "$output" >/dev/null || { echo "XLSX download is not a valid OOXML archive." >&2; exit 1; }
  fi
  echo "$format export smoke passed: $rows rows, $(wc -c < "$output" | tr -d ' ') bytes"
done

echo "OmniFlux full-stack smoke passed: $base_url"
