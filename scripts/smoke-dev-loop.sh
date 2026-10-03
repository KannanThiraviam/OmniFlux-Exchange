#!/usr/bin/env bash
set -euo pipefail

base_url="${OMNIFLUX_SMOKE_URL:-http://localhost:8080}"
curl --fail --silent --show-error "$base_url/actuator/health" >/dev/null
curl --fail --silent --show-error "$base_url/api/system/resources" >/dev/null
curl --fail --silent --show-error "$base_url/api/meta/relations" >/dev/null
echo "OmniFlux dev-loop smoke passed: $base_url"
