#!/usr/bin/env bash
set -euo pipefail

# The app must already be running. The default is deliberately cheap; use
# `full` only on a machine provisioned for the 10M-row strata. Results are
# JSONL so a killed repetition remains visible rather than disappearing from a
# partially-written JSON array.
mode="${1:-smoke}"
proof_adapter="${PROOF_ADAPTER:-rest}"
run_started="$(date -u +%Y%m%dT%H%M%SZ)"
run_dir="${PROOF_RUN_DIR:-docs/evidence/runs/${run_started}-${mode}}"
out="${OUTPUT:-${run_dir}/scaling.jsonl}"
mkdir -p "$(dirname "$out")" "$run_dir"
if [[ -e "$out" && "${PROOF_APPEND:-0}" != "1" ]]; then
  echo "refusing to overwrite existing evidence: $out (set PROOF_APPEND=1 to append deliberately)" >&2
  exit 2
fi

build_id="uncommitted"
if command -v git >/dev/null 2>&1; then
  build_id="$(git rev-parse --verify HEAD 2>/dev/null || echo uncommitted)"
fi
java_value="${JAVA_HOME:-default}"
java_value="${java_value//\\/\\\\}"
java_value="${java_value//\"/\\\"}"
cat > "${run_dir}/manifest.json" <<EOF
{"startedAt":"${run_started}","mode":"${mode}","requestedAdapter":"${proof_adapter}","build":"${build_id}","baseUrl":"${OMNIFLUX_PROOF_URL:-http://localhost:8080}","java":"${java_value}"}
EOF

# The local compose app signs browser-facing URLs for localhost:9005. A host
# runner can verify those URLs directly; set PROOF_IN_DOCKER=1 only when the
# public storage endpoint is reachable and signed for the proof container too.
# On Docker Desktop, `--network omniflux-network` cannot reach that signed
# localhost endpoint — run the proof container with `--network host` instead
# (verified 2026-09-19: full S2-10k cell completes and verifies).
# The StreamingRunner class lives in the main build's test sources, not the
# production tree: build it with ./mvnw test-compile first.
if [[ "${PROOF_IN_DOCKER:-0}" == "1" ]]; then
  export MSYS_NO_PATHCONV=1
  classes_path="${PWD}/target/test-classes"
  if command -v cygpath >/dev/null 2>&1; then
    classes_path="$(cygpath -w "${classes_path}")"
  fi
  runner=(docker run --rm --network "${PROOF_NETWORK:-omniflux-network}"
    -v "${classes_path}:/proof/classes:ro"
    "${PROOF_JAVA_IMAGE:-eclipse-temurin:25-jre-alpine}" java -cp /proof/classes
    com.omniflux.exchange.proof.StreamingRunner)
  default_url="http://app:8080"
else
  runner=("${JAVA:-java}" -cp "${CLASSPATH:-target/test-classes}"
    com.omniflux.exchange.proof.StreamingRunner)
  default_url="http://localhost:8080"
fi

relation_args=()
if [[ -n "${PROOF_RELATION:-}" ]]; then
  relation_args=(--relation "$PROOF_RELATION")
fi

case "$mode" in
  smoke)
    if [[ "$proof_adapter" == "r2dbc" ]]; then
      cells=(S1-10k)
    else
      cells=(S2-10k)
    fi
    ;;
  csv-10m)
    if [[ "$proof_adapter" == "r2dbc" ]]; then
      cells=(S1-10m)
    else
      cells=(S2-10m)
    fi
    ;;
  xlsx-proof)
    # One-million-row XLSX is the product limit proof. The limit cell is
    # expected to fail with XLSX_ROW_LIMIT at 1,000,001 data rows.
    if [[ "$proof_adapter" == "r2dbc" ]]; then
      cells=(S3-1m S9-xlsx-limit)
    else
      cells=(S4-1m S9-xlsx-limit-rest)
    fi
    ;;
  full)
    cells=(
      S1-10k S1-100k S1-1m S1-10m
      S2-10k S2-100k S2-1m S2-10m
      S3-10k S3-100k S3-1m S4-10k S4-1m
      S5-100 S5-400 S6-100 S7-1m S8-100 S9-xlsx-limit S9-xlsx-limit-rest S10-10k
    )
    ;;
  *)
    echo "usage: $0 [smoke|csv-10m|xlsx-proof|full]" >&2
    exit 2
    ;;
esac

for cell in "${cells[@]}"; do
  case "$cell" in
    S2-*|S4-*|S9-xlsx-limit-rest) base_url="${OMNIFLUX_REST_PROOF_URL:-${OMNIFLUX_PROOF_URL:-$default_url}}" ;;
    *) base_url="${OMNIFLUX_R2DBC_PROOF_URL:-${OMNIFLUX_PROOF_URL:-$default_url}}" ;;
  esac
  if "${runner[@]}" \
    --base-url "$base_url" --cell "$cell" "${relation_args[@]}" | tee -a "$out"; then
    :
  else
    status=${PIPESTATUS[0]}
    printf '{"cellId":"%s","status":"RUNNER_ERROR","exitCode":%d}\n' "$cell" "$status" | tee -a "$out"
    echo "proof cell failed: $cell" >&2
    exit "$status"
  fi
done

cat > "${run_dir}/completed.json" <<EOF
{"completedAt":"$(date -u +%Y%m%dT%H%M%SZ)","resultFile":"${out}","mode":"${mode}"}
EOF
echo "proof matrix results written to $out"
