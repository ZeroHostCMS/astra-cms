#!/usr/bin/env bash
# DocType JSONB benchmark: read queries. Every query runs as the non-superuser application role with the
# tenant bound through set_config, exactly like TenantTransactions does, so row-level security is active.
# Each query is executed 4 times; the first run is reported as "cold-ish" (whatever the cache holds), the best
# of the remaining three as "warm". EXPLAIN (ANALYZE, BUFFERS) output goes to $OUT/<id>-<shape>.txt.
set -euo pipefail
export PGPASSWORD=astra_app
PSQL=(psql -h 127.0.0.1 -p 5433 -U astra_app -d bench -X -q -v ON_ERROR_STOP=1)
OUT=${OUT:-./results}
mkdir -p "$OUT"
TENANT=${TENANT:-hanoi-express}

run() { # id shape sql
  local id=$1 shape=$2 sql=$3 best= first=
  local f="$OUT/$id-$shape.txt"
  : > "$f"
  for i in 1 2 3 4; do
    local t
    t=$("${PSQL[@]}" -At -c "SELECT set_config('astra.tenant', '$TENANT', false)" \
          -c "EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT TEXT) $sql" | tee -a "$f" | grep -E '^Execution Time' | awk '{print $3}')
    echo "----- run $i: $t ms" >> "$f"
    if [ $i -eq 1 ]; then first=$t; else best=$(awk -v a="${best:-999999999}" -v b="$t" 'BEGIN{print (b<a)?b:a}'); fi
  done
  printf '%-6s %-5s first=%10s ms  warm-best=%10s ms\n' "$id" "$shape" "$first" "$best" | tee -a "$OUT/summary.txt"
}

echo "== tenant=$TENANT  $(date)" | tee -a "$OUT/summary.txt"

# Q1 point get by name (DocumentStore.get)
run q1 jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND name = 'ORD-20261021-02500000'"
run q1 rel   "SELECT * FROM orders_rel WHERE name = 'ORD-20261021-02500000'"
run q1 part  "SELECT * FROM astra_doc_part WHERE doctype = 'CustomerOrder' AND name = 'ORD-20261021-02500000'"

# Q2 list by projected Link field, high cardinality (DocumentStore.list, filters={customer})
run q2 jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND (data->>'customer') = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
run q2 rel   "SELECT * FROM orders_rel WHERE customer = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
run q2 part  "SELECT * FROM astra_doc_part WHERE doctype = 'CustomerOrder' AND (data->>'customer') = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"

# Q3 list by projected Select field, low cardinality: common value (10 %) and rare value (0.5 %)
run q3a jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND (data->>'zone') = 'Remote' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
run q3a rel   "SELECT * FROM orders_rel WHERE zone = 'Remote' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
run q3b jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND (data->>'ops_status') = 'Cancelled' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
run q3b rel   "SELECT * FROM orders_rel WHERE ops_status = 'Cancelled' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
run q3b part  "SELECT * FROM astra_doc_part WHERE doctype = 'CustomerOrder' AND (data->>'ops_status') = 'Cancelled' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"

# Q4 customer + docstatus (Desk list with a status tab)
run q4 jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND docstatus = 1 AND (data->>'customer') = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
run q4 rel   "SELECT * FROM orders_rel WHERE docstatus = 1 AND customer = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"

# Q5 numeric range on a non-indexed Currency field, combined with an indexed Select
run q5 jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND (data->>'zone') = 'Remote' AND (data->>'cod_amount')::numeric > 4900000 ORDER BY modified_at DESC LIMIT 20"
run q5 rel   "SELECT * FROM orders_rel WHERE zone = 'Remote' AND cod_amount > 4900000 ORDER BY modified_at DESC LIMIT 20"

# Q6 one day of orders in a rare status, newest first (ops dashboard)
run q6 jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' AND (data->>'ops_status') = 'Failed Delivery' ORDER BY created_at DESC LIMIT 50"
run q6 rel   "SELECT * FROM orders_rel WHERE created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' AND ops_status = 'Failed Delivery' ORDER BY created_at DESC LIMIT 50"
run q6 part  "SELECT * FROM astra_doc_part WHERE doctype = 'CustomerOrder' AND created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' AND (data->>'ops_status') = 'Failed Delivery' ORDER BY created_at DESC LIMIT 50"

# Q7 aggregation over one day (2M rows): COD total per zone
run q7 jsonb "SELECT data->>'zone' AS zone, count(*), sum((data->>'cod_amount')::numeric) FROM astra_doc WHERE doctype = 'CustomerOrder' AND created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' GROUP BY 1"
run q7 rel   "SELECT zone, count(*), sum(cod_amount) FROM orders_rel WHERE created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' GROUP BY 1"
run q7 part  "SELECT data->>'zone' AS zone, count(*), sum((data->>'cod_amount')::numeric) FROM astra_doc_part WHERE doctype = 'CustomerOrder' AND created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' GROUP BY 1"

# Q8 GIN containment: single key, and two keys ANDed (where GIN should beat two B-trees)
run q8a jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND data @> '{\"customer\":\"CUST-0123456\"}' ORDER BY modified_at DESC LIMIT 20"
run q8b jsonb "SELECT count(*) FROM astra_doc WHERE doctype = 'CustomerOrder' AND data @> '{\"zone\":\"Remote\",\"ops_status\":\"Returned\"}'"
run q8b rel   "SELECT count(*) FROM orders_rel WHERE zone = 'Remote' AND ops_status = 'Returned'"

# Q9 count for one customer (list page total)
run q9 jsonb "SELECT count(*) FROM astra_doc WHERE doctype = 'CustomerOrder' AND (data->>'customer') = 'CUST-0123456'"
run q9 rel   "SELECT count(*) FROM orders_rel WHERE customer = 'CUST-0123456'"

# Q10 deep OFFSET pagination on the plain list (both shapes suffer; keyset pagination is the fix)
run q10 jsonb "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' ORDER BY modified_at DESC LIMIT 20 OFFSET 200000"
run q10 rel   "SELECT * FROM orders_rel ORDER BY modified_at DESC LIMIT 20 OFFSET 200000"
