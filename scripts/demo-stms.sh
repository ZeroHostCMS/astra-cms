#!/usr/bin/env bash
# Scene-by-scene terminal demo of Astra STMS: vehicle routing with Timefold, live replanning and the dispatch copilot.
#   scripts/demo-stms.sh [BASE] [SCENE...]     e.g. scripts/demo-stms.sh http://localhost:8080 2 3
# PAUSE=0 runs without waiting for Enter. Run scripts/demo-seed.sh first (same STMS_DATE, default today in Vietnam).
# The copilot's language model is a scripted MOCK (astra.stms.llm=mock); the solver and every replan are real.
# Bash 5 is required (associative arrays, EPOCHSECONDS); macOS ships 3.2, Homebrew has the current one.
if (( BASH_VERSINFO[0] < 5 )); then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [[ -x $b ]] && exec "$b" "$0" "$@"; done
  echo "Bash 5+ is required: brew install bash" >&2; exit 1
fi
set -uo pipefail
BASE="${1:-http://localhost:8080}"; shift || true
SCENES=("$@"); [[ ${#SCENES[@]} -eq 0 ]] && SCENES=(1 2 3 4 5 6 7 8)
PAUSE="${PAUSE:-1}"
T=saigon-express
HUB=HCM-TB
STMS_DATE="${STMS_DATE:-$(TZ=Asia/Ho_Chi_Minh date +%F)}"
SECONDS_BUDGET="${SOLVE_SECONDS:-5}"
cd "$(dirname "$0")/.."
source scripts/lib/demo-common.sh
AS=linh   # dispatcher of both demo tenants in the Keycloak realm

title() { printf '\n\033[1;35m━━━ Scene %s · %s ━━━\033[0m\n' "$1" "$2"; }
say()   { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
wait_key() { [[ $PAUSE == 1 ]] && read -rsp $'\033[2m  [Enter]\033[0m' _ && echo; return 0; }

# api TENANT METHOD PATH [JSON] [JQ] — as user $AS: shows the call, prints status and (filtered) JSON body.
api() {
  local tenant=$1 method=$2 path=$3 body=${4:-} filter=${5:-.}
  if [[ $method == GET || $method == DELETE ]] && (( $# == 4 )); then filter=$body; body=""; fi
  printf '\033[2m$ curl -X %s %s%s -H "Authorization: Bearer <%s>" -H "X-Astra-Tenant: %s"%s\033[0m\n' "$method" "$BASE" "$path" "$AS" \
    "$tenant" "${body:+ -d '$body'}"
  http "$AS" "$tenant" "$method" "$path" "$body"
  if [[ $HTTP_CODE == 2* ]]; then printf '\033[1;32mHTTP %s\033[0m\n' "$HTTP_CODE"; else printf '\033[1;31mHTTP %s\033[0m\n' "$HTTP_CODE"; fi
  [[ $HTTP_CODE == 2* ]] || filter=.
  if [[ -n $HTTP_BODY ]]; then jq -C "$filter" <<<"$HTTP_BODY" 2>/dev/null || echo "$HTTP_BODY"; fi
  LAST="$HTTP_BODY"
}
# tool TOOL ARGS_JSON [JQ] — one MCP tools/call as user $AS; JQ applies to result.structuredContent
tool() {
  local filter=${3:-.}
  api $T POST /mcp "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
    ".result | if .isError then {isError, error: .content[0].text} else (.structuredContent | $filter) end"
}
# copilot INSTRUCTION — POST /api/stms/copilot (the Desk's path): same tenant, role check, PII masking and audit as MCP
copilot() {
  local body
  body=$(jq -cn --arg plan "$PLAN" --arg instruction "$1" '{plan: $plan, instruction: $instruction}')
  api $T POST /api/stms/copilot "$body" "$COPILOT_REST"
  [[ -n ${TX_LSN:-} ]] && printf '\033[2m← X-Tx-LSN: %s (read-your-writes position of this replan)\033[0m\n' "$TX_LSN"
  return 0
}
routes() { # the plan's route table, as the Desk shows it
  http "$AS" $T GET "/api/doc/DispatchPlan/$PLAN"
  jq -r '.data.route_summary' <<<"$HTTP_BODY"
}
# The moves of a replan: stop, from → to, new ETA; plus score, distance and solve time
CHANGE='{event, score: "\(.score_before) → \(.score_after)", feasible, km: "\(.distance_km_before) → \(.distance_km_after)", solve_ms,
  moved: [.moved[] | "\(.stop) \(.label): \(.from // "new") → \(.to) (start \(.start))"], resequenced}'
COPILOT='{llm, llm_note, tool_calls: [.tool_calls[] | {name, input: (.input | del(.plan))}], explanation,
  moved: [.results[] | select(.ok) | .result.moved? // [] | .[] | "\(.stop) \(.label): \(.from // "new") → \(.to) (start \(.start))"],
  solve_ms: [.results[] | select(.tool != "stms_explain_plan") | .result.solve_ms? // empty]}'
# The REST answer also carries the net effect of all calls: moved_stops and the routes before and after
COPILOT_REST='{llm, llm_note, tool_calls: [.tool_calls[] | {name, input: (.input | del(.plan))}], explanation,
  score: "\(.score_before) → \(.score_after)", feasible,
  moved_stops: [.moved_stops[] | "\(.stop) \(.label): \(.from // "new") → \(.to) (start \(.start))"], resequenced,
  solve_ms: [.results[] | select(.tool != "stms_explain_plan") | .result.solve_ms? // empty]}'

PLAN="${PLAN:-}"
need_plan() {
  [[ -n $PLAN ]] && return
  PLAN=$(http "$AS" $T GET "/api/doc/DispatchPlan?hub=$HUB&plan_date=$STMS_DATE&docstatus=0&limit=1" && jq -r '.[0].name // empty' <<<"$HTTP_BODY")
  [[ -n $PLAN ]] || { echo "No draft plan for $HUB on $STMS_DATE: run scene 2 first" >&2; exit 1; }
}

scene1() {
  title 1 "Master data: hub, fleet, the day's stops"
  say "Signed in as linh (role dispatcher). The STMS DocTypes come from astra-apps/stms, discovered at startup"
  api $T GET /api/doctype "" '[.[] | select(.module == "stms") | {name, kind, submittable}]'; wait_key
  say "Hub $HUB and its vehicles"
  api $T GET "/api/doc/Hub/$HUB" '.data | {hub_name, city, latitude, longitude, open_from}'
  api $T GET "/api/doc/Vehicle?depot_hub=$HUB" '[.[] | {plate: .name, capacity_kg: .data.capacity_kg}]'; wait_key
  say "Twenty stops for $STMS_DATE: weight, time window, priority"
  api $T GET "/api/doc/DeliveryStop?hub=$HUB&plan_date=$STMS_DATE&limit=50" \
    '[.[] | "\(.name)  \(.data.window_from)-\(.data.window_to)  \(.data.demand_kg) kg  \(.data.priority)  \(.data.label)"] | sort'
  wait_key
}

scene2() {
  title 2 "Baseline: Timefold solves the day (capacity + time windows hard, distance soft)"
  say "Create a draft Dispatch Plan with solve: true — DispatchPlanHook routes it within a ${SECONDS_BUDGET}s budget on a virtual thread"
  api $T POST /api/doc/DispatchPlan "{\"plan_date\":\"$STMS_DATE\",\"hub\":\"$HUB\",\"solve\":true,\"solve_seconds\":$SECONDS_BUDGET}" \
    '{name, status: .data.status, score: .data.score, feasible: .data.feasible, distance_km: .data.distance_km, vehicles_used: .data.vehicles_used, solve_ms: .data.solve_ms}'
  PLAN=$(jq -r .name <<<"$LAST"); wait_key
  say "The routes (child rows: vehicle, sequence, stop, ETA, load) — the same text the Desk shows"
  routes; wait_key
}

scene3() {
  title 3 "Copilot: a vehicle breaks down"
  need_plan
  say "The dispatcher types in Vietnamese (Desk → Điều phối (Copilot), i.e. POST /api/stms/copilot)."
  say "The LLM is a scripted MOCK; the tool calls it returns run for real"
  copilot "Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác"
  wait_key
  say "Replanned routes: 51C-12345 is gone, its stops are on the other vehicles, every window still holds"
  routes; wait_key
}

scene4() {
  title 4 "Copilot: VIP customers first, balance the load (constraint weights)"
  need_plan
  copilot "Ưu tiên khách VIP trước 10h, cân bằng tải giữa các xe"
  wait_key
  say "The weights now in force, and the VIP stops' service times"
  tool stms_explain_plan "{\"plan\":\"$PLAN\",\"priority_deadline\":\"10:00\"}" '{weights, priority_stops, vehicles}'
  wait_key
}

scene5() {
  title 5 "Copilot over MCP: an urgent order comes in"
  need_plan
  say "The same copilot as an MCP tool, for AI agents: tools/call stms_copilot on /mcp"
  tool stms_copilot "{\"plan\":\"$PLAN\",\"instruction\":\"Thêm đơn gấp 30kg ở Bến Thành trước 11h\"}" "$COPILOT"
  [[ -n ${TX_LSN:-} ]] && printf '\033[2m← X-Tx-LSN: %s (MCP reports the write position too)\033[0m\n' "$TX_LSN"
  wait_key
  routes; wait_key
}

scene6() {
  title 6 "Live events without the copilot: congestion and a changed time window (MCP tools)"
  need_plan
  say "Congestion around Thủ Đức: legs touching a 4 km radius take 2.5× longer"
  tool stms_traffic_delay "{\"plan\":\"$PLAN\",\"label\":\"Thủ Đức\",\"latitude\":10.8494,\"longitude\":106.7537,\"radius_km\":4,\"factor\":2.5}" "$CHANGE"
  wait_key
  local stop
  stop=$(http "$AS" $T GET "/api/doc/DeliveryStop?hub=$HUB&plan_date=$STMS_DATE&limit=50" \
         && jq -r '[.[] | select(.data.label | startswith("Linh kiện Tân Tiến"))][0].name' <<<"$HTTP_BODY")
  say "A customer moves their window: $stop now only 08:00-09:00"
  tool stms_change_time_window "{\"plan\":\"$PLAN\",\"stop\":\"$stop\",\"window_from\":\"08:00\",\"window_to\":\"09:00\"}" "$CHANGE"
  wait_key
}

scene7() {
  title 7 "Same tenant, same permissions: who may replan"
  need_plan
  say "quang (ops) can read the plan, but the copilot is refused (REST 403) and so are the replanning tools (MCP)"
  AS=quang api $T POST /api/stms/copilot "{\"plan\":\"$PLAN\",\"instruction\":\"Xe 51C-67890 hỏng, chuyển hết đơn sang xe khác\"}"
  AS=quang tool stms_vehicle_unavailable "{\"plan\":\"$PLAN\",\"vehicle\":\"51C-67890\"}"
  say "ha (customer care) does not even see routing"
  AS=ha tool stms_explain_plan "{\"plan\":\"$PLAN\"}"
  say "The same plan name in hanoi-express is a different (or no) document: tenants never see each other's plans"
  api hanoi-express POST /mcp "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"stms_explain_plan\",\"arguments\":{\"plan\":\"$PLAN\"}}}" \
    '.result | {isError, text: (.content[0].text | .[0:200])}'
  wait_key
}

scene8() {
  title 8 "Dispatch: submit freezes the plan; the event log stays"
  need_plan
  say "Every live change was logged as a DispatchEvent (readable by dispatcher and admin only)"
  api $T GET "/api/stms/plans/$PLAN/events" \
    '[.[] | {name, event_type: .data.event_type, source: .data.source, score: "\(.data.score_before) → \(.data.score_after)", moved_stops: .data.moved_stops, solve_ms: .data.solve_ms}] | reverse'
  wait_key
  say "Submit: only a solved, feasible plan can be dispatched"
  api $T POST "/api/doc/DispatchPlan/$PLAN/submit" "" '{name, docstatus, status: .data.status, score: .data.score}'
  say "Submitted means frozen: a replan is refused"
  api $T PUT "/api/doc/DispatchPlan/$PLAN" '{"solve":true}'
  tool stms_set_weights "{\"plan\":\"$PLAN\",\"distance\":2}"
  wait_key
}

for s in "${SCENES[@]}"; do "scene$s"; done
printf '\n\033[1;32mDone.\033[0m Desk: %s/desk/ (DispatchPlan %s)\n' "$BASE" "${PLAN:-}"
