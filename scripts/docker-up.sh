#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_ROOT"

random_secret() {
  local bytes=${1:-24}
  od -An -N"$bytes" -tx1 /dev/urandom | tr -d ' \n'
}

if [[ ! -f .env ]]; then
  root_password=$(random_secret)
  app_password=$(random_secret)
  # MySQL replication's report-password field is limited; keep this <= 32 bytes.
  canal_password=$(random_secret 12)
  printf '%s\n' \
    "MYSQL_ROOT_PASSWORD=$root_password" \
    "MYSQL_APP_PASSWORD=$app_password" \
    "CANAL_DB_PASSWORD=$canal_password" \
    "HTTP_PORT=8080" > .env
  chmod 600 .env
  echo "Created local .env with random passwords."
fi

docker compose up -d --build --wait --wait-timeout 420
docker compose ps
http_port=$(sed -n 's/^HTTP_PORT=//p' .env | tail -n 1 | tr -d '\r')
echo
echo "FuYouXuan is ready: http://localhost:${http_port:-8080}"
