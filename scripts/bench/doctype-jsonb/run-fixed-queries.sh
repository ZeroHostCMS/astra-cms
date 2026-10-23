#!/usr/bin/env bash
# DocType JSONB benchmark: the same questions as run-queries.sh, asked the way 04-fixes.sql makes possible.
set -euo pipefail
: "${BENCH_HOST:=127.0.0.1}" "${BENCH_PORT:=5433}" "${BENCH_DB:=bench}"   # where the database under test is
export PGPASSWORD=astra_app
PSQL=(psql -h "$BENCH_HOST" -p "$BENCH_PORT" -U astra_app -d "$BENCH_DB" -X -q -v ON_ERROR_STOP=1)
OUT=${OUT:-./results}; mkdir -p "$OUT"
TENANT=${TENANT:-hanoi-express}
run() {
  local id=$1 shape=$2 sql=$3 best= first=
  local f="$OUT/$id-$shape.txt"
  : > "$f"
  for i in 1 2 3 4; do
    local t
    t=$("${PSQL[@]}" -At -c "SELECT set_config('astra.tenant', '$TENANT', false)" \
          -c "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $sql" | tee -a "$f" | grep -E '^Execution Time' | awk '{print $3}')
    echo "----- run $i: $t ms" >> "$f"
    if [ $i -eq 1 ]; then first=$t; else best=$(awk -v a="${best:-999999999}" -v b="$t" 'BEGIN{print (b<a)?b:a}'); fi
  done
  printf '%-6s %-9s first=%10s ms  warm-best=%10s ms\n' "$id" "$shape" "$first" "$best" | tee -a "$OUT/summary.txt"
}
echo "== FIXED tenant=$TENANT  $(date)" | tee -a "$OUT/summary.txt"

# Q2 with the leakproof accessor (F1)
run q2 jsonb-lp "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND astra_field(data, 'customer') = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
run q9 jsonb-lp "SELECT count(*) FROM astra_doc WHERE doctype = 'CustomerOrder' AND astra_field(data, 'customer') = 'CUST-0123456'"
run q4 jsonb-lp "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND docstatus = 1 AND astra_field(data, 'customer') = 'CUST-0123456' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
# Q3b with the composite status + modified_at index (F2)
run q3b jsonb-lp "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND astra_field(data, 'ops_status') = 'Cancelled' ORDER BY modified_at DESC LIMIT 20 OFFSET 0"
# Q5 / Q7 with the materialised numeric column (F3)
run q5 jsonb-mat "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND astra_field(data, 'zone') = 'Remote' AND m_cod_amount > 4900000 ORDER BY modified_at DESC LIMIT 20"
run q5 rel-idx   "SELECT * FROM orders_rel WHERE zone = 'Remote' AND cod_amount > 4900000 ORDER BY modified_at DESC LIMIT 20"
run q7 jsonb-mat "SELECT data->>'zone' AS zone, count(*), sum(m_cod_amount) FROM astra_doc WHERE doctype = 'CustomerOrder' AND created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' GROUP BY 1"
run q7 rel-idx   "SELECT zone, count(*), sum(cod_amount) FROM orders_rel WHERE created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' GROUP BY 1"
# Q6 with a created_at index on the unpartitioned tables (F4)
run q6 jsonb-idx "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' AND astra_field(data, 'ops_status') = 'Failed Delivery' ORDER BY created_at DESC LIMIT 50"
run q6 rel-idx   "SELECT * FROM orders_rel WHERE created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' AND ops_status = 'Failed Delivery' ORDER BY created_at DESC LIMIT 50"
# Keyset pagination instead of OFFSET (both shapes)
run q10 jsonb-ks "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND modified_at < '2026-10-21 03:00+07' ORDER BY modified_at DESC LIMIT 20"
run q10 rel-ks   "SELECT * FROM orders_rel WHERE modified_at < '2026-10-21 03:00+07' ORDER BY modified_at DESC LIMIT 20"
