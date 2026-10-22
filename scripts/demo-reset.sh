#!/usr/bin/env bash
# Deletes the Astra Express demo tenants so scripts/demo-seed.sh can run again.
#   scripts/demo-reset.sh [compose|k3d]   (default: k3d when its PostgreSQL pod exists, else Docker Compose)
# Uses the bootstrap superuser of the local database; never point this at real data.
set -euo pipefail
cd "$(dirname "$0")/.."
TARGET="${1:-$(kubectl -n astra get pod postgres-0 >/dev/null 2>&1 && echo k3d || echo compose)}"
TENANTS="'saigon-express','hanoi-express'"
# The ledger refuses DELETE for every role; a local reset lifts that guard inside one transaction only.
SQL="BEGIN;
DELETE FROM astra_doc WHERE tenant_id IN (${TENANTS});
DELETE FROM astra_series WHERE tenant_id IN (${TENANTS});
DELETE FROM astra_overlay WHERE tenant_id IN (${TENANTS});
DO \$\$ BEGIN
  IF to_regclass('public.replica_waybill') IS NOT NULL THEN   -- CDC replicas: the worker never sees deletes
    DELETE FROM replica_waybill WHERE tenant_id IN (${TENANTS});
    DELETE FROM replica_order WHERE tenant_id IN (${TENANTS});
  END IF;
END \$\$;
ALTER TABLE astra_ledger DISABLE TRIGGER astra_ledger_no_update_delete;
DELETE FROM astra_ledger WHERE tenant_id IN (${TENANTS});
ALTER TABLE astra_ledger ENABLE TRIGGER astra_ledger_no_update_delete;
DO \$\$ BEGIN
  IF to_regclass('bms.customer_credit') IS NOT NULL THEN
    DELETE FROM bms.customer_credit WHERE tenant_id IN (${TENANTS});
    DELETE FROM bms.undo_log;   -- local demo only: undo images of interrupted runs
  END IF;
END \$\$;
COMMIT;"

case "$TARGET" in
  k3d)
    SUPERUSER="$(kubectl -n astra get secret astra-db -o jsonpath='{.data.superuser}' | base64 -d)"
    kubectl -n astra exec -i postgres-0 -- psql -U "$SUPERUSER" -d astra -v ON_ERROR_STOP=1 -c "$SQL" ;;
  compose)
    docker compose -f deploy/docker/docker-compose.yml exec -T postgres psql -U astra -d astra -v ON_ERROR_STOP=1 -c "$SQL" ;;
  *) echo "usage: $0 [compose|k3d]" >&2; exit 2 ;;
esac
# Redis: TCC pickup slots and markers of the demo tenants.
redis() {
  if [[ $TARGET == k3d ]]; then kubectl -n astra exec -i deploy/redis -- redis-cli "$@"
  else docker compose -f deploy/docker/docker-compose.yml exec -T redis redis-cli "$@"; fi
}
for t in saigon-express hanoi-express; do
  keys=$(redis --scan --pattern "astra:${t}:*" | tr '\n' ' ')
  [[ -n ${keys// } ]] && redis DEL $keys >/dev/null
done
echo "Demo tenants cleared (${TARGET})."
