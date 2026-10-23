#!/usr/bin/env bash
# DocType JSONB benchmark, limits phase. Runs where pgbench and psql are installed (bench.sh runs it inside a
# PostgreSQL container), after
# 10-limits-setup.sql. Every workload in workloads/ reproduces the statements the kernel sends for one
# operation (tenant bind, Link check, naming series, write with RETURNING *), as the application role.
#
# For each run it records throughput, latency percentiles per script from the per-transaction log, WAL bytes
# per transaction (pg_stat_wal), buffer cache hit ratio, and CPU use of the machine pgbench runs on (/proc/stat:
# the database host when pgbench runs in the database container, the client host otherwise).
#
#   ./run-limits.sh reads | writes | mixed | rate | rate-seq | locality | partition | sync-off | gin | probe | all
set -euo pipefail
: "${BENCH_HOST:=127.0.0.1}" "${BENCH_PORT:=5433}" "${BENCH_DB:=bench}"   # where the database under test is
export PGPASSWORD=astra_app
CONN=(-h "$BENCH_HOST" -p "$BENCH_PORT" -U astra_app)   # pgbench: -d is --debug, the database is the last argument
sq() { PGPASSWORD=${BENCH_SU_PASSWORD:-bench} psql -h "$BENCH_HOST" -p "$BENCH_PORT" -U "${BENCH_SU:-postgres}" -d "$BENCH_DB" -X -q -At -c "$1"; }
W=${W:-./workloads}
OUT=${OUT:-./results/limits}; mkdir -p "$OUT"
SECS=${SECS:-30}
THREADS=${THREADS:-8}
WARMUP=${WARMUP:-10}   # seconds at the start of each run left out of the latency percentiles
RATE_SECS=${RATE_SECS:-60}   # length of each fixed-rate run
NORDERS=$(sq "SELECT count(*) FROM bench_orders")
HOT_LO=$(( NORDERS - NORDERS / 10 + 1 ))   # newest 10 % of orders
SUMMARY="$OUT/summary.txt"

cpu_sample() { awk '/^cpu /{print $2+$3+$4+$7+$8, $2+$3+$4+$5+$6+$7+$8}' /proc/stat; }

# run <label> <clients> <rate or -> <secs> -f script@w ...
run() {
  local label=$1 clients=$2 rate=$3 secs=$4; shift 4
  local t; t=$(mktemp -d)
  local j=$(( clients < THREADS ? clients : THREADS ))
  local wal0 wal1 c0 c1 b0 b1
  local blks="SELECT blks_hit || ' ' || blks_read FROM pg_stat_database WHERE datname = current_database()"
  sq "CHECKPOINT" >/dev/null
  wal0=$(sq "SELECT wal_bytes FROM pg_stat_wal"); c0=$(cpu_sample); b0=$(sq "$blks")
  local rateopt=(); [ "$rate" != "-" ] && rateopt=(-R "$rate")
  pgbench "${CONN[@]}" -n -M "${QMODE:-prepared}" -P 5 -c "$clients" -j "$j" -T "$secs" -D norders="$NORDERS" -D hot_lo="$HOT_LO" "${rateopt[@]}" \
      -l --log-prefix="$t/l" "$@" "$BENCH_DB" > "$OUT/$label.txt" 2>&1 || true
  wal1=$(sq "SELECT wal_bytes FROM pg_stat_wal"); c1=$(cpu_sample); b1=$(sq "$blks")
  local tps txs failed cpu walpt hit
  tps=$(awk '/^tps = /{printf "%.0f", $3}' "$OUT/$label.txt")
  txs=$(awk '/number of transactions actually processed/{print $NF}' "$OUT/$label.txt" | head -1)
  failed=$(awk '/number of failed transactions/{print $5}' "$OUT/$label.txt" | head -1)
  cpu=$(echo "$c0 $c1" | awk '{b=$3-$1; a=$4-$2; printf "%.0f", (a>0)?100*b/a:0}')
  walpt=$(awk -v a="$wal0" -v b="$wal1" -v n="${txs:-0}" 'BEGIN{printf "%.0f", (n>0)?(b-a)/n:0}')
  hit=$(echo "$b0 $b1" | awk '{h=$3-$1; r=$4-$2; printf "%.1f", (h+r>0)?100*h/(h+r):100}')
  # Per-transaction log: client tx latency_us script epoch epoch_us [lag]; latency is "failed"/"skipped" on errors.
  # Percentiles leave out the first WARMUP seconds: right after the CHECKPOINT every first write of a page logs the
  # full page, a burst that would otherwise dominate p99 of fixed-rate runs. Throughput covers the whole run.
  cat "$t"/l* > "$t/all" 2>/dev/null || true
  {
    printf '%-34s c=%s R=%s tps=%s fail=%s cpu=%s%% hit=%s%% wal/tx=%s warmup=%ss' "$label" "$clients" "$rate" "${tps:-?}" "${failed:-?}" "$cpu" "$hit" "$walpt" "$WARMUP"
    awk -v w="$WARMUP" 'NR == FNR { if ($5 > 0 && (t0 == 0 || $5 < t0)) t0 = $5; next }
         $3 ~ /^[0-9]+$/ && $5 >= t0 + w { print $4, $3 }' "$t/all" "$t/all" | sort -k1,1n -k2,2n | awk 'BEGIN { s = -1 }
      function flush() { if (n) printf "  s%s n=%d p50=%.2f p95=%.2f p99=%.2f max=%.1f", s, n, v[int(n*0.50)+1]/1000, v[int(n*0.95)+1]/1000, v[int(n*0.99)+1]/1000, v[n]/1000 }
      $1 != s { flush(); s = $1; n = 0; delete v }
      { v[++n] = $2 }
      END { flush() }'
    echo
  } | tee -a "$SUMMARY"
  rm -rf "$t"
}


READ_JSONB=( -f "$W/jsonb-get.sql@7" -f "$W/jsonb-list-customer.sql@2" -f "$W/jsonb-list-status.sql@1" )
READ_REL=(   -f "$W/rel-get.sql@7"   -f "$W/rel-list-customer.sql@2"   -f "$W/rel-list-status.sql@1" )
# Peak-hour model per order: 1 create, 8 scan events, 14 gets, 6 customer lists, 2 status lists.
MIX_JSONB=( -f "$W/jsonb-insert-series.sql@1" -f "$W/jsonb-update-waybill.sql@8" -f "$W/jsonb-get.sql@14" -f "$W/jsonb-list-customer.sql@6" -f "$W/jsonb-list-status.sql@2" )
MIX_JSONB_SEQ=( -f "$W/jsonb-insert-seq.sql@1" -f "$W/jsonb-update-waybill.sql@8" -f "$W/jsonb-get.sql@14" -f "$W/jsonb-list-customer.sql@6" -f "$W/jsonb-list-status.sql@2" )
MIX_JSONB_HOT=( -f "$W/jsonb-insert-seq.sql@1" -f "$W/jsonb-update-waybill-hot.sql@8" -f "$W/jsonb-get-hot.sql@14" -f "$W/jsonb-list-customer.sql@6" -f "$W/jsonb-list-status.sql@2" )
MIX_REL=( -f "$W/rel-insert-series.sql@1" -f "$W/rel-update.sql@8" -f "$W/rel-get.sql@14" -f "$W/rel-list-customer.sql@6" -f "$W/rel-list-status.sql@2" )
MIX_TXN=( -f "$W/txn-insert-seq.sql@1" -f "$W/txn-update-waybill.sql@8" -f "$W/txn-get.sql@14" -f "$W/txn-list-customer.sql@6" -f "$W/txn-list-status.sql@2" )
MIX_TXN_PRUNED=( -f "$W/txn-insert-seq.sql@1" -f "$W/txn-update-waybill-pruned.sql@8" -f "$W/txn-get-pruned.sql@14" -f "$W/txn-list-customer.sql@6" -f "$W/txn-list-status.sql@2" )
MIX_REL_SEQ=( -f "$W/rel-insert-seq.sql@1" -f "$W/rel-update.sql@8" -f "$W/rel-get.sql@14" -f "$W/rel-list-customer.sql@6" -f "$W/rel-list-status.sql@2" )

MODE=${1:-all}
echo "== $MODE  norders=$NORDERS secs=$SECS threads=$THREADS  $(date -u '+%F %T UTC')  $(sq "SELECT current_setting('shared_buffers') || ' sb, ' || current_setting('max_wal_size') || ' wal, sync_commit=' || current_setting('synchronous_commit')")" | tee -a "$SUMMARY"

if [ "$MODE" = reads ] || [ "$MODE" = all ]; then
  for c in 8 16 32 64 128 256; do
    run "read-jsonb-c$c" "$c" - "$SECS" "${READ_JSONB[@]}"
    run "read-rel-c$c"   "$c" - "$SECS" "${READ_REL[@]}"
  done
fi
if [ "$MODE" = writes ] || [ "$MODE" = all ]; then
  for c in 4 16 64; do
    run "insert-series-jsonb-c$c" "$c" - "$SECS" -f "$W/jsonb-insert-series.sql"
    run "insert-series-rel-c$c"   "$c" - "$SECS" -f "$W/rel-insert-series.sql"
    run "insert-seq-jsonb-c$c"    "$c" - "$SECS" -f "$W/jsonb-insert-seq.sql"
    run "insert-seq-rel-c$c"      "$c" - "$SECS" -f "$W/rel-insert-seq.sql"
    run "update-order-jsonb-c$c"  "$c" - "$SECS" -f "$W/jsonb-update-order.sql"
    run "update-waybill-jsonb-c$c" "$c" - "$SECS" -f "$W/jsonb-update-waybill.sql"
    run "update-waybill-2tx-jsonb-c$c" "$c" - "$SECS" -f "$W/jsonb-update-waybill-2tx.sql"
    run "update-rel-c$c"          "$c" - "$SECS" -f "$W/rel-update.sql"
  done
fi
if [ "$MODE" = mixed ] || [ "$MODE" = all ]; then
  for c in 16 32 64 128; do
    run "mix-jsonb-c$c"     "$c" - "$SECS" "${MIX_JSONB[@]}"
    run "mix-jsonb-seq-c$c" "$c" - "$SECS" "${MIX_JSONB_SEQ[@]}"
    run "mix-rel-c$c"       "$c" - "$SECS" "${MIX_REL[@]}"
  done
fi
if [ "$MODE" = rate ] || [ "$MODE" = all ]; then
  # Peak target: 231 orders/s x 31 transactions per order = 7,161 tx/s; then 1.5x and 2x peak. Latency includes
  # the time a transaction waited for its scheduled start, so a run that cannot keep up shows growing p99.
  for r in ${RATES:-7200 10800 14400}; do
    run "rate-jsonb-R$r"     64 "$r" "$RATE_SECS" "${MIX_JSONB[@]}"       # kernel before the change: gapless series
    run "rate-jsonb-seq-R$r" 64 "$r" "$RATE_SECS" "${MIX_JSONB_SEQ[@]}"   # after: gapless: false
    run "rate-rel-seq-R$r"   64 "$r" "$RATE_SECS" "${MIX_REL_SEQ[@]}"     # traditional table, same naming
  done
fi
if [ "$MODE" = rate-seq ]; then
  for r in ${RATES:-7200 10800 14400}; do
    run "rate-jsonb-seq-R$r" 64 "$r" "$RATE_SECS" "${MIX_JSONB_SEQ[@]}"
    run "rate-rel-seq-R$r"   64 "$r" "$RATE_SECS" "${MIX_REL_SEQ[@]}"
  done
fi
if [ "$MODE" = locality ] || [ "$MODE" = all ]; then
  # Same work, keys drawn from all orders (worst case, most reads miss the cache) or from the newest 10 %.
  run "get-uniform-jsonb-c64" 64 - "$SECS" -f "$W/jsonb-get.sql"
  run "get-hot-jsonb-c64"     64 - "$SECS" -f "$W/jsonb-get-hot.sql"
  run "mix-hot-jsonb-seq-c64" 64 - "$SECS" "${MIX_JSONB_HOT[@]}"
fi
if [ "$MODE" = partition ] || [ "$MODE" = all ]; then
  # The same kernel operations on astra_doc (one table) and astra_doc_txn (18 monthly partitions, 12-partition-setup.sql),
  # and range filters on the materialised COD column against the same filter computed from the JSON document.
  run "part-get-doc-c64"            64 - "$SECS" -f "$W/jsonb-get.sql"
  run "part-get-txn-c64"            64 - "$SECS" -f "$W/txn-get.sql"
  run "part-get-txnpruned-c64"      64 - "$SECS" -f "$W/txn-get-pruned.sql"
  run "part-list-customer-doc-c64"  64 - "$SECS" -f "$W/jsonb-list-customer.sql"
  run "part-list-customer-txn-c64"  64 - "$SECS" -f "$W/txn-list-customer.sql"
  run "part-list-status-doc-c64"    64 - "$SECS" -f "$W/jsonb-list-status.sql"
  run "part-list-status-txn-c64"    64 - "$SECS" -f "$W/txn-list-status.sql"
  run "part-insert-doc-c64"         64 - "$SECS" -f "$W/jsonb-insert-seq.sql"
  run "part-insert-txn-c64"         64 - "$SECS" -f "$W/txn-insert-seq.sql"
  run "part-update-waybill-doc-c64" 64 - "$SECS" -f "$W/jsonb-update-waybill.sql"
  run "part-update-waybill-txn-c64" 64 - "$SECS" -f "$W/txn-update-waybill.sql"
  run "part-update-waybill-txnpruned-c64" 64 - "$SECS" -f "$W/txn-update-waybill-pruned.sql"
  run "part-mix-doc-c64"            64 - "$SECS" "${MIX_JSONB_SEQ[@]}"
  run "part-mix-txn-c64"            64 - "$SECS" "${MIX_TXN[@]}"
  run "part-mix-txnpruned-c64"      64 - "$SECS" "${MIX_TXN_PRUNED[@]}"
  run "range-cod-col-doc-c16"       16 - "$SECS" -f "$W/range-cod-col.sql"
  run "range-cod-col-txn-c16"       16 - "$SECS" -f "$W/txn-range-cod-col.sql"
  run "range-cod-json-txn-c16"      16 - "$SECS" -f "$W/txn-range-cod-json.sql"
  # The kernel plans range lists per execution (prepareThreshold 0): unnamed statements, like pgbench -M extended.
  QMODE=extended run "range-cod-col-doc-custom-c16" 16 - "$SECS" -f "$W/range-cod-col.sql"
  QMODE=extended run "range-cod-col-txn-custom-c16" 16 - "$SECS" -f "$W/txn-range-cod-col.sql"
  QMODE=extended run "range-cod-json-txn-custom-c16" 16 - "$SECS" -f "$W/txn-range-cod-json.sql"
fi
if [ "$MODE" = probe ]; then
  # Short before/after probe around a schema change; TAG names the variant.
  run "${TAG:-probe}-insert-series-jsonb-c16"  16 - "$SECS" -f "$W/jsonb-insert-series.sql"
  run "${TAG:-probe}-update-waybill-jsonb-c16" 16 - "$SECS" -f "$W/jsonb-update-waybill.sql"
  run "${TAG:-probe}-mix-jsonb-c64"            64 - "$SECS" "${MIX_JSONB[@]}"
fi
if [ "$MODE" = sync-off ]; then
  # synchronous_commit = off: commit returns before the WAL reaches disk; a crash loses up to ~3 x wal_writer_delay
  # of acknowledged transactions (never corrupts). Measured on the lock-free naming so the commit cost shows.
  sq "ALTER ROLE astra_app SET synchronous_commit = off" >/dev/null
  run "syncoff-insert-seq-jsonb-c16"     16 - "$SECS" -f "$W/jsonb-insert-seq.sql"
  run "syncoff-update-waybill-jsonb-c16" 16 - "$SECS" -f "$W/jsonb-update-waybill.sql"
  run "syncoff-mix-jsonb-seq-c64"        64 - "$SECS" "${MIX_JSONB_SEQ[@]}"
  sq "ALTER ROLE astra_app RESET synchronous_commit" >/dev/null
fi
if [ "$MODE" = gin ]; then
  # The V1 kernel's GIN jsonb_path_ops index, back on for comparison with the same runs without it.
  echo "building GIN jsonb_path_ops (V1 kernel index)" | tee -a "$SUMMARY"
  sq "SET maintenance_work_mem = '1GB'; CREATE INDEX astra_doc_data_gin ON astra_doc USING gin (data jsonb_path_ops)" >/dev/null
  run "gin-insert-seq-jsonb-c16"     16 - "$SECS" -f "$W/jsonb-insert-seq.sql"
  run "gin-insert-seq-jsonb-c64"     64 - "$SECS" -f "$W/jsonb-insert-seq.sql"
  run "gin-update-waybill-jsonb-c64" 64 - "$SECS" -f "$W/jsonb-update-waybill.sql"
  run "gin-mix-jsonb-seq-c64"        64 - "$SECS" "${MIX_JSONB_SEQ[@]}"
  sq "DROP INDEX astra_doc_data_gin" >/dev/null
fi
