#!/usr/bin/env bash
# Writes 30 days of demo history into tenant saigon-express (scripts/sql/demo-history.sql): Shop ABC's orders for the
# customer-pricing scene, hub weights, COD payouts and courier failures with planted, labelled anomalies for the
# anomaly scene. Needs customer ABC (scripts/demo-seed.sh). Uses the local bootstrap superuser; never real data.
#   scripts/demo-seed-history.sh [compose|k3d]
set -euo pipefail
cd "$(dirname "$0")/.."
TARGET="${1:-$(kubectl -n astra get pod postgres-0 >/dev/null 2>&1 && echo k3d || echo compose)}"
case "$TARGET" in
  k3d)
    SUPERUSER="$(kubectl -n astra get secret astra-db -o jsonpath='{.data.superuser}' | base64 -d)"
    kubectl -n astra exec -i postgres-0 -- psql -U "$SUPERUSER" -d astra -v ON_ERROR_STOP=1 -q < scripts/sql/demo-history.sql ;;
  compose)
    docker compose -f deploy/docker/docker-compose.yml exec -T postgres psql -U astra -d astra -v ON_ERROR_STOP=1 -q < scripts/sql/demo-history.sql ;;
  *) echo "usage: $0 [compose|k3d]" >&2; exit 2 ;;
esac
