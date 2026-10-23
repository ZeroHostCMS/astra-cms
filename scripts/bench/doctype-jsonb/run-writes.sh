#!/usr/bin/env bash
# DocType JSONB benchmark: write and concurrent read throughput with pgbench, as the application role with
# the tenant bound per transaction (what TenantTransactions does). Prepared statements, like pgjdbc.
set -euo pipefail
: "${BENCH_HOST:=127.0.0.1}" "${BENCH_PORT:=5433}" "${BENCH_DB:=bench}"   # where the database under test is
export PGPASSWORD=astra_app
CONN=(-h "$BENCH_HOST" -p "$BENCH_PORT" -U astra_app)   # pgbench: -d is --debug, the database is the last argument
OUT=${OUT:-./results}; mkdir -p "$OUT"
CLIENTS=${CLIENTS:-16}; SECS=${SECS:-30}
D=$(mktemp -d)

cat > "$D/insert-jsonb.sql" <<'EOF'
\set n random(1, 100000000)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
INSERT INTO astra_doc (tenant_id, doctype, name, owner, data) VALUES ('hanoi-express', 'CustomerOrder', 'NEW-' || :client_id || '-' || :n || '-' || clock_timestamp()::text, 'user1',
 jsonb_build_object('customer', 'CUST-' || lpad((:n % 1000000)::text, 7, '0'), 'receiver_name', 'Nguyen Van A', 'receiver_phone', '0912345678',
  'receiver_address', '12 Pho Hue, Quan Hai Ba Trung, Ha Noi', 'zone', 'Same city', 'service', 'Standard', 'weight_kg', 1.5,
  'cod_amount', 250000, 'shipping_fee', 25000, 'priced_by', 'standard tariff', 'ops_status', 'Created'));
END;
EOF
cat > "$D/insert-rel.sql" <<'EOF'
\set n random(1, 100000000)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
INSERT INTO orders_rel (tenant_id, name, owner, customer, receiver_name, receiver_phone, receiver_address, zone, service, weight_kg, cod_amount, shipping_fee, priced_by, ops_status)
VALUES ('hanoi-express', 'NEW-' || :client_id || '-' || :n || '-' || clock_timestamp()::text, 'user1', 'CUST-' || lpad((:n % 1000000)::text, 7, '0'), 'Nguyen Van A', '0912345678',
  '12 Pho Hue, Quan Hai Ba Trung, Ha Noi', 'Same city', 'Standard', 1.5, 250000, 25000, 'standard tariff', 'Created');
END;
EOF
# Status change on a random existing order: what a scan event does to a Waybill / order document.
cat > "$D/update-jsonb.sql" <<'EOF'
\set n random(1, 4000000)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
UPDATE astra_doc SET data = data || jsonb_build_object('ops_status', 'Delivered'), modified_at = now(), version = version + 1
 WHERE doctype = 'CustomerOrder' AND name = 'ORD-' || to_char(timestamptz '2026-10-20 00:00+07' + (:n - 1) * (interval '60 hours' / 5000000), 'YYYYMMDD') || '-' || lpad(:n::text, 8, '0');
END;
EOF
cat > "$D/update-rel.sql" <<'EOF'
\set n random(1, 4000000)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
UPDATE orders_rel SET ops_status = 'Delivered', modified_at = now(), version = version + 1
 WHERE name = 'ORD-' || to_char(timestamptz '2026-10-20 00:00+07' + (:n - 1) * (interval '60 hours' / 5000000), 'YYYYMMDD') || '-' || lpad(:n::text, 8, '0');
END;
EOF
# Read mix: 70 % point get, 30 % customer list.
cat > "$D/read-jsonb.sql" <<'EOF'
\set n random(1, 4000000)
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND name = 'ORD-' || to_char(timestamptz '2026-10-20 00:00+07' + (:n - 1) * (interval '60 hours' / 5000000), 'YYYYMMDD') || '-' || lpad(:n::text, 8, '0');
END;
EOF
cat > "$D/list-jsonb.sql" <<'EOF'
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND (data->>'customer') = 'CUST-' || lpad(:c::text, 7, '0') ORDER BY modified_at DESC LIMIT 20 OFFSET 0;
END;
EOF
cat > "$D/list-jsonb-lp.sql" <<'EOF'
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND astra_field(data, 'customer') = 'CUST-' || lpad(:c::text, 7, '0') ORDER BY modified_at DESC LIMIT 20 OFFSET 0;
END;
EOF
cat > "$D/read-rel.sql" <<'EOF'
\set n random(1, 4000000)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM orders_rel WHERE name = 'ORD-' || to_char(timestamptz '2026-10-20 00:00+07' + (:n - 1) * (interval '60 hours' / 5000000), 'YYYYMMDD') || '-' || lpad(:n::text, 8, '0');
END;
EOF
cat > "$D/list-rel.sql" <<'EOF'
\set c random(0, 999999)
BEGIN;
SELECT set_config('astra.tenant', 'hanoi-express', true);
SELECT * FROM orders_rel WHERE customer = 'CUST-' || lpad(:c::text, 7, '0') ORDER BY modified_at DESC LIMIT 20 OFFSET 0;
END;
EOF

bench() { # label files...
  local label=$1; shift
  echo "== $label clients=$CLIENTS secs=$SECS $(date)" | tee -a "$OUT/writes-summary.txt"
  pgbench "${CONN[@]}" -n -M prepared -c "$CLIENTS" -j 4 -T "$SECS" -P 10 "$BENCH_DB" "$@" 2>&1 | tee "$OUT/pgbench-$label.txt" | grep -E "tps|latency average|latency stddev" | tee -a "$OUT/writes-summary.txt"
}
stats() { psql "${CONN[@]}" -d "$BENCH_DB" -X -Atc "SELECT relname, n_tup_ins, n_tup_upd, n_tup_hot_upd, round(100.0*n_tup_hot_upd/greatest(n_tup_upd,1),1) AS hot_pct, n_dead_tup FROM pg_stat_user_tables WHERE relname IN ('astra_doc','orders_rel') ORDER BY 1" | tee -a "$OUT/writes-summary.txt"; }

MODE=${1:-all}
if [ "$MODE" = reads ] || [ "$MODE" = all ]; then
    bench read-jsonb -f "$D/read-jsonb.sql@7" -f "$D/list-jsonb.sql@3"
    bench read-rel   -f "$D/read-rel.sql@7"   -f "$D/list-rel.sql@3"
fi
if [ "$MODE" = reads-fixed ]; then
    bench read-jsonb-lp  -f "$D/read-jsonb.sql@7" -f "$D/list-jsonb-lp.sql@3"
    bench read-rel-again -f "$D/read-rel.sql@7"   -f "$D/list-rel.sql@3"
fi
if [ "$MODE" = writes ] || [ "$MODE" = all ]; then
    stats
    bench insert-jsonb -f "$D/insert-jsonb.sql"
    bench insert-rel   -f "$D/insert-rel.sql"
    stats
    bench update-jsonb -f "$D/update-jsonb.sql"
    bench update-rel   -f "$D/update-rel.sql"
    stats
fi
rm -rf "$D"
