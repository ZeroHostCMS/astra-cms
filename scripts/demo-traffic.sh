#!/usr/bin/env bash
# Steady demo traffic against Astra Express, one line per request: time, pod that served it (X-Astra-Pod), result,
# and the release that handled it. Ends (Ctrl-C, SIGTERM or DURATION) with "ok=… fail=…".
#   scripts/demo-traffic.sh [BASE]
#   MODE=orders (default)  create and submit orders; also shows who priced each one and which carrier plugin
#                          booked the order of a few requests earlier (bookings run in a background job)
#   MODE=ops               move recent waybills through the hubs: hub scans with a measured weight (some heavier than
#                          declared), deliveries by couriers bt01..bt08 (bt05 fails often), the events the anomaly
#                          detector scores
#   TENANT=saigon-express  AS=demo (signed-in user)  CUSTOMER=C010 (or a comma list; C010 has the credit for it)  RATE=4
#   DURATION=0 (seconds; 0 runs until stopped)  QUIET=1 prints only failures and the summary
if (( BASH_VERSINFO[0] < 5 )); then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [[ -x $b ]] && exec "$b" "$0" "$@"; done
  echo "Bash 5+ is required: brew install bash" >&2; exit 1
fi
set -uo pipefail
BASE="${1:-http://localhost:8080}"
MODE="${MODE:-orders}"; TENANT="${TENANT:-saigon-express}"; USER_AS="${AS:-demo}"; RATE="${RATE:-4}"
DURATION="${DURATION:-0}"; QUIET="${QUIET:-0}"
IFS=',' read -ra CUSTOMERS <<<"${CUSTOMER:-C010}"
cd "$(dirname "$0")/.."
source scripts/lib/demo-common.sh
login "$USER_AS"

ok=0; fail=0; started=$EPOCHSECONDS; n=0
declare -a recent=()
summary() { printf '\n\033[1m%s traffic: ok=%d fail=%d in %ds\033[0m\n' "$MODE" "$ok" "$fail" "$(( EPOCHSECONDS - started ))"; exit 0; }
trap summary INT TERM

# req METHOD PATH [BODY] -> CODE POD BODY in globals
req() {
  login "$USER_AS"
  local out
  out="$(curl -sS -m 10 -w $'\n%{http_code} %header{x-astra-pod}' -X "$1" "${BASE}$2" -H "Authorization: Bearer ${TOKENS[$USER_AS]}" \
    -H "X-Astra-Tenant: ${TENANT}" ${3:+-H 'Content-Type: application/json' --data-binary "$3"} 2>/dev/null)" || out=$'\n000 -'
  local tail="${out##*$'\n'}"
  CODE="${tail%% *}"; POD="${tail#* }"; POD="${POD:--}"; BODY="${out%$'\n'*}"
}
line() { # STATUS TEXT
  if [[ $1 == ok ]]; then ok=$((ok + 1)); [[ $QUIET == 1 ]] && return
    printf '%s \033[36m%-30s\033[0m \033[32mok \033[0m %s\n' "$(date +%T)" "$POD" "$2"
  else fail=$((fail + 1)); printf '%s \033[36m%-30s\033[0m \033[31mFAIL\033[0m %s\n' "$(date +%T)" "$POD" "$2"; fi
}

orders() {
  local zones=("Same city" "Domestic" "Domestic" "Remote") zone customer
  zone="${zones[RANDOM % 4]}"; customer="${CUSTOMERS[RANDOM % ${#CUSTOMERS[@]}]}"
  local body; body=$(jq -nc --arg c "$customer" --arg z "$zone" --argjson kg "$(( RANDOM % 40 + 5 ))" --argjson cod "$(( (RANDOM % 2) * (RANDOM % 20 + 1) * 50000 ))" \
    '{customer: $c, receiver_name: "Khách demo", receiver_phone: "0901234567", receiver_address: "1 Lê Lợi", zone: $z,
      service: "Standard", weight_kg: ($kg / 10), cod_amount: $cod, length_cm: 30, width_cm: 20, height_cm: 15}')
  req POST /api/doc/CustomerOrder "$body"
  if [[ $CODE != 201 ]]; then line fail "create $CODE $(jq -c '.errors? // .' <<<"$BODY" 2>/dev/null | head -c 160)"; return; fi
  local name fee by; name=$(jq -r .name <<<"$BODY"); fee=$(jq -r .data.shipping_fee <<<"$BODY"); by=$(jq -r .data.priced_by <<<"$BODY")
  local pod_priced="$POD"   # the order is priced on create; the submit below may land on the other pod
  req POST "/api/doc/CustomerOrder/$name/submit"
  if [[ $CODE != 200 ]]; then line fail "submit $name $CODE"; return; fi
  recent+=("$(jq -r .data.waybill <<<"$BODY")")
  local booked=""
  if (( ${#recent[@]} > 6 )); then
    local w="${recent[0]}"; recent=("${recent[@]:1}")
    local pod_submit="$POD"
    req GET "/api/doc/Waybill/$w"
    [[ $CODE == 200 ]] && booked="  · $w $(jq -r '.data.booked_by // .data.carrier_status' <<<"$BODY")"
    POD="$pod_submit"
  fi
  POD="$pod_priced"
  line ok "$name $customer $zone fee=$fee [$by]$booked"
}

ops() {
  req GET "/api/doc/Waybill?ops_status=Created&limit=5"
  local w; w=$(jq -r '.[0].name // empty' <<<"$BODY" 2>/dev/null)
  if [[ -z $w ]]; then orders; return; fi           # nothing to move: make a parcel first
  local declared; declared=$(jq -r '.[0].data.declared_weight_kg // 1' <<<"$BODY")
  local heavy=$(( RANDOM % 12 == 0 )) courier="bt0$(( RANDOM % 8 + 1 ))"
  local measured; measured=$(jq -n --argjson d "$declared" --argjson h "$heavy" '($d * (if $h == 1 then 1.5 else 1.01 end) * 100 | round) / 100')
  local s
  for s in '{"ops_status":"Picked Up"}' "{\"ops_status\":\"At Hub\",\"current_hub\":\"HCM-Tân Bình\",\"measured_weight_kg\":$measured}" \
           "{\"ops_status\":\"Out for Delivery\",\"courier\":\"$courier\"}"; do
    req PUT "/api/doc/Waybill/$w" "$s"
    [[ $CODE == 200 ]] || { line fail "scan $w $CODE $(head -c 120 <<<"$BODY")"; return; }
  done
  local final='Delivered'
  if [[ $courier == bt05 ]] && (( RANDOM % 3 == 0 )); then final='Failed Delivery'; elif (( RANDOM % 25 == 0 )); then final='Failed Delivery'; fi
  req PUT "/api/doc/Waybill/$w" "{\"ops_status\":\"$final\"}"
  [[ $CODE == 200 ]] || { line fail "scan $w $CODE"; return; }
  line ok "$w hub weight ${declared}→${measured} kg, $courier: $final"
}

interval=$(jq -n --argjson r "$RATE" '1 / $r')
while :; do
  n=$((n + 1))
  case $MODE in orders) orders ;; ops) ops ;; *) echo "MODE must be orders or ops" >&2; exit 2 ;; esac
  (( DURATION > 0 && EPOCHSECONDS - started >= DURATION )) && summary
  sleep "$interval"
done
