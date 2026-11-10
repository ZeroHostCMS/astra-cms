#!/usr/bin/env bash
# Video 22, "software that evolves": the three plugin tiers on the k3d cluster (2 pods), scene by scene.
#   scripts/demo-tiers.sh [BASE] [SCENE...]     scenes: T0 T0b T1a T1b T2a T2b (default: all, in order)
# PAUSE=0 runs straight through. Needs scripts/demo-seed.sh first; this script writes the 30-day history itself and
# builds the plugin jars if they are missing. Each scene leaves what later scenes build on (the rule set of T0b is
# what Shop ABC's plugin inherits in T2a), so run them in order on a fresh seed.
# The AI sessions themselves are recorded separately (docs/video/v22-tiers); T2a and T2b replay what follows them:
# the AI token proposes (release_propose), cannot approve (403), an admin approves, traffic shows the result.
if (( BASH_VERSINFO[0] < 5 )); then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [[ -x $b ]] && exec "$b" "$0" "$@"; done
  echo "Bash 5+ is required: brew install bash" >&2; exit 1
fi
set -uo pipefail
BASE="${1:-http://localhost:8080}"; shift || true
SCENES=("$@"); [[ ${#SCENES[@]} -eq 0 ]] && SCENES=(T0 T0b T1a T1b T2a T2b)
PAUSE="${PAUSE:-1}"
T=saigon-express
cd "$(dirname "$0")/.."
export PATH="$HOME/.local/bin:$PATH"
source scripts/lib/demo-common.sh
AS=mai
PLUGINS=astra-apps/express-integrations/plugins
MODULES=astra-apps/express/wasm/modules
RULES=astra-apps/express/src/main/resources/META-INF/astra/rules
LOGS="${LOGS:-${TMPDIR:-/tmp}/astra-demo-tiers}"
mkdir -p "$LOGS"

title() { printf '\n\033[1;35m━━━ Scene %s · %s ━━━\033[0m\n' "$1" "$2"; }
say()   { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
wait_key() { [[ $PAUSE == 1 ]] && read -rsp $'\033[2m  [Enter]\033[0m' _ && echo; return 0; }
as() { AS=$1; say "Signed in as ${1}"; }

# api TENANT METHOD PATH [JSON] [JQ] — as user $AS: shows the call, status, X-Astra-Pod and the (filtered) JSON body
api() {
  local tenant=$1 method=$2 path=$3 body=${4:-} filter=${5:-.}
  # GET and the lifecycle POSTs (submit, cancel) carry no body: their fourth argument is the jq filter
  if [[ $method == GET || $path == */submit || $path == */cancel ]] && (( $# == 4 )); then filter=$body; body=""; fi
  local shown="$body"; (( ${#shown} > 160 )) && shown="${shown:0:150}…\"}"
  printf '\033[2m$ curl -X %s %s%s -H "Authorization: Bearer <%s>" -H "X-Astra-Tenant: %s"%s\033[0m\n' "$method" "$BASE" "$path" "$AS" "$tenant" "${shown:+ -d '$shown'}"
  login "$AS"
  local out
  out="$(curl -sS -w $'\n%{http_code} %header{x-astra-pod}' -X "$method" "${BASE}${path}" -H "Authorization: Bearer ${TOKENS[$AS]}" \
    -H "X-Astra-Tenant: ${tenant}" ${body:+-H 'Content-Type: application/json' --data-binary "$body"})"
  local tail="${out##*$'\n'}"; HTTP_CODE="${tail%% *}"; POD="${tail#* }"; HTTP_BODY="${out%$'\n'*}"
  if [[ $HTTP_CODE == 2* ]]; then printf '\033[1;32mHTTP %s\033[0m' "$HTTP_CODE"; else printf '\033[1;31mHTTP %s\033[0m' "$HTTP_CODE"; fi
  printf '   \033[2mX-Astra-Pod: %s\033[0m\n' "$POD"
  [[ $HTTP_CODE == 2* ]] || filter=.
  [[ -n $HTTP_BODY ]] && { jq -C "$filter" <<<"$HTTP_BODY" 2>/dev/null || echo "$HTTP_BODY"; }
  LAST="$HTTP_BODY"
}
b64() { base64 < "$1" | tr -d '\n'; }
json_str() { jq -Rs . < "$1"; }

# release TENANT SLOT TIER FILE [EXTRA_JSON] — saves an AppRelease from a file; prints its name in RELEASE
release() {
  local extra="${5:-}"
  api "$1" POST /api/doc/AppRelease "{\"slot\":\"$2\",\"tier\":\"$3\",\"promote_after\":${PROMOTE_AFTER:-10},\"artifact\":\"$(b64 "$4")\"${extra:+,$extra}}" \
    '{name, app: .data.app_id, version: .data.version, stage: .data.stage, generator: .data.generator, self_test: .data.self_test, sha256: .data.artifact_sha256[0:16]}'
  RELEASE=$(jq -r .name <<<"$LAST")
}
# approve TENANT RELEASE — the admin's submit; then one read interval so every pod has re-read the stages
approve() {
  api "$1" POST "/api/doc/AppRelease/$2/submit" '{name, stage: .data.stage, canary_pods: .data.canary_pods, pods: .data.target_pods, signed_by: .data.signed_by, why: .data.stage_reason}'
  sleep 3
}
stage() { api "$1" GET "/api/doc/AppRelease/$2" '{name, app: .data.app_id, version: .data.version, stage: .data.stage, canary_pods: .data.canary_pods, pods: .data.target_pods, calls: .data.calls, errors: .data.errors, p99_ms: .data.p99_ms, why: .data.stage_reason}'; }
# wait_stage TENANT RELEASE STAGE [SECONDS] — polls until the rollout job moves the release to STAGE
wait_stage() {
  local i
  for (( i = 0; i < ${4:-90}; i++ )); do
    http "$AS" "$1" GET "/api/doc/AppRelease/$2"
    [[ $(jq -r .data.stage <<<"$HTTP_BODY") == "$3" ]] && break
    sleep 1
  done
  stage "$1" "$2"
}

# traffic_start NAME [ENV...] — demo traffic in the background, its log in $LOGS/NAME.log; traffic_stop NAME prints the tail
traffic_start() {
  local name=$1; shift
  ( env "$@" scripts/demo-traffic.sh "$BASE" > "$LOGS/$name.log" 2>&1 ) &
  TRAFFIC_PID=$!
  say "Traffic running in the background ($*), log $LOGS/$name.log"
}
traffic_stop() {
  kill -TERM "$TRAFFIC_PID" 2>/dev/null; wait "$TRAFFIC_PID" 2>/dev/null
  tail -n "${2:-12}" "$LOGS/$1.log"
}
order() { # TENANT JSON -> submits, leaves the order in LAST
  api "$1" POST /api/doc/CustomerOrder "$2" '{name, shipping_fee: .data.shipping_fee, priced_by: .data.priced_by}'
  local o; o=$(jq -r .name <<<"$LAST")
  api "$1" POST "/api/doc/CustomerOrder/$o/submit" "{name, waybill: .data.waybill, shipping_fee: .data.shipping_fee, price_breakdown: [.data.price_breakdown[] | {code, amount, source} + (if .op then {op, before, reason} else {} end)]}"
  ORDER_NAME=$o; WAYBILL=$(jq -r .data.waybill <<<"$LAST")
}
kernel_log() { kubectl -n astra logs -l app=astra-server --since="${2:-120s}" --prefix --tail=-1 2>/dev/null | grep -E "$1" | sed -E 's/^\[pod\/([^/]+)\/[^]]+\]/\1/' | tail -n "${3:-8}"; }
redis_cli() { kubectl -n astra exec -i deploy/redis -- redis-cli "$@" 2>/dev/null; }

# The courier's daily pickup pool (Seata TCC, 200 slots/day, shown in video 17) is a real limit, not this video's point:
# the background load here would exhaust it and swamp the hot-swap signal with "no pickup slot" errors. Keep today's
# pool open for the duration of the demo by clearing its Redis counters every few seconds; it is restored on exit.
PICKUP_DAY=$(TZ=Asia/Ho_Chi_Minh date +%F)
pickup_pool_open() {
  ( while :; do redis_cli MSET "astra:$T:pickup:$PICKUP_DAY:reserved" 0 "astra:$T:pickup:$PICKUP_DAY:booked" 0 >/dev/null; sleep 3; done ) &
  PICKUP_PID=$!
}
pickup_pool_restore() {
  [[ -n ${PICKUP_PID:-} ]] && kill "$PICKUP_PID" 2>/dev/null
  redis_cli MSET "astra:$T:pickup:$PICKUP_DAY:reserved" 0 "astra:$T:pickup:$PICKUP_DAY:booked" 0 >/dev/null
}

prepare() {
  [[ -f $PLUGINS/fastship-1.0.0.jar ]] || scripts/build-plugins.sh
  http demo "$T" GET "/api/doc/CustomerOrder/ORD-H-0000001"
  if [[ $HTTP_CODE != 200 ]]; then say "Writing 30 days of history (Shop ABC orders, hub weights, COD payouts, couriers)"; scripts/demo-seed-history.sh; fi
  pickup_pool_open
  trap pickup_pool_restore EXIT
}

ORDER_OTHER='{"customer":"C010","receiver_name":"Lê Văn Demo","receiver_phone":"0901234567","receiver_address":"Xã Đồng Văn, Hà Giang","zone":"Remote","service":"Standard","weight_kg":2,"cod_amount":320000,"length_cm":40,"width_cm":30,"height_cm":30}'
ORDER_ABC="${ORDER_OTHER/C010/ABC}"

sceneT0() {
  title T0 "Lớp 1 · Tier 0: the core is compiled in, only Tier 0 writes the books"
  say "ExpressApp and OrderPostingHook are compiled into the server image (build time, direct calls)"
  sed -n '/^@ApplicationScoped/,/^}/p' astra-apps/express/src/main/java/io/astra/apps/express/ExpressApp.java | head -12
  grep -n 'ledger.post' -A3 astra-apps/express/src/main/java/io/astra/apps/express/OrderPostingHook.java; wait_key
  say "Submitting an order posts the shipping fee to the general ledger, in the order's own transaction"
  order $T '{"customer":"C001","receiver_name":"Lê Văn Demo","receiver_phone":"0901234567","receiver_address":"1 Bạch Đằng, Đà Nẵng","zone":"Domestic","service":"Standard","weight_kg":1.5,"cod_amount":0}'
  api $T GET "/api/ledger/entries?voucher_no=$ORDER_NAME" '[.[] | {account, party, debit, credit}]'; wait_key
  say "A Tier 1 plugin gets no DocumentService and no GeneralLedger: this is everything it receives"
  sed -n '/^public interface PluginContext/,/^}/p' astra-spi/src/main/java/io/astra/spi/app/PluginContext.java | grep -v '^\s*/\?\*'; wait_key
  say "Changing the core = a new image and a rolling update of both pods, while traffic runs"
  traffic_start t0 MODE=orders RATE=4 QUIET=1
  sleep 4
  kubectl -n astra rollout restart deploy/astra-server
  kubectl -n astra rollout status deploy/astra-server --timeout=300s
  sleep 6
  kubectl -n astra get pods -l app=astra-server
  traffic_stop t0 3; wait_key
}

sceneT0b() {
  title T0b "Lớp 1 · Tier 0: price rules are data (Drools), edited on the Desk, approved like code"
  say "Before: the tariff was hard-coded in Java (main branch)"
  git show main:astra-apps/express/src/main/java/io/astra/apps/express/ShippingFeeHook.java 2>/dev/null | grep -n -A4 'TARIFF = Map.of' | head -6
  say "Now: the same table as rules (rules/standard-tariff.drl)"
  grep -n -A6 'rule "base-remote"' $RULES/standard-tariff.drl; wait_key
  as mai
  say "The business analyst saves the standard tariff as a rule set: every word is checked, the rules compile"
  api $T POST /api/doc/PricingRuleset "{\"ruleset_name\":\"standard-tariff\",\"version\":\"1.0.0\",\"rules\":$(json_str $RULES/standard-tariff.drl)}" \
    '{name, rule_count: .data.rule_count, self_test: .data.self_test}'
  local rs; rs=$(jq -r .name <<<"$LAST")
  api $T POST "/api/doc/PricingRuleset/$rs/submit" '{name, release: .data.release}'
  approve $T "$(jq -r .data.release <<<"$LAST")"; wait_key
  say "Same fee as the hard-coded tariff, now line by line, each line naming its rule"
  order $T "$ORDER_OTHER"; wait_key

  say "The analyst adds a peak surcharge for 11.11. First two mistakes, refused on save, with the reason"
  local peak="$LOGS/peak.drl" today; today=$(date +%m-%d)
  cp $RULES/peak-1111.drl "$peak"
  if [[ ! $today =~ ^11-1[0-2]$ ]]; then   # recorded outside 10-12 November: the same rule, dated today
    sed -i.bak -E "s/month == 11, day >= 10, day <= 12/month == $((10#$(date +%m))), day == $((10#$(date +%d)))/; s/Phụ phí cao điểm 11.11/Phụ phí cao điểm (demo)/" "$peak"
  fi
  local bad1="$LOGS/bad-syntax.drl" bad2="$LOGS/bad-exit.drl"
  { cat $RULES/standard-tariff.drl; sed 's/QuoteRequest(zone == "Remote"/QuoteRequest(zone == "Remote"(/' "$peak"; } > "$bad1"
  { cat $RULES/standard-tariff.drl; sed 's/lines.lock("peak");/lines.lock("peak"); System.exit(0);/' "$peak"; } > "$bad2"
  api $T POST /api/doc/PricingRuleset "{\"ruleset_name\":\"standard-tariff\",\"version\":\"1.1.0\",\"rules\":$(json_str "$bad1")}" '.errors'
  api $T POST /api/doc/PricingRuleset "{\"ruleset_name\":\"standard-tariff\",\"version\":\"1.1.0\",\"rules\":$(json_str "$bad2")}" '.errors'; wait_key
  say "The correct rule: saved, submitted, approved by the admin; a canary on one pod while traffic runs"
  cat $RULES/standard-tariff.drl "$peak" > "$LOGS/standard-tariff-1.1.0.drl"
  api $T POST /api/doc/PricingRuleset "{\"ruleset_name\":\"standard-tariff\",\"version\":\"1.1.0\",\"rules\":$(json_str "$LOGS/standard-tariff-1.1.0.drl")}" \
    '{name, rule_count: .data.rule_count, self_test: .data.self_test}'
  rs=$(jq -r .name <<<"$LAST")
  api $T POST "/api/doc/PricingRuleset/$rs/submit" '{name, release: .data.release}'
  local rel; rel=$(jq -r .data.release <<<"$LAST")
  traffic_start t0b MODE=orders RATE=5
  approve $T "$rel"
  wait_stage $T "$rel" Live 120
  traffic_stop t0b 6
  say "A Remote order now carries the surcharge line, from the new rule; no pod restarted"
  order $T "$ORDER_OTHER"
  kubectl -n astra get pods -l app=astra-server; wait_key
}

sceneT1a() {
  title T1a "Lớp 2 · Tier 1: a carrier plugin swapped while running, canary by pod, automatic rollback"
  say "FastShip 1.0.0: a jar with a manifest; it may reach the partner sandbox and nothing else"
  unzip -p $PLUGINS/fastship-1.0.0.jar META-INF/astra-app.yaml
  as mai
  release $T carrier.booking isolated-jvm $PLUGINS/fastship-1.0.0.jar
  approve $T "$RELEASE"; wait_key
  traffic_start t1a MODE=orders RATE=4
  sleep 8
  say "FastShip 1.1.0 (2-hour service in the city, ETA): approved, it goes to one pod first"
  PROMOTE_AFTER=30 release $T carrier.booking isolated-jvm $PLUGINS/fastship-1.1.0.jar   # a canary window long enough to watch
  local v11=$RELEASE
  approve $T "$v11"
  sleep 8
  say "Traffic: the canary pod books with 1.1.0, the other pod still with 1.0.0"
  grep -E 'fastship@' "$LOGS/t1a.log" | tail -6
  wait_stage $T "$v11" Live 120
  say "On every pod: 1.1.0 is Live, 1.0.0 is retired, drained and unloaded"
  grep -E 'fastship@1.1.0' "$LOGS/t1a.log" | tail -3
  # The old version is drained, then its class loader is collected a few GC cycles later (the rollout job nudges it),
  # so give it a moment before reading the kernel log for the "collected" line.
  for _ in $(seq 10); do kernel_log 'classloader fastship@1.0.0 collected' 240s 1 | grep -q collected && break; sleep 2; done
  kernel_log 'classloader fastship@1.0.0 collected|unloaded after drain|PartnerHttp denied' 240s 6; wait_key

  say "Hanoi Express keeps 1.0.0: its own release, pinned for the tenant"
  as nam
  release hanoi-express carrier.booking isolated-jvm $PLUGINS/fastship-1.0.0.jar
  approve hanoi-express "$RELEASE"
  api hanoi-express POST /api/doc/AppPin '{"slot":"carrier.booking","app_id":"fastship","version":"1.0.0","note":"giữ 1.0 tới khi đối soát xong"}' '{name, slot: .data.slot, pinned: (.data.app_id + "@" + .data.version)}'
  as mai; wait_key

  say "FastShip 1.2.0-bad loses the Remote zone. On the canary pod its errors exceed the budget: rolled back by itself"
  release $T carrier.booking isolated-jvm "$PLUGINS/fastship-1.2.0-bad.jar"
  local bad=$RELEASE
  approve $T "$bad"
  wait_stage $T "$bad" "Rolled back" 120
  sleep 6
  traffic_stop t1a 8
  say "Bookings that failed on the bad build stay pending and are booked by 1.1.0 on the next tick"
  api $T GET "/api/doc/Waybill?limit=6" '[.[] | {name, carrier_status: .data.carrier_status, booked_by: .data.booked_by}]'; wait_key
}

sceneT1b() {
  title T1b "Lớp 2 · Tier 1: e-invoices per country, a new country is a new module"
  as mai
  release $T einvoice.issue isolated-jvm $PLUGINS/einvoice-vn-1.0.0.jar
  approve $T "$RELEASE"
  api $T POST /api/doc/AppPin '{"slot":"einvoice.issue","app_id":"einvoice-vn","version":"1.0.0"}' '{name, pinned: (.data.app_id + "@" + .data.version)}'
  order $T '{"customer":"C001","receiver_name":"Lê Văn Demo","receiver_phone":"0901234567","receiver_address":"1 Lê Lợi","zone":"Domestic","service":"Standard","weight_kg":1,"cod_amount":0}'
  local vn=$ORDER_NAME; wait_key
  as sophea
  say "Angkor Express, a new Cambodian tenant: no invoice module yet, the order waits"
  order angkor-express '{"customer":"K001","receiver_name":"Sok Dara","receiver_phone":"0912345678","receiver_address":"Street 51, Phnom Penh","zone":"Domestic","service":"Standard","weight_kg":1,"cod_amount":0}'
  local kh=$ORDER_NAME
  sleep 4
  api angkor-express GET "/api/doc/CustomerOrder/$kh" '{name, einvoice_status: .data.einvoice_status}'; wait_key
  say "Upload and approve einvoice-kh, pin it for the tenant: the waiting order gets its invoice, no restart"
  release angkor-express einvoice.issue isolated-jvm $PLUGINS/einvoice-kh-1.0.0.jar
  approve angkor-express "$RELEASE"
  api angkor-express POST /api/doc/AppPin '{"slot":"einvoice.issue","app_id":"einvoice-kh","version":"1.0.0"}' '{name, pinned: (.data.app_id + "@" + .data.version)}'
  sleep 6
  api angkor-express GET "/api/doc/CustomerOrder/$kh" '{name, einvoice_status: .data.einvoice_status, einvoice_no: .data.einvoice_no, einvoice_by: .data.einvoice_by}'
  as mai
  api $T GET "/api/doc/CustomerOrder/$vn" '{name, einvoice_status: .data.einvoice_status, einvoice_no: .data.einvoice_no, einvoice_by: .data.einvoice_by}'; wait_key
}

# mcp USER TOOL ARGS... — the same call Claude Code makes, through scripts/astra-mcp.sh as USER
mcp() {
  local user=$1; shift
  login "$user"
  local session="$LOGS/mcp-$user.env"
  ( umask 077; printf 'ASTRA_BASE=%q\nASTRA_TENANT=%q\nASTRA_TOKEN=%q\n' "$BASE" "$T" "${TOKENS[$user]}" > "$session" )
  printf '\033[2m$ scripts/astra-mcp.sh %s   # as %s\033[0m\n' "$*" "$user"
  LAST=$(ASTRA_MCP_SESSION="$session" scripts/astra-mcp.sh "$@")
  jq -C "${MCP_FILTER:-.}" <<<"$LAST"
}

sceneT2a() {
  title T2a "Lớp 3 · Tier 2: Khai giá Astra, Shop ABC's contract as a plugin that inherits the rule price"
  say "What the AI read: Shop ABC's profile over 30 days (seed data)"
  MCP_FILTER='{orders_30_days, return_rate, orders_by_zone, bulky_share}' mcp aibot customer_pricing_profile customer=ABC
  say "The backtest of the module it wrote, on last month's orders"
  MCP_FILTER='{orders, revenue_rule_layer_vnd, revenue_with_module_vnd, difference_pct, operations}' mcp aibot quote_backtest customer=ABC module_base64@=$MODULES/khaigia-shopabc-1.0.0.wasm
  say "It proposes the release for this customer only, and cannot approve it"
  MCP_FILTER='{release, stage, generator, self_test}' mcp aibot release_propose slot=pricing.customer tier=wasm app_id=khaigia-shopabc version=1.0.0 customer=ABC \
    "notes=Phụ lục Shop ABC v1; backtest -2.9%" artifact_base64@=$MODULES/khaigia-shopabc-1.0.0.wasm
  local rel; rel=$(jq -r .release <<<"$LAST")
  as aibot
  api $T POST "/api/doc/AppRelease/$rel/submit" '.errors'
  as mai
  approve $T "$rel"; wait_key
  say "Shop ABC's order: the rule lines, and what the plugin changed on them, with the clause"
  order $T "$ORDER_ABC"
  say "Another customer, same parcel: the rule price only"
  order $T "$ORDER_OTHER"; wait_key
  say "Annex v2 (the 11.11 week keeps the best tier): a few lines changed, hot-reloaded while traffic runs"
  diff <(sed -n '/fn adjust/,/^}/p' astra-apps/express/wasm/khaigia-shopabc/src/lib.rs | grep -v 'cfg!(feature' ) \
       <(sed -n '/fn adjust/,/^}/p' astra-apps/express/wasm/khaigia-shopabc/src/lib.rs) | head -8
  traffic_start t2a MODE=orders RATE=4 CUSTOMER=ABC,C010
  MCP_FILTER='{release, stage, generator}' mcp aibot release_propose slot=pricing.customer tier=wasm app_id=khaigia-shopabc version=1.1.0 customer=ABC \
    "notes=Phụ lục Shop ABC v2: tuần 11.11 giữ bậc cao nhất" artifact_base64@=$MODULES/khaigia-shopabc-1.1.0.wasm
  rel=$(jq -r .release <<<"$LAST")
  approve $T "$rel"
  wait_stage $T "$rel" Live 120
  traffic_stop t2a 6
  order $T "$ORDER_ABC"; wait_key
}

sceneT2b() {
  title T2b "Lớp 3 · Tier 2: the AI's anomaly detector, approved by a person, alerts to the right people"
  say "The backtest of the detector the AI wrote, on 30 days of events with planted, labelled anomalies (seed data)"
  MCP_FILTER='{events, by_kind}' mcp aibot anomaly_backtest module_base64@=$MODULES/anomaly-ops-1.0.0.wasm
  MCP_FILTER='{release, stage, generator, self_test}' mcp aibot release_propose slot=ops.anomaly tier=wasm app_id=anomaly-ops version=1.0.0 \
    "notes=lệch cân ở hub, COD chi thiếu, bưu tá giao thất bại nhiều" artifact_base64@=$MODULES/anomaly-ops-1.0.0.wasm
  local rel; rel=$(jq -r .release <<<"$LAST")
  as aibot
  api $T POST "/api/doc/AppRelease/$rel/submit" '.errors'
  as mai
  approve $T "$rel"; wait_key
  say "Parcels move through the hubs: events scored by the detector"
  traffic_start t2b MODE=ops RATE=3
  sleep 25
  as khoa
  api $T POST /api/doc/CODRemittance "{\"customer\":\"C001\",\"amount\":100000,\"collected_until\":\"$(date -v-2d +%F 2>/dev/null || date -d '2 days ago' +%F)\"}" '{name, outstanding: .data.outstanding, amount: .data.amount}'
  traffic_stop t2b 6
  local who
  for who in quang khoa ha; do
    say "$who's alerts"
    MCP_FILTER='[.items[] | {alert, kind, severity, detail, raised_by}] | .[0:4]' mcp "$who" my_alerts status=Open
  done
  as quang
  MCP_FILTER=. mcp quang my_alerts status=Open > /dev/null
  local alert; alert=$(jq -r '.items[0].alert' <<<"$LAST")
  api $T PUT "/api/doc/OpsAlert/$alert" '{"status":"Dismissed","note":"kiện nhẹ dưới 0,5 kg, cân hub sai số"}' '{name, status: .data.status, note: .data.note}'
  as mai
  say "What the AI reads next: the release's results and the dismissals"
  MCP_FILTER='.items[0] | {release, stage, calls, errors, results}' mcp aibot release_status release="$rel"; wait_key
}

prepare
for s in "${SCENES[@]}"; do "scene$s"; done
echo
echo "Done. Desk: ${BASE}/desk/  ·  releases: ${BASE}/desk/#/list/AppRelease"
