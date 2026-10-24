#!/usr/bin/env bash
# DocType JSONB benchmark: Q5 and Q7 after 05-bigint-amounts.sql (amount columns as bigint, tables repacked).
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
echo "== BIGINT tenant=$TENANT  $(date)" | tee -a "$OUT/summary.txt"
run q5 jsonb-i8 "SELECT * FROM astra_doc WHERE doctype = 'CustomerOrder' AND astra_field(data, 'zone') = 'Remote' AND m_cod_amount > 4900000 ORDER BY modified_at DESC LIMIT 20"
run q5 rel-i8   "SELECT * FROM orders_rel WHERE zone = 'Remote' AND cod_amount > 4900000 ORDER BY modified_at DESC LIMIT 20"
run q7 jsonb-i8 "SELECT data->>'zone' AS zone, count(*), sum(m_cod_amount) FROM astra_doc WHERE doctype = 'CustomerOrder' AND created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' GROUP BY 1"
run q7 rel-i8   "SELECT zone, count(*), sum(cod_amount) FROM orders_rel WHERE created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' GROUP BY 1"
run q7 jsonb-i8-json "SELECT data->>'zone' AS zone, count(*), sum((data->>'cod_amount')::numeric) FROM astra_doc WHERE doctype = 'CustomerOrder' AND created_at >= '2026-10-21 00:00+07' AND created_at < '2026-10-22 00:00+07' GROUP BY 1"
