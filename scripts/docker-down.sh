#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_ROOT"

command -v docker >/dev/null 2>&1 || {
  echo "Docker was not found. Install Docker Engine and the Compose plugin first." >&2
  exit 1
}
docker info >/dev/null || {
  echo "Docker Engine is not reachable. Start Docker Desktop or Docker Engine, then retry." >&2
  exit 1
}
docker compose version >/dev/null

docker compose down
echo "Containers stopped. Named volumes and demo data were preserved."
