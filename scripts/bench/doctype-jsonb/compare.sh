#!/usr/bin/env bash
# Puts several benchmark runs side by side. Each argument is a results/<label> folder holding metrics.csv
# (written by summarize.sh); with no argument every folder under results/ is used.
#
#   ./compare.sh                                  # headline table of every run, Markdown, to stdout
#   ./compare.sh results/aws-m7i-* results/hetzner-*
#   ALL=1 ./compare.sh > all-metrics.csv          # every metric, CSV (one row per metric, one column per run)
#   KEYS='limits.mix-jsonb-c64.tps limits.read-jsonb-c64.tps' ./compare.sh
#
# Runs repeated on the same machine (labels that differ only in the date) show the run-to-run spread;
# compare medians across machines, not single runs.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
DIRS=("$@")
if [ ${#DIRS[@]} -eq 0 ]; then
  for d in "$HERE"/results/*/; do [ -f "$d/metrics.csv" ] && DIRS+=("${d%/}"); done
fi
[ ${#DIRS[@]} -gt 0 ] || { echo "no results/<label>/metrics.csv found; run ./summarize.sh on a run first" >&2; exit 1; }
for d in "${DIRS[@]}"; do [ -f "$d/metrics.csv" ] || "$HERE/summarize.sh" "$d" >/dev/null; done

HEADLINE=${KEYS:-"machine.product machine.cpu_model machine.cpus machine.ram_mb machine.profile machine.rows machine.fdatasync_ops_per_s
query.base.q2.jsonb.warm_ms query.fixed.q2.jsonb-lp.warm_ms query.base.q2.rel.warm_ms
query.base.q7.jsonb.warm_ms query.base.q7.rel.warm_ms
limits.read-jsonb-c16.tps limits.read-jsonb-c64.tps limits.read-rel-c64.tps limits.read-jsonb-c64.s0.p99_ms
limits.insert-series-jsonb-c16.tps limits.insert-seq-jsonb-c16.tps limits.insert-seq-jsonb-c64.tps
limits.update-waybill-jsonb-c16.tps limits.update-waybill-jsonb-c64.tps limits.update-waybill-2tx-jsonb-c64.tps limits.update-rel-c64.tps
limits.mix-jsonb-c64.tps limits.mix-jsonb-seq-c64.tps limits.mix-rel-c64.tps limits.mix-jsonb-c64.s0.p99_ms limits.mix-jsonb-c64.s1.p99_ms
limits.rate-jsonb-R7200.s1.p99_ms limits.rate-jsonb-seq-R7200.s1.p99_ms limits.rate-jsonb-seq-R10800.s1.p99_ms limits.rate-jsonb-seq-R14400.tps limits.rate-jsonb-seq-R14400.s1.p99_ms
limits.part-get-txn-c64.tps limits.part-mix-doc-c64.tps limits.part-mix-txn-c64.tps limits.range-cod-col-txn-c16.tps limits.range-cod-json-txn-c16.tps
limits.syncoff-mix-jsonb-seq-c64.tps limits.gin-insert-seq-jsonb-c64.tps limits.gin-mix-jsonb-seq-c64.tps limits.get-hot-jsonb-c64.tps limits.get-uniform-jsonb-c64.tps"}

# Join: first column the metric, then one column per run, in argument order.
files=(); labels=()
for d in "${DIRS[@]}"; do files+=("$d/metrics.csv"); labels+=("$(basename "$d")"); done
joined=$(awk -F, -v n=${#files[@]} '
  FNR == 1 { f++; next }
  { if (!($1 in seen)) { seen[$1] = 1; order[++k] = $1 } v[$1, f] = $2 }
  END { for (i = 1; i <= k; i++) { line = order[i]; for (j = 1; j <= n; j++) line = line "," ((order[i], j) in v ? v[order[i], j] : ""); print line } }' "${files[@]}")

if [ "${ALL:-0}" = 1 ]; then
  (IFS=,; echo "metric,${labels[*]}")
  echo "$joined"
  exit 0
fi
printf '| metric |'; for l in "${labels[@]}"; do printf ' %s |' "$l"; done; echo
printf '| --- |'; for _ in "${labels[@]}"; do printf ' --- |'; done; echo
for k in $HEADLINE; do
  echo "$joined" | awk -F, -v k="$k" -v n=${#labels[@]} '$1 == k { line = "| " k " |"; for (i = 2; i <= n + 1; i++) line = line " " ($i == "" ? "–" : $i) " |"; print line; found = 1 }
    END { if (!found) { line = "| " k " |"; for (i = 1; i <= n; i++) line = line " – |"; print line } }'
done
