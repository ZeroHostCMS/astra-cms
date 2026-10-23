#!/usr/bin/env bash
# DocType JSONB benchmark harness for Linux servers (bare metal or cloud VMs). PostgreSQL runs in Docker from one
# pinned image, so every machine and cloud runs the same build; the container uses the host network (no NAT) and
# can keep its data on the disk under test (DATA_DIR). One command tunes PostgreSQL to the machine (or applies a
# fixed profile), runs the chosen phases and writes every raw output plus machine.txt, metrics.csv and report.md
# to results/<label>/. ./compare.sh puts several runs side by side. See README.md.
#
#   ./bench.sh                    # every phase (about 2.5 h for ROWS=5000000 on 24 threads / NVMe)
#   ./bench.sh quick limits       # lean load + limits only (about 1 h)
#   ./bench.sh load baseline      # any subset, in this order: load|quick baseline fixes bigint limits report
#
# Requirements: Linux, Docker, bash, awk. pgbench and psql run inside the same image.
#
# Environment (all optional):
#   DATA_DIR       host directory for the PostgreSQL data, on the disk you want to measure (default: a Docker
#                  volume named after CONTAINER). Must be empty; deleted at the end unless KEEP=1.
#   PG_IMAGE       default pgvector/pgvector:pg16 (the project's image); its digest is recorded in machine.txt
#   BENCH_LABEL    result folder name (default <hostname>-<yyyymmdd-hhmm>)
#   BENCH_NOTE     free text stored in machine.txt (instance type, region, disk class, price, ...)
#   ROWS           orders to generate (default 5000000; 2000000 is one day at the target load)
#   PROFILE        auto (default): PostgreSQL tuned to this machine's RAM and CPUs
#                  fixed16: the settings of the first 16 GB run on every machine (isolates the hardware)
#   PG_ARGS        extra "-c name=value" server arguments, e.g. "-c synchronous_commit=off"
#   SECS           seconds per pgbench run in the limits phase (default 30)
#   RATES          fixed-rate steps in transactions/s for the latency test (default "7200 10800 14400")
#   RATE_SECS      seconds per fixed-rate run (default 60); WARMUP seconds left out of percentiles (default 10)
#   LIMIT_MODES    limits sub-phases (default "reads writes mixed rate locality sync-off gin")
#   BENCH_PORT     PostgreSQL port on the host network (default 5433)
#   CONTAINER      container (and volume) name (default astra-bench-pg)
#   KEEP           1 keeps the container and its data afterwards (default: both removed at the end)
#   REUSE          1 runs against an existing container of the same name instead of refusing
#   DISK_MIN_GB    refuse to start with less free space for the data (default 60)
#   SKIP_FSYNC_TEST  1 skips pg_test_fsync
#
# Separate client and server (pgbench does not compete with PostgreSQL for CPU): on the database VM keep the
# server running and reachable, KEEP=1 LISTEN=0.0.0.0 BENCH_SU_PASSWORD=<secret> ./bench.sh quick (the image accepts
# password logins from any address: allow only the client VM to reach BENCH_PORT in the cloud firewall); then on
# the client VM: BENCH_HOST=<db-ip> BENCH_SU_PASSWORD=<secret> ./bench.sh limits report (pgbench, same image).
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
LABEL=${BENCH_LABEL:-$(hostname -s)-$(date +%Y%m%d-%H%M)}
OUT="$HERE/results/$LABEL"
WORK="$OUT/.scripts"
ROWS=${ROWS:-5000000}
PROFILE=${PROFILE:-auto}
PG_IMAGE=${PG_IMAGE:-pgvector/pgvector:pg16}
CONTAINER=${CONTAINER:-astra-bench-pg}
SECS=${SECS:-30}
LIMIT_MODES=${LIMIT_MODES:-reads writes mixed rate locality sync-off gin}
DISK_MIN_GB=${DISK_MIN_GB:-60}
LISTEN=${LISTEN:-127.0.0.1}
EXTERNAL=0; [ -n "${BENCH_HOST:-}" ] && EXTERNAL=1
BENCH_HOST=${BENCH_HOST:-127.0.0.1}
BENCH_PORT=${BENCH_PORT:-5433}
BENCH_SU=${BENCH_SU:-postgres}
BENCH_SU_PASSWORD=${BENCH_SU_PASSWORD:-bench}

PHASES=("$@"); [ ${#PHASES[@]} -eq 0 ] && PHASES=(load baseline fixes bigint limits report)

mkdir -p "$OUT/limits" "$WORK/workloads"
log() { echo "[$(date '+%F %T')] $*" | tee -a "$OUT/run.log"; }
die() { log "ERROR: $*"; exit 1; }
[ "$(uname -s)" = Linux ] || die "bench.sh targets Linux servers"
command -v docker >/dev/null || die "docker is required"

# Scripts are copied with LF line endings (a checkout made on Windows has CRLF, which breaks bash and psql \i).
for f in "$HERE"/*.sql "$HERE"/*.sh; do tr -d '\r' < "$f" > "$WORK/$(basename "$f")"; done
for f in "$HERE"/workloads/*.sql; do tr -d '\r' < "$f" > "$WORK/workloads/$(basename "$f")"; done
chmod +x "$WORK"/*.sh

# ------------------------------------------------------------------ machine and PostgreSQL settings
MEM_MB=$(( $(awk '/^MemTotal/{print $2}' /proc/meminfo) / 1024 ))
NCPU=$(nproc)
min() { [ "$1" -lt "$2" ] && echo "$1" || echo "$2"; }
max() { [ "$1" -gt "$2" ] && echo "$1" || echo "$2"; }

case "$PROFILE" in
  auto)
    SB=$(min $(( MEM_MB / 4 )) 16384); ECS=$(( MEM_MB * 70 / 100 )); MWM=$(min $(( MEM_MB / 20 )) 2048)
    PW=$(max $(( NCPU / 2 )) 2); PWG=$(max "$(min $(( NCPU / 4 )) 4)" 1)
    PG_SETTINGS="-c shared_buffers=${SB}MB -c effective_cache_size=${ECS}MB -c maintenance_work_mem=${MWM}MB -c work_mem=32MB
      -c max_connections=400 -c wal_buffers=64MB -c max_wal_size=16GB -c min_wal_size=2GB -c checkpoint_timeout=15min
      -c checkpoint_completion_target=0.9 -c wal_compression=lz4 -c random_page_cost=1.1 -c effective_io_concurrency=200
      -c max_worker_processes=$NCPU -c max_parallel_workers=$PW -c max_parallel_workers_per_gather=$PWG
      -c max_parallel_maintenance_workers=$PWG -c autovacuum_max_workers=4 -c autovacuum_naptime=10s
      -c autovacuum_vacuum_cost_limit=2000"
    SHM_MB=$(min "$SB" 8192) ;;
  fixed16)
    PG_SETTINGS="-c shared_buffers=4GB -c effective_cache_size=12GB -c maintenance_work_mem=2GB -c work_mem=64MB
      -c max_connections=400 -c max_wal_size=8GB -c checkpoint_completion_target=0.9 -c wal_compression=on
      -c random_page_cost=1.1 -c max_parallel_workers_per_gather=4 -c max_parallel_maintenance_workers=4"
    SHM_MB=4096 ;;
  *) die "PROFILE must be auto or fixed16" ;;
esac
PG_SETTINGS="$PG_SETTINGS -c listen_addresses=$LISTEN -c track_io_timing=on
  -c shared_preload_libraries=pg_stat_statements -c log_checkpoints=on -c log_autovacuum_min_duration=0
  -c log_line_prefix=%m ${PG_ARGS:-}"

data_path() {   # where the data lives on the host, for free-space checks
  if [ -n "${DATA_DIR:-}" ]; then local d=$DATA_DIR; while [ ! -e "$d" ]; do d=$(dirname "$d"); done; echo "$d"
  else docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo /; fi
}
free_gb() {   # 999999 when the data path cannot be inspected from here (e.g. Docker's root dir not visible)
  local g; g=$(df -Pk "$(data_path)" 2>/dev/null | awk 'NR==2{print int($4/1024/1024)}')
  echo "${g:-999999}"
}

machine_info() {
  local fs dev
  fs=$(data_path); dev=$(df -P "$fs" 2>/dev/null | awk 'NR==2{print $1}' || true)
  {
    echo "label: $LABEL"
    echo "note: ${BENCH_NOTE:-}"
    echo "date_utc: $(date -u '+%F %T')"
    echo "git_commit: $(git -C "$HERE" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "kernel: $(uname -sr)"
    echo "os: $(. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME")"
    echo "vendor: $(cat /sys/class/dmi/id/sys_vendor 2>/dev/null || true)"
    echo "product: $(cat /sys/class/dmi/id/product_name 2>/dev/null || true)"
    echo "cpu_model: $(awk -F': ' '/^model name/{print $2; exit}' /proc/cpuinfo)"
    echo "cpus: $NCPU"
    echo "cpu_governor: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo n/a)"
    echo "ram_mb: $MEM_MB"
    echo "transparent_hugepages: $(cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null || echo n/a)"
    echo "docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
    echo "pg_image: $PG_IMAGE $(docker image inspect --format '{{index .RepoDigests 0}}' "$PG_IMAGE" 2>/dev/null)"
    echo "server: $([ $EXTERNAL = 1 ] && echo "external $BENCH_HOST:$BENCH_PORT" || echo "container $CONTAINER, host network, data ${DATA_DIR:-in volume $CONTAINER}")"
    echo "rows: $ROWS"
    echo "profile: $PROFILE"
    echo "pg_settings: $(echo $PG_SETTINGS)"
    echo "data_device: $dev"
    echo "--- disks (ROTA 0 = SSD/NVMe)"
    lsblk -d -o NAME,ROTA,SIZE,MODEL 2>/dev/null || true
    echo "--- filesystem holding the data"
    df -hT "$fs" 2>/dev/null | tail -1 || echo "(not visible from here: $fs)"
  } > "$OUT/machine.txt"
}

# ------------------------------------------------------------------ container
data_mount() { if [ -n "${DATA_DIR:-}" ]; then echo "$DATA_DIR:/var/lib/postgresql/data"; else echo "$CONTAINER:/var/lib/postgresql/data"; fi; }

fsync_test() {
  # Commit latency is what bounds write throughput at low concurrency; measured on the data disk, same binary.
  [ $EXTERNAL = 1 ] || [ "${SKIP_FSYNC_TEST:-0}" = 1 ] && return 0
  docker run --rm -v "$(data_mount)" --entrypoint bash "$PG_IMAGE" -c \
    'f=/var/lib/postgresql/data/.bench_fsync_test; $(ls -d /usr/lib/postgresql/*/bin | tail -1)/pg_test_fsync -s 2 -f $f; rm -f $f' \
    > "$OUT/pg_test_fsync.txt" 2>&1 || true
}

start_db() {
  if docker container inspect "$CONTAINER" >/dev/null 2>&1; then
    [ "${REUSE:-0}" = 1 ] || die "container $CONTAINER exists; remove it or set REUSE=1"
    docker start "$CONTAINER" >/dev/null
  else
    if [ -n "${DATA_DIR:-}" ]; then mkdir -p "$DATA_DIR"; [ -z "$(ls -A "$DATA_DIR")" ] || die "$DATA_DIR is not empty"; fi
    fsync_test
    # PGPORT, not -c port: the image's init scripts talk to their temporary server on that port.
    # shellcheck disable=SC2086
    docker run -d --name "$CONTAINER" --network host --shm-size="${SHM_MB}m" --ulimit nofile=65536:65536 \
      -e POSTGRES_PASSWORD="$BENCH_SU_PASSWORD" -e POSTGRES_USER="$BENCH_SU" -e POSTGRES_DB=bench -e PGPORT="$BENCH_PORT" \
      -v "$(data_mount)" -v "$WORK:/bench" -v "$OUT:/out" \
      "$PG_IMAGE" postgres $PG_SETTINGS >/dev/null
  fi
  for _ in $(seq 1 120); do docker exec "$CONTAINER" pg_isready -h 127.0.0.1 -p "$BENCH_PORT" -q 2>/dev/null && return 0; sleep 1; done
  die "PostgreSQL did not start: $(docker logs --tail 20 "$CONTAINER" 2>&1)"
}
stop_db() {
  [ $EXTERNAL = 1 ] && return
  docker logs "$CONTAINER" > "$OUT/postgres.log" 2>&1 || true
  if [ "${KEEP:-0}" = 1 ]; then log "KEEP=1: container $CONTAINER and its data are kept"; return; fi
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  if [ -n "${DATA_DIR:-}" ]; then
    # Files belong to the container's postgres user; delete them from a container, then the empty directory.
    docker run --rm -v "$DATA_DIR:/d" --entrypoint sh "$PG_IMAGE" -c 'rm -rf /d/* /d/.[!.]* 2>/dev/null; true'
    rmdir "$DATA_DIR" 2>/dev/null || true
  else
    docker volume rm "$CONTAINER" >/dev/null 2>&1 || true
  fi
  log "container and its data removed (KEEP=1 keeps them)"
}

# psql and the runner scripts run inside the server container, or in a client container of the same image
# (host network) when the server is external.
ENVS=(-e BENCH_HOST="$BENCH_HOST" -e BENCH_PORT="$BENCH_PORT" -e BENCH_DB=bench -e BENCH_SU="$BENCH_SU"
      -e BENCH_SU_PASSWORD="$BENCH_SU_PASSWORD" -e PGPASSWORD="$BENCH_SU_PASSWORD" -e SECS="$SECS" -e CLIENTS=16)
[ -n "${RATES:-}" ] && ENVS+=(-e RATES="$RATES")
for v in WARMUP RATE_SECS THREADS; do [ -n "${!v:-}" ] && ENVS+=(-e "$v=${!v}"); done
in_pg() {
  if [ $EXTERNAL = 0 ]; then docker exec -w /bench "${ENVS[@]}" "$@" "$CONTAINER" "${CMD[@]}"
  else docker run --rm --network host -w /bench -v "$WORK:/bench" -v "$OUT:/out" "${ENVS[@]}" "$@" --entrypoint "${CMD[0]}" "$PG_IMAGE" "${CMD[@]:1}"; fi
}
P()  { CMD=(psql -h "$BENCH_HOST" -p "$BENCH_PORT" -U "$BENCH_SU" -d bench -X -v ON_ERROR_STOP=1 "$@"); in_pg; }
X()  { CMD=("$@"); in_pg -e OUT=/out; }
XL() { CMD=(./run-limits.sh "$@"); in_pg -e OUT=/out/limits; }

stage() {
  local name=$1; shift
  local free=""; [ $EXTERNAL = 0 ] && free=$(free_gb)
  log "START $name${free:+ (free disk ${free} GB)}"
  [ -n "$free" ] && [ "$free" -lt 10 ] && die "less than 10 GB free for the data, stopping before $name"
  local t0=$SECONDS
  "$@" || die "$name failed (see $OUT)"
  log "END   $name in $(( SECONDS - t0 )) s"
}
to() { local f=$1; shift; "$@" > "$f" 2>&1; }

phase_load() {
  stage schema          P -f 01-schema.sql
  stage load            to "$OUT/load-timing.txt"  P -v rows="$ROWS" -f 02-load.sql
  stage indexes         to "$OUT/index-timing.txt" P -f 03-indexes.sql
  stage sizes-baseline  to "$OUT/sizes-baseline.txt" P -f 90-sizes.sql
}
phase_quick() {
  stage schema          P -f 01-schema.sql
  stage lean-load       to "$OUT/load-timing.txt" P -v rows="$ROWS" -f 06-lean-reload.sql
}
phase_baseline() {
  stage queries         X ./run-queries.sh
  stage writes          X ./run-writes.sh all
  stage superuser-ref   to "$OUT/superuser-reference.txt" P -f 08-superuser-reference.sql
}
phase_fixes() {
  stage fixes           to "$OUT/fixes-timing.txt" P -f 04-fixes.sql
  stage sizes-fixes     to "$OUT/sizes-fixes.txt" P -f 90-sizes.sql
  stage fixed-queries   X ./run-fixed-queries.sh
  stage leakproof-check to "$OUT/leakproof-inline.txt" P -f 07-leakproof-inline-check.sql
  stage range-check     to "$OUT/range-leakproof.txt" P -f 09-range-leakproof-check.sql
}
phase_bigint() {
  stage bigint          to "$OUT/bigint-timing.txt" P -f 05-bigint-amounts.sql
  stage sizes-bigint    to "$OUT/sizes-bigint.txt" P -f 90-sizes.sql
  stage bigint-queries  X ./run-bigint-queries.sh
  stage reads-fixed     X ./run-writes.sh reads-fixed
}
phase_limits() {
  stage limits-setup    to "$OUT/limits/setup.txt" P -f 10-limits-setup.sql
  stage partial-indexes to "$OUT/limits/partial-indexes.txt" P -f 11-partial-projection-indexes.sql
  stage sizes-limits    to "$OUT/limits/sizes.txt" P -f 90-sizes.sql
  for m in $LIMIT_MODES; do stage "limits-$m" XL "$m"; done
}
phase_report() { stage report "$HERE/summarize.sh" "$OUT"; }

# ------------------------------------------------------------------ run
: > "$OUT/run.log"
docker pull -q "$PG_IMAGE" >/dev/null || log "could not pull $PG_IMAGE, using the local copy"
machine_info
if [ $EXTERNAL = 0 ]; then
  free=$(free_gb)
  [ "$free" -ge "$DISK_MIN_GB" ] || die "only ${free} GB free for the data, need $DISK_MIN_GB (DISK_MIN_GB, DATA_DIR)"
fi
log "label=$LABEL rows=$ROWS profile=$PROFILE phases=${PHASES[*]} results=$OUT"
needs_db=0; for p in "${PHASES[@]}"; do [ "$p" != report ] && needs_db=1; done
if [ $needs_db = 1 ]; then
  if [ $EXTERNAL = 0 ]; then start_db; trap stop_db EXIT; fi
  P -Atc "SELECT version()" >> "$OUT/machine.txt"
  P -Atc "SELECT name || ' = ' || setting || coalesce(unit, '') FROM pg_settings WHERE source NOT IN ('default', 'override') ORDER BY 1" > "$OUT/pg-settings.txt"
fi
for p in "${PHASES[@]}"; do
  case "$p" in
    load|quick|baseline|fixes|bigint|limits|report) "phase_$p" ;;
    *) die "unknown phase $p (load quick baseline fixes bigint limits report)" ;;
  esac
done
[[ " ${PHASES[*]} " == *" report "* ]] || "$HERE/summarize.sh" "$OUT"
rm -rf "$WORK"
log "done: $OUT/report.md"
