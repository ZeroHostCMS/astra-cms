#!/usr/bin/env bash
# End-to-end HTTP benchmark of the kernel on a Linux server with Docker: starts PostgreSQL, Keycloak (project realm),
# Redis and the kernel built from this checkout, then drives the Express order lifecycle with k6 at each rate in
# RATES (orders per second; one order is 11 requests). Writes one k6 summary per rate, container CPU/memory
# samples and machine info to results/<label>/, and a table to results/<label>/summary.txt.
#
#   ./run.sh                          # RATES="25 50 100 150 200 250 300", 60 s each
#   RATES="100 200" DURATION=120s ./run.sh
#
# Environment: BENCH_LABEL, BENCH_NOTE, RATES, DURATION, ASTRA_DB_POOL_MAX (default 48), PG_SHARED_BUFFERS,
# PG_EFFECTIVE_CACHE, KEEP=1 (leave the stack running), BUILD=1 (build the kernel with Maven in Docker first).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
LABEL=${BENCH_LABEL:-$(hostname -s)-$(date +%Y%m%d-%H%M)}
export OUT_DIR="$HERE/results/$LABEL"
RATES=${RATES:-25 50 100 150 200 250 300}
DURATION=${DURATION:-60s}
mkdir -p "$OUT_DIR"
DC=(docker compose -f "$HERE/docker-compose.yml")
log() { echo "[$(date '+%F %T')] $*" | tee -a "$OUT_DIR/run.log"; }

if [ "${BUILD:-0}" = 1 ] || [ ! -f "$ROOT/astra-server/target/quarkus-app/quarkus-run.jar" ]; then
  log "building the kernel (Maven in Docker)"
  docker run --rm -v "$ROOT:/src" -v astra-m2:/root/.m2 -w /src maven:3.9-eclipse-temurin-25 \
    mvn -B -ntp -q -DskipTests package -pl astra-server -am
fi

{
  echo "label: $LABEL"; echo "note: ${BENCH_NOTE:-}"; echo "date_utc: $(date -u '+%F %T')"
  echo "git_commit: $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "cpu_model: $(awk -F': ' '/^model name/{print $2; exit}' /proc/cpuinfo 2>/dev/null)"
  echo "cpus: $(nproc)"; echo "ram_mb: $(( $(awk '/^MemTotal/{print $2}' /proc/meminfo) / 1024 ))"
  echo "product: $(cat /sys/class/dmi/id/product_name 2>/dev/null || true)"
  echo "rates: $RATES"; echo "duration: $DURATION"; echo "db_pool_max: ${ASTRA_DB_POOL_MAX:-48}"
  echo "layout: PostgreSQL, Keycloak, Redis, kernel and k6 on this one machine"
} > "$OUT_DIR/machine.txt"

cleanup() {
  # The full log can reach tens of MB under overload (one stack trace per failed request): keep a summary.
  "${DC[@]}" logs --no-log-prefix kernel > "$OUT_DIR/.kernel.log" 2>&1 || true
  { echo "# first 60 lines"; head -60 "$OUT_DIR/.kernel.log"; echo; echo "# ERROR lines: $(grep -c ' ERROR ' "$OUT_DIR/.kernel.log")"
    grep -E 'Caused by|^[a-z]+\.[a-zA-Z.]+Exception' "$OUT_DIR/.kernel.log" | cut -c1-160 | sort | uniq -c | sort -rn | head -20
    echo; echo "# last 40 lines"; tail -40 "$OUT_DIR/.kernel.log"; } > "$OUT_DIR/kernel-log-summary.txt"
  rm -f "$OUT_DIR/.kernel.log"
  if [ "${KEEP:-0}" = 1 ]; then log "KEEP=1: stack left running (docker compose -f $HERE/docker-compose.yml down -v)"; return; fi
  "${DC[@]}" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "starting PostgreSQL, Keycloak, Redis"
"${DC[@]}" up -d postgres keycloak redis >/dev/null
probe() {   # service path: HTTP status line of GET path on the service's port 8080, from inside its container
  "${DC[@]}" exec -T "$1" bash -c "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET $2 HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3" 2>/dev/null
}
for i in $(seq 1 120); do probe keycloak /auth/realms/astra | grep -q ' 200' && break; sleep 2; [ "$i" = 120 ] && { log "Keycloak not ready"; exit 1; }; done
log "starting the kernel (OIDC discovery needs Keycloak up)"
"${DC[@]}" up -d kernel >/dev/null
for i in $(seq 1 180); do
  probe kernel /q/health/ready | grep -q ' 200' && break
  sleep 2
  [ "$i" = 180 ] && { log "kernel not ready"; "${DC[@]}" logs --tail 80 kernel; exit 1; }
done
log "stack ready"

: > "$OUT_DIR/summary.txt"
for rate in $RATES; do
  log "rate $rate orders/s for $DURATION"
  ( sleep 20; docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' > "$OUT_DIR/stats-rate-$rate.txt" 2>&1 ) &
  "${DC[@]}" run --rm -T -e RATE="$rate" -e DURATION="$DURATION" k6 run --quiet /scripts/order-flow.js > "$OUT_DIR/k6-rate-$rate.txt" 2>&1 || true
  wait
  { echo "== rate $rate"; grep -E '^(rate=|iterations=|create |submit |scan |get |list )' "$OUT_DIR/k6-rate-$rate.txt" || tail -5 "$OUT_DIR/k6-rate-$rate.txt"
    grep -E 'astra-httpbench-(kernel|postgres|keycloak)' "$OUT_DIR/stats-rate-$rate.txt" || true; } | tee -a "$OUT_DIR/summary.txt"
done
log "done: $OUT_DIR/summary.txt"
