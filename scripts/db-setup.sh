#!/usr/bin/env bash
# Applies deploy/db/*.sql as the database superuser: the BMS schema and role (Seata AT) and the CDC
# replication role and publication. Idempotent.
#   scripts/db-setup.sh [k3d|compose]   (default: k3d when its PostgreSQL pod exists, else Docker Compose)
set -euo pipefail
cd "$(dirname "$0")/.."
TARGET="${1:-$(kubectl -n astra get pod postgres-0 >/dev/null 2>&1 && echo k3d || echo compose)}"
psql_super() {
  case "$TARGET" in
    k3d) kubectl -n astra exec -i postgres-0 -- psql -q -U "$(kubectl -n astra get secret astra-db -o jsonpath='{.data.superuser}' | base64 -d)" -d astra -v ON_ERROR_STOP=1 "$@" ;;
    compose) docker compose -f deploy/docker/docker-compose.yml exec -T postgres psql -q -U astra -d astra -v ON_ERROR_STOP=1 "$@" ;;
    *) echo "usage: $0 [k3d|compose]" >&2; exit 2 ;;
  esac
}
for f in deploy/db/*.sql; do psql_super -f - < "$f"; done
echo "Database prepared (${TARGET}): wal_level=$(psql_super -tAc 'SHOW wal_level'), BMS schema, CDC role$(psql_super -tAc "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_publication WHERE pubname='astra_cdc') THEN ' and publication' ELSE ' (publication after the kernel migrates)' END")."
