#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."

command -v docker >/dev/null 2>&1 || { echo "Docker CLI is required." >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "OpenSSL is required to create the local JWT." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Start Docker Engine and retry." >&2; exit 1; }

# Compose gives exported process variables precedence over .env. Use this
# checkout's credentials even if similarly named shell variables are set.
unset OMNIFLUX_POSTGRES_PORT OMNIFLUX_S3_PORT OMNIFLUX_SEAWEEDFS_MASTER_PORT
unset OMNIFLUX_POSTGREST_PORT OMNIFLUX_POSTGREST_ADMIN_PORT OMNIFLUX_APP_PORT
unset OMNIFLUX_STORAGE_PUBLIC_ENDPOINT OMNIFLUX_SOURCE_DEFAULT_ADAPTER
unset OMNIFLUX_DB_PASSWORD OMNIFLUX_S3_ACCESS_KEY OMNIFLUX_S3_SECRET_KEY
unset OMNIFLUX_MINIO_USER OMNIFLUX_MINIO_PASSWORD OMNIFLUX_MINIO_PORT
unset OMNIFLUX_PGRST_JWT_SECRET OMNIFLUX_DATA_API_TOKEN DATA_API_TOKEN

if [ ! -f .env ]; then
  umask 077
  secret="$(openssl rand -hex 48)"
  db_password="$(openssl rand -hex 24)"
  storage_password="$(openssl rand -hex 24)"
  header="$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
  payload="$(printf '%s' '{"role":"omniflux"}' | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
  signature="$(printf '%s' "$header.$payload" | openssl dgst -sha256 -mac HMAC -macopt "key:$secret" -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
  {
    printf '%s\n' 'OMNIFLUX_POSTGRES_PORT=5435'
    printf '%s\n' 'OMNIFLUX_S3_PORT=9005'
    printf '%s\n' 'OMNIFLUX_SEAWEEDFS_MASTER_PORT=9006'
    printf '%s\n' 'OMNIFLUX_POSTGREST_PORT=3001'
    printf '%s\n' 'OMNIFLUX_APP_PORT=8080'
    printf '%s\n' 'OMNIFLUX_POSTGREST_ADMIN_PORT=3002'
    printf '%s\n' 'OMNIFLUX_STORAGE_PUBLIC_ENDPOINT=http://localhost:9005'
    printf '%s\n' 'OMNIFLUX_SOURCE_DEFAULT_ADAPTER=rest'
    printf 'OMNIFLUX_DB_PASSWORD=%s\n' "$db_password"
    printf '%s\n' 'OMNIFLUX_S3_ACCESS_KEY=omniflux'
    printf 'OMNIFLUX_S3_SECRET_KEY=%s\n' "$storage_password"
    printf 'OMNIFLUX_PGRST_JWT_SECRET=%s\n' "$secret"
    printf 'OMNIFLUX_DATA_API_TOKEN=%s.%s.%s\n' "$header" "$payload" "$signature"
  } > .env
  echo "Created .env with random local credentials and a matching PostgREST JWT."
fi

docker compose up -d --build --remove-orphans
attempt=0
while [ "$attempt" -lt 100 ]; do
  container="$(docker compose ps -q app)"
  if [ -n "$container" ]; then
    health="$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    if [ "$health" = healthy ]; then
      app_port="$(sed -n 's/^OMNIFLUX_APP_PORT=//p' .env | tail -n 1)"
      app_port="${app_port:-8080}"
      OMNIFLUX_SMOKE_URL="http://localhost:$app_port" ./scripts/smoke.sh
      echo "OmniFlux is ready at http://localhost:$app_port"
      exit 0
    fi
  fi
  attempt=$((attempt + 1))
  sleep 3
done
docker compose ps
docker compose logs --tail 80 app postgrest
echo "Timed out waiting for the application health check." >&2
exit 1
