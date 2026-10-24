#!/usr/bin/env bash
# Turns the raw outputs of one run (results/<label>/) into metrics.csv (one "key,value" per line, the format
# compare.sh joins across runs) and report.md (the headline tables). Safe to re-run; reads only text files.
#   ./summarize.sh results/<label>
set -euo pipefail
DIR=${1:?usage: summarize.sh results/<label>}
M="$DIR/metrics.csv"
R="$DIR/report.md"

{
  echo "key,value"
  # machine
  if [ -f "$DIR/machine.txt" ]; then
    awk -F': ' '/^(label|note|product|cpu_model|cpus|ram_mb|profile|rows|postgres|data_device):/ {
      v = $2; gsub(/,/, ";", v); sub(/ *#.*/, "", v); print "machine." $1 "," v }' "$DIR/machine.txt"
  fi
  [ -f "$DIR/pg_test_fsync.txt" ] && awk '/fdatasync/ && /ops\/sec/ { for (i = 1; i <= NF; i++) if ($i == "ops/sec") { print "machine.fdatasync_ops_per_s," $(i-1); exit } }' "$DIR/pg_test_fsync.txt"

  # single-query latency: sections of summary.txt are the baseline, FIXED and BIGINT runs
  if [ -f "$DIR/summary.txt" ]; then
    awk '/^== FIXED/ {sec = "fixed"; next} /^== BIGINT/ {sec = "bigint"; next} /^== / {sec = "base"; next}
         { gsub(/=/, "= "); $0 = $0 }
         $3 == "first=" { id = $1; shape = $2; first = $4; best = $7
                          print "query." sec "." id "." shape ".first_ms," first
                          print "query." sec "." id "." shape ".warm_ms," best }' "$DIR/summary.txt" \
      | awk -F, '{ if ($2 ~ /^[0-9.]+$/) print }'
  fi

  # pgbench runs of run-writes.sh
  if [ -f "$DIR/writes-summary.txt" ]; then
    awk '/^== / { label = $2 } /^tps = / { print "writes." label ".tps," int($3 + 0.5) }
         /^latency average/ { print "writes." label ".latency_avg_ms," $4 }' "$DIR/writes-summary.txt"
  fi

  # limits: one line per run: label c= R= tps= fail= cpu= hit= wal/tx= s0 n= p50= p95= p99= max= s1 ...
  if [ -f "$DIR/limits/summary.txt" ]; then
    awk '{ gsub(/= +/, "=") }   # older lines padded values: "cpu= 8%"
         $2 ~ /^c=/ { label = $1
           for (i = 2; i <= NF; i++) {
             split($i, kv, "=")
             if (kv[1] ~ /^(c|tps|fail|cpu|hit|wal\/tx|warmup)$/) { k = kv[1]; v = kv[2]; sub(/%$/, "", v); sub(/\//, "_per_", k); print "limits." label "." k "," v }
             else if ($i ~ /^s[0-9]+$/) script = $i
             else if ($i == "s") script = "s0"   # runs recorded before scripts were numbered from s0
             else if (kv[1] ~ /^p(50|95|99)$/ || kv[1] == "max") print "limits." label "." script "." kv[1] "_ms," kv[2]
           } }' "$DIR/limits/summary.txt"
  fi

  # sizes (MB) of the two order tables at each stage that recorded them
  for f in "$DIR"/sizes-*.txt "$DIR/limits/sizes.txt"; do
    [ -f "$f" ] || continue
    stage=$(basename "$f" .txt); [ "$f" = "$DIR/limits/sizes.txt" ] && stage=sizes-limits
    awk -v st="${stage#sizes-}" -F'|' 'function mb(s) { gsub(/^ +| +$/, "", s); split(s, a, " ")
             return a[2] == "GB" ? a[1] * 1024 : a[2] == "kB" ? a[1] / 1024 : a[2] == "bytes" ? 0 : a[1] }
         $1 ~ /^ (astra_doc|orders_rel) +$/ && NF >= 5 { t = $1; gsub(/ /, "", t)
             print "size." st "." t ".heap_mb," mb($2); print "size." st "." t ".index_mb," mb($3) }' "$f"
  done
} > "$M"

val() { awk -F, -v k="$1" '$1 == k { v = $2; found = 1 } END { print found ? v : "–" }' "$M"; }   # last value wins

{
  echo "# DocType JSONB benchmark: $(val machine.label)"
  echo
  echo "| Machine | |"
  echo "| --- | --- |"
  for k in note product cpu_model cpus ram_mb data_device postgres profile rows fdatasync_ops_per_s; do
    echo "| $k | $(val machine.$k) |"
  done
  echo
  echo "## Single queries, warm (ms)"
  echo
  echo "| Query | JSONB baseline | JSONB fixed | Traditional |"
  echo "| --- | --- | --- | --- |"
  for q in q1 q2 q3b q4 q5 q6 q7 q9 q10; do
    fixed="–"
    for k in "query.fixed.$q.jsonb-lp" "query.fixed.$q.jsonb-idx" "query.fixed.$q.jsonb-ks" "query.bigint.$q.jsonb-i8" "query.fixed.$q.jsonb-mat"; do
      v=$(val "$k.warm_ms"); [ "$v" != "–" ] && { fixed=$v; break; }
    done
    echo "| $q | $(val "query.base.$q.jsonb.warm_ms") | $fixed | $(val "query.base.$q.rel.warm_ms") |"
  done
  echo
  echo "## Read throughput by connections (transactions/s, p99 ms of the point get)"
  echo
  echo "| Clients | JSONB tps | JSONB p99 | Traditional tps | Traditional p99 |"
  echo "| --- | --- | --- | --- | --- |"
  for c in 8 16 32 64 128 256; do
    echo "| $c | $(val limits.read-jsonb-c$c.tps) | $(val limits.read-jsonb-c$c.s0.p99_ms) | $(val limits.read-rel-c$c.tps) | $(val limits.read-rel-c$c.s0.p99_ms) |"
  done
  echo
  echo "## Writes, kernel-shaped transactions (transactions/s at 4 / 16 / 64 clients)"
  echo
  echo "| Workload | c=4 | c=16 | c=64 | p99 ms at 64 |"
  echo "| --- | --- | --- | --- | --- |"
  for w in insert-series-jsonb insert-series-rel insert-seq-jsonb insert-seq-rel update-order-jsonb update-waybill-jsonb update-waybill-2tx-jsonb update-rel; do
    echo "| $w | $(val limits.$w-c4.tps) | $(val limits.$w-c16.tps) | $(val limits.$w-c64.tps) | $(val limits.$w-c64.s0.p99_ms) |"
  done
  echo
  echo "## Peak-hour mix (1 create : 8 scans : 14 gets : 6 customer lists : 2 status lists)"
  echo
  echo "| Run | tps | create p99 | scan p99 | get p99 | CPU % |"
  echo "| --- | --- | --- | --- | --- | --- |"
  for r in mix-jsonb-c16 mix-jsonb-c32 mix-jsonb-c64 mix-jsonb-c128 mix-jsonb-seq-c64 mix-rel-c64 \
           rate-jsonb-R7200 rate-jsonb-R14400 rate-jsonb-seq-R7200 rate-jsonb-seq-R10800 rate-jsonb-seq-R14400 rate-rel-seq-R7200 rate-rel-seq-R10800 rate-rel-seq-R14400 \
           mix-hot-jsonb-seq-c64 syncoff-mix-jsonb-seq-c64 gin-mix-jsonb-seq-c64; do
    echo "| $r | $(val limits.$r.tps) | $(val limits.$r.s0.p99_ms) | $(val limits.$r.s1.p99_ms) | $(val limits.$r.s2.p99_ms) | $(val limits.$r.cpu) |"
  done
  echo
  echo "## Month partitioning (18 partitions) and materialised range filters, transactions/s"
  echo
  echo "| Operation | astra_doc | astra_doc_txn (partitioned) | p99 ms doc / txn |"
  echo "| --- | --- | --- | --- |"
  for op in get list-customer list-status insert update-waybill mix; do
    echo "| $op | $(val limits.part-$op-doc-c64.tps) | $(val limits.part-$op-txn-c64.tps) | $(val limits.part-$op-doc-c64.s0.p99_ms) / $(val limits.part-$op-txn-c64.s0.p99_ms) |"
  done
  for op in get update-waybill mix; do
    echo "| $op, name-date bound | – | $(val limits.part-$op-txnpruned-c64.tps) | – / $(val limits.part-$op-txnpruned-c64.s0.p99_ms) |"
  done
  echo "| COD range, column, generic plan, 16 clients | $(val limits.range-cod-col-doc-c16.tps) | $(val limits.range-cod-col-txn-c16.tps) | $(val limits.range-cod-col-doc-c16.s0.p99_ms) / $(val limits.range-cod-col-txn-c16.s0.p99_ms) |"
  echo "| COD range, column, plan per value, 16 clients | $(val limits.range-cod-col-doc-custom-c16.tps) | $(val limits.range-cod-col-txn-custom-c16.tps) | $(val limits.range-cod-col-doc-custom-c16.s0.p99_ms) / $(val limits.range-cod-col-txn-custom-c16.s0.p99_ms) |"
  echo "| COD range, from JSON, 16 clients | – | $(val limits.range-cod-json-txn-c16.tps) / $(val limits.range-cod-json-txn-custom-c16.tps) per value | – / $(val limits.range-cod-json-txn-c16.s0.p99_ms) |"
  echo
  echo "## Trade-offs"
  echo
  echo "| Run | tps | p99 ms |"
  echo "| --- | --- | --- |"
  for r in get-uniform-jsonb-c64 get-hot-jsonb-c64 insert-seq-jsonb-c16 syncoff-insert-seq-jsonb-c16 \
           update-waybill-jsonb-c16 syncoff-update-waybill-jsonb-c16 insert-seq-jsonb-c64 gin-insert-seq-jsonb-c64 \
           update-waybill-jsonb-c64 gin-update-waybill-jsonb-c64; do
    echo "| $r | $(val limits.$r.tps) | $(val limits.$r.s0.p99_ms) |"
  done
  echo
  echo "Raw outputs: every file next to this report. Key/value form: metrics.csv."
} > "$R"
echo "wrote $M and $R"
