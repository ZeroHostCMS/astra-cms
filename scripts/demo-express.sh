#!/usr/bin/env bash
# Scene-by-scene terminal demo of Astra Express (docs/demo-video-series.md). Each scene matches one video.
#   scripts/demo-express.sh [BASE] [SCENE...]     e.g. scripts/demo-express.sh http://localhost:8080 3 4
# PAUSE=0 runs without waiting for Enter. Run scripts/demo-seed.sh first.
# Bash 5 is required (associative arrays, EPOCHSECONDS); macOS ships 3.2, Homebrew has the current one.
if (( BASH_VERSINFO[0] < 5 )); then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [[ -x $b ]] && exec "$b" "$0" "$@"; done
  echo "Bash 5+ is required: brew install bash" >&2; exit 1
fi
set -uo pipefail
BASE="${1:-http://localhost:8080}"; shift || true
SCENES=("$@"); [[ ${#SCENES[@]} -eq 0 ]] && SCENES=(1 2 3 4 5 6 7 8 9 10 11 12 13 14 15)
PAUSE="${PAUSE:-1}"
T=saigon-express
cd "$(dirname "$0")/.."
source scripts/lib/demo-common.sh
AS=demo   # the signed-in Keycloak user for the next calls; scenes switch it to show roles
# Scene 8 runs the kernel jar, which targets Java 25; on macOS prefer an installed JDK 25.
if JDK25=$(/usr/libexec/java_home -v 25 2>/dev/null); then export PATH="$JDK25/bin:$PATH"; fi

title() { printf '\n\033[1;35m━━━ Scene %s · %s ━━━\033[0m\n' "$1" "$2"; }
say()   { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
wait_key() { [[ $PAUSE == 1 ]] && read -rsp $'\033[2m  [Enter]\033[0m' _ && echo; return 0; }

# api TENANT METHOD PATH [JSON] [JQ] — as user $AS: shows the call, prints status and (filtered) JSON body.
# GET and DELETE carry no body, so for them the fourth argument is the jq filter.
api() {
  local tenant=$1 method=$2 path=$3 body=${4:-} filter=${5:-.}
  if [[ $method == GET || $method == DELETE ]] && (( $# == 4 )); then filter=$body; body=""; fi
  printf '\033[2m$ curl -X %s %s%s -H "Authorization: Bearer <%s>"%s%s%s\033[0m\n' "$method" "$BASE" "$path" "$AS" \
    "${tenant:+ -H \"X-Astra-Tenant: $tenant\"}" "${REQUIRED_LSN:+ -H \"X-Required-LSN: $REQUIRED_LSN\"}" "${body:+ -d '$body'}"
  http "$AS" "$tenant" "$method" "$path" "$body"
  if [[ $HTTP_CODE == 2* ]]; then printf '\033[1;32mHTTP %s\033[0m' "$HTTP_CODE"; else printf '\033[1;31mHTTP %s\033[0m' "$HTTP_CODE"; fi
  if [[ -n $TX_LSN ]]; then printf '   \033[1;33mX-Tx-LSN: %s\033[0m\n' "$TX_LSN"; else echo; fi
  [[ $HTTP_CODE == 2* ]] || filter=.   # errors are shown whole
  if [[ -n $HTTP_BODY ]]; then jq -C "$filter" <<<"$HTTP_BODY" 2>/dev/null || echo "$HTTP_BODY"; fi
  LAST="$HTTP_BODY"
}
as() { AS=$1; say "Signed in as ${1}"; }
# The database behind BASE: DB=k3d when the k3d stack serves localhost:8080, DB=compose for `make dev`, DB=none to skip SQL.
if [[ -z ${DB:-} ]]; then
  if [[ $BASE == *:8080* ]] && kubectl -n astra get pod postgres-0 >/dev/null 2>&1; then DB=k3d
  elif docker compose -f deploy/docker/docker-compose.yml ps --status running postgres 2>/dev/null | grep -q postgres; then DB=compose
  else DB=none; fi
fi
psql_app() {
  case $DB in
    k3d)     kubectl -n astra exec -i postgres-0 -- env PGPASSWORD=astra_app psql -h localhost -U astra_app -d astra "$@" ;;
    compose) docker compose -f deploy/docker/docker-compose.yml exec -T -e PGPASSWORD=astra_app postgres psql -U astra_app -d astra "$@" ;;
  esac
}
on_compose() { # scene 8 starts a throwaway local kernel against the Compose database, whatever BASE is
  docker compose -f deploy/docker/docker-compose.yml ps --status running postgres 2>/dev/null | grep -q postgres
}
have_db() { [[ $DB != none ]]; }

ORDER='{"customer":"C001","receiver_name":"Lê Văn Demo","receiver_phone":"0901234567","receiver_address":"1 Bạch Đằng, Đà Nẵng","zone":"Domestic","service":"Express","weight_kg":2.2,"cod_amount":500000}'

scene1() {
  title 1 "One server, many apps"
  say "Health and the apps compiled into this image (Tier 0)"
  api $T GET /q/health/ready "" '.status'
  api $T GET /api/app; wait_key
  say "Every DocType they contribute, discovered at startup"
  api $T GET /api/doctype "" '[.[] | {name, module, kind, submittable, naming: (.naming.series // .naming.field)}]'; wait_key
}

scene2() {
  title 2 "DocType = metadata, Zero-DDL storage"
  say "A DocType is a YAML file in the app"
  cat astra-apps/express/src/main/resources/META-INF/astra/doctypes/customer-order.doctype.yaml; wait_key
  say "projected: true became an online expression index (CREATE INDEX CONCURRENTLY, no ALTER TABLE)"
  api $T GET /api/doctype/CustomerOrder/projections "" '[.[] | {field, valid, definition}]'; wait_key
  if have_db; then
    say "All DocTypes share one JSONB table — adding a DocType or a field never runs DDL"
    psql_app -c '\d astra_doc'
    wait_key
  fi
}

scene3() {
  title 3 "Business rules in hooks, enforced by the kernel"
  say "Create a draft order: the name comes from the series ORD-.YYYYMMDD.-.#####, the fee from ShippingFeeHook"
  api $T POST /api/doc/CustomerOrder "$ORDER" '{name, docstatus, shipping_fee: .data.shipping_fee}'
  DRAFT=$(jq -r .name <<<"$LAST"); wait_key
  say "The app's rules (ShippingFeeHook): bad phone and overweight parcel, reported together (422)"
  api $T POST /api/doc/CustomerOrder '{"customer":"C001","receiver_name":"x","receiver_phone":"123","receiver_address":"x","weight_kg":45}'; wait_key
  say "The kernel's rule: a Link must point to an existing document — customer C999 does not exist"
  api $T POST /api/doc/CustomerOrder "${ORDER/C001/C999}"; wait_key
  say "Read-only fields belong to hooks: a client cannot set its own shipping fee"
  api $T POST /api/doc/CustomerOrder "${ORDER%\}},\"shipping_fee\":1}"; wait_key
  say "Unknown fields and invalid Select values are rejected by the DocType itself"
  api $T PUT "/api/doc/CustomerOrder/$DRAFT" '{"zone":"Mars","discount":10}'; wait_key
}

scene4() {
  title 4 "Document lifecycle: Draft → Submitted → Cancelled → Amend"
  api $T POST /api/doc/CustomerOrder "$ORDER" '{name, docstatus}'
  local o; o=$(jq -r .name <<<"$LAST")
  say "Submit: the order becomes immutable and OrderWaybillHook creates its waybill in the same transaction"
  api $T POST "/api/doc/CustomerOrder/$o/submit" "" '{name, docstatus, waybill: .data.waybill}'
  local w; w=$(jq -r .data.waybill <<<"$LAST"); wait_key
  say "Editing a submitted order is refused"
  api $T PUT "/api/doc/CustomerOrder/$o" '{"note":"đổi địa chỉ"}'; wait_key
  say "Cancel cascades: the waybill is cancelled with the order"
  api $T POST "/api/doc/CustomerOrder/$o/cancel" "" '{name, docstatus}'
  api $T GET "/api/doc/Waybill/$w" "" '{name, ops_status: .data.ops_status}'; wait_key
  say "Amend creates a new draft revision linked to the cancelled one"
  api $T POST "/api/doc/CustomerOrder/$o/amend" "" '{name, docstatus, amendedFrom, waybill: .data.waybill}'; wait_key
}

scene5() {
  title 5 "Operations: the parcel state machine"
  api $T POST /api/doc/CustomerOrder "$ORDER" '{name}'
  local o w; o=$(jq -r .name <<<"$LAST")
  api $T POST "/api/doc/CustomerOrder/$o/submit" "" '{waybill: .data.waybill}'; w=$(jq -r .data.waybill <<<"$LAST")
  say "Scan: picked up, then at hub"
  api $T PUT "/api/doc/Waybill/$w" '{"ops_status":"Picked Up"}' '{ops_status: .data.ops_status}'
  api $T PUT "/api/doc/Waybill/$w" '{"ops_status":"At Hub","current_hub":"DN-Hải Châu"}' '.data.scan_log'; wait_key
  say "Skipping a step is refused by WaybillScanHook"
  api $T PUT "/api/doc/Waybill/$w" '{"ops_status":"Delivered"}'; wait_key
  say "Once the parcel is in the network, cancelling the order is vetoed — and nothing changes (one transaction)"
  api $T POST "/api/doc/CustomerOrder/$o/cancel"
  api $T GET "/api/doc/CustomerOrder/$o" "" '{name, docstatus}'; wait_key
  say "Filter on a projected field: every parcel currently At Hub"
  api $T GET "/api/doc/Waybill?ops_status=At%20Hub" "" '[.[] | {name, hub: .data.current_hub}]'; wait_key
}

scene6() {
  title 6 "Customer care sees operations"
  api $T GET "/api/doc/Waybill?ops_status=Out%20for%20Delivery&limit=1" "" '[.[] | .name]'
  local w; w=$(jq -r '.[0].name' <<<"$LAST")
  say "A complaint mentioning damage is escalated, gets an SLA deadline and a snapshot of the waybill status"
  api $T POST /api/doc/SupportTicket "{\"subject\":\"Hàng bị vỡ, khách yêu cầu đền bù\",\"waybill\":\"$w\",\"channel\":\"Hotline\"}" \
      '{name, priority: .data.priority, triage_note: .data.triage_note, sla_due: .data.sla_due, waybill_status: .data.waybill_status}'
  wait_key
  say "Open high-priority tickets"
  api $T GET "/api/doc/SupportTicket?priority=High&status=Open" "" '[.[] | {name, subject: .data.subject}]'; wait_key
}

scene7() {
  title 7 "Multi-tenant isolation, fail-closed"
  say "Two tenants, same document name, different data"
  api saigon-express GET /api/doc/Customer/C001 "" '{name, customer_name: .data.customer_name}'
  api hanoi-express  GET /api/doc/Customer/C001 "" '{name, customer_name: .data.customer_name}'; wait_key
  say "hanoi-express cannot even reference saigon-express data: links resolve inside the tenant only"
  api hanoi-express GET /api/doc/Customer/C003
  api hanoi-express POST /api/doc/CustomerOrder "${ORDER/C001/C003}"; wait_key
  say "Users only reach tenants they belong to: nam is admin of hanoi-express only"
  AS=nam api saigon-express GET /api/doc/Customer
  say "demo belongs to two tenants, so it must say which one"
  api "" GET /api/doc/Customer; wait_key
  if have_db; then
    say "Directly in PostgreSQL, as the application role: without a tenant bound, row-level security returns nothing"
    psql_app -c "SELECT count(*) AS visible_rows FROM astra_doc;"
    psql_app -c "BEGIN; SELECT set_config('astra.tenant','saigon-express',true); SELECT doctype, count(*) FROM astra_doc GROUP BY 1 ORDER BY 1; COMMIT;"
    wait_key
  fi
}

scene8() {
  title 8 "The kernel refuses to run unsafely"
  local jar=astra-server/target/quarkus-app/quarkus-run.jar
  if [[ -f $jar ]] && on_compose; then
    say "Start a second kernel connected as the PostgreSQL superuser (RLS would be ignored)…"
    printf '\033[2m$ ASTRA_DB_USER=astra java -jar %s\033[0m\n' "$jar"
    local log; log=$(ASTRA_DB_USER=astra ASTRA_DB_PASSWORD=astra QUARKUS_HTTP_PORT=8099 java -jar "$jar" 2>&1)
    printf '\033[1;31m%s\033[0m\n' "$(grep -m1 -oE "Database role '[^']+' is a SUPERUSER[^.]*\." <<<"$log" || echo "(no refusal line captured)")"
    say "…it refuses to start. Tenant isolation cannot be switched off by a misconfigured connection."
  else
    say "Needs the Docker Compose stack (make infra-up) and a build (make build): the unsafe kernel connects to it as superuser."
  fi
  wait_key
}

scene9() {
  title 9 "Keycloak: one identity, roles per tenant, permissions per DocType"
  say "Who is signed in — memberships come from Keycloak groups /tenants/<tenant>/<role>"
  AS=tuan api "" GET /api/iam/me '{username, tenants: [.tenants[] | {tenant, roles, CustomerOrder: .permissions.CustomerOrder, Waybill: .permissions.Waybill}]}'
  wait_key
  as tuan   # sales
  api $T POST /api/doc/CustomerOrder "$ORDER" '{name, owner}'
  local o; o=$(jq -r .name <<<"$LAST")
  api $T POST "/api/doc/CustomerOrder/$o/submit" "" '{name, docstatus, waybill: .data.waybill}'
  local w; w=$(jq -r .data.waybill <<<"$LAST")
  say "Sales cannot scan parcels or cancel orders"
  api $T PUT "/api/doc/Waybill/$w" '{"ops_status":"Picked Up"}'
  api $T POST "/api/doc/CustomerOrder/$o/cancel"; wait_key
  as quang  # ops
  api $T PUT "/api/doc/Waybill/$w" '{"ops_status":"Picked Up"}' '{name, ops_status: .data.ops_status}'
  api $T POST /api/doc/CustomerOrder "$ORDER"; wait_key
  as ha     # care
  api $T POST /api/doc/SupportTicket "{\"subject\":\"Khách hỏi giờ giao\",\"waybill\":\"$w\"}" '{name, waybill_status: .data.waybill_status}'
  wait_key
  as khoa   # accountant
  say "Accountants may cancel (the order's waybill is only Picked Up, so the cancel goes through)"
  api $T POST "/api/doc/CustomerOrder/$o/cancel" "" '{name, docstatus}'
  AS=demo; wait_key
}

scene10() {
  title 10 "Ledger: append-only double entry, reversals, maker/checker"
  as khoa   # accountant
  say "Trial balance of saigon-express, straight from the append-only ledger"
  api $T GET /api/ledger/trial-balance '[.[] | {account, debit, credit, balance}]'; wait_key
  say "Submitting an order posts its fee (Dr 131 / Cr 511) in the same transaction as the order"
  AS=tuan api $T POST /api/doc/CustomerOrder "$ORDER" '{name}'
  local o; o=$(jq -r .name <<<"$LAST")
  AS=tuan api $T POST "/api/doc/CustomerOrder/$o/submit" "" '{name, docstatus, shipping_fee: .data.shipping_fee}'
  api $T GET "/api/ledger/entries?voucher_no=$o" '[.[] | {account, party, debit, credit, is_reversal, partition}]'; wait_key
  say "Cancelling never edits those lines: it posts their mirror image"
  api $T POST "/api/doc/CustomerOrder/$o/cancel" "" '{name, docstatus}'
  api $T GET "/api/ledger/entries?voucher_no=$o" '[.[] | {account, debit, credit, is_reversal}]'; wait_key
  if have_db; then
    say "Not even the table owner can rewrite history"
    psql_app -c "BEGIN; SELECT set_config('astra.tenant','$T',true); UPDATE astra_ledger SET debit = 0 WHERE voucher_no = '$o'; ROLLBACK;" 2>&1 | grep -E 'ERROR|append' || true
    wait_key
  fi
  say "Monthly partitions, created ahead of time by the kernel"
  api $T GET /api/ledger/partitions '[.[] | {partition, bounds, rows}]'; wait_key
  say "COD payout above what the shop is owed is refused"
  api $T POST /api/doc/CODRemittance '{"customer":"C001","amount":99000000}'
  say "khoa prepares a payout, but segregation of duties stops khoa from approving it"
  api $T POST /api/doc/CODRemittance '{"customer":"C001","amount":100000}'
  if [[ $HTTP_CODE == 201 ]]; then
    local r; r=$(jq -r .name <<<"$LAST")
    api $T POST "/api/doc/CODRemittance/$r/submit"
    AS=thu api $T POST "/api/doc/CODRemittance/$r/submit" "" '{name, docstatus, owner}'
  fi
  AS=demo; wait_key
}

scene11() {
  title 11 "Tenant overlays: customise one tenant without touching the app"
  say "Hanoi Express's overlay, kept in Git next to its other tenant settings"
  cat tenants/hanoi-express/overlays/customer-order.overlay.yaml; wait_key
  say "Same DocType, two tenants: hanoi-express sees the overlay, saigon-express does not"
  AS=nam api hanoi-express GET /api/doctype/CustomerOrder '[.fields[] | select(.name | test("vat|tax|invoice|zone|note")) | {name, label, required, options}]'
  api $T GET /api/doctype/CustomerOrder '[.fields[] | select(.name | test("vat|tax|invoice|zone|note")) | {name, label, required, options}]'
  wait_key
  local body; body="${ORDER%\}},\"zone\":\"Island\",\"vat_invoice\":true,\"tax_code\":\"0100109106\",\"note\":\"Giao trước 10h\"}"
  say "A VAT order to an island is valid in hanoi-express…"
  AS=nam api hanoi-express POST /api/doc/CustomerOrder "$body" '{name, zone: .data.zone, tax_code: .data.tax_code, note: .data.note}'
  say "…and rejected by the very same API in saigon-express"
  api $T POST /api/doc/CustomerOrder "$body"
  say "hanoi-express made the delivery note mandatory"
  AS=nam api hanoi-express POST /api/doc/CustomerOrder "${ORDER/C001/C002}"; wait_key
  say "Overlays that would break the app are refused, and only tenant admins may change them"
  CT=application/yaml AS=nam api hanoi-express PUT /api/overlay/CustomerOrder $'fields:\n  modify:\n    - { name: weight_kg, type: Data }\n'
  CT=application/yaml AS=tuan api $T PUT /api/overlay/CustomerOrder $'fields:\n  add:\n    - { name: gift_wrap, type: Check }\n'
  wait_key
}

scene12() {
  title 12 "Tier 2 Wasm: a tenant's own pricing formula, sandboxed"
  say "Saigon Express wrote its Tết promotion in Rust and compiled it to WebAssembly"
  sed -n '1,20p' astra-apps/express/wasm/tet-promo/src/lib.rs
  ls -l astra-apps/express/wasm/modules/*.wasm; wait_key
  say "Uploaded as a PricingRule document: the kernel vetted it in the sandbox on save"
  api $T GET "/api/doc/PricingRule?limit=5" '[.[] | {name, rule_name: .data.rule_name, segment: .data.segment, active: .data.active, module_bytes: .data.module_bytes, self_test: .data.self_test}]'
  local rule; rule=$(jq -r '[.[] | select(.data.rule_name | test("Tết"))][0].name' <<<"$LAST"); wait_key
  say "Rule off: a B2B order is priced by the standard tariff"
  AS=tuan api $T POST /api/doc/CustomerOrder "$ORDER" '{name, shipping_fee: .data.shipping_fee, priced_by: .data.priced_by}'
  say "Switch the rule on — no deploy, no restart"
  api $T PUT "/api/doc/PricingRule/$rule" '{"active":true}' '{name, active: .data.active}'
  AS=tuan api $T POST /api/doc/CustomerOrder "$ORDER" '{name, shipping_fee: .data.shipping_fee, priced_by: .data.priced_by}'; wait_key
  say "A script that never returns is stopped at its deadline and never goes live"
  local bad; bad=$(base64 < astra-apps/express/wasm/modules/runaway.wasm | tr -d '\n')
  api $T POST /api/doc/PricingRule "{\"rule_name\":\"Vòng lặp vô hạn\",\"active\":true,\"module_wasm\":\"$bad\"}"
  say "The kernel did not even notice: the next order is priced as usual"
  AS=tuan api $T POST /api/doc/CustomerOrder "$ORDER" '{name, shipping_fee: .data.shipping_fee, priced_by: .data.priced_by}'
  api $T PUT "/api/doc/PricingRule/$rule" '{"active":false}' '{name, active: .data.active}'   # back to the standard tariff for the other scenes
  wait_key
}

# mcp TENANT JSON-RPC-METHOD PARAMS [JQ] — one MCP request as user $AS
mcp() { api "$1" POST /mcp "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$2\",\"params\":$3}" "${4:-.}"; }

scene13() {
  title 13 "MCP: AI agents use Astra with the user's own permissions"
  as ha   # care
  say "Handshake: any MCP client (Claude, Cursor, an in-house agent) connects with the user's Keycloak token"
  mcp $T initialize '{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"demo","version":"1"}}' '.result | {protocolVersion, serverInfo}'
  mcp $T tools/list '{}' '[.result.tools[] | {name, description}]'; wait_key
  say "Personal data is masked before it reaches the model"
  mcp $T tools/call '{"name":"search_documents","arguments":{"doctype":"CustomerOrder","filters":{"customer":"C001"},"limit":2}}' \
      '[.result.structuredContent.items[] | {name, receiver_name: .data.receiver_name, receiver_phone: .data.receiver_phone, receiver_address: .data.receiver_address, shipping_fee: .data.shipping_fee}]'
  local w; w=$(AS=demo http demo $T GET "/api/doc/Waybill?ops_status=Out%20for%20Delivery&limit=1" && jq -r '.[0].name' <<<"$HTTP_BODY")
  say "An app-contributed tool: the whole story of one parcel"
  mcp $T tools/call "{\"name\":\"track_waybill\",\"arguments\":{\"waybill\":\"$w\"}}" \
      '.result.structuredContent | {status: .waybill.data.ops_status, hub: .waybill.data.current_hub, scans: [.waybill.data.scan_log[] | "\(.status) \(.hub // "")"], order: .order.name, tickets: [.tickets[].name]}'
  wait_key
  say "The agent inherits the user's limits: care cannot create orders or read the ledger…"
  mcp $T tools/call '{"name":"create_document","arguments":{"doctype":"CustomerOrder","data":{}}}' '.result | {isError, text: .content[0].text}'
  mcp $T tools/call '{"name":"cod_outstanding","arguments":{}}' '.result | {isError, text: .content[0].text}'
  say "…but can open a ticket, and the app's triage rules still run"
  mcp $T tools/call '{"name":"create_document","arguments":{"doctype":"SupportTicket","data":{"subject":"Khách gọi: kiện hàng bị vỡ góc","channel":"Hotline"}}}' \
      '.result.structuredContent | {name, priority: .data.priority, triage_note: .data.triage_note}'
  AS=khoa mcp $T tools/call '{"name":"cod_outstanding","arguments":{}}' '.result.structuredContent.items'
  AS=demo; wait_key
}

# The CDC worker of the stack behind BASE: k3d Deployment or Docker Compose service.
cdc_scale() { # cdc_scale 0|1
  if [[ $DB == k3d ]]; then
    kubectl -n astra scale deployment/astra-cdc --replicas="$1" >/dev/null
    if [[ $1 == 0 ]]; then kubectl -n astra wait --for=delete pod -l app=astra-cdc --timeout=60s >/dev/null 2>&1 || true
    else kubectl -n astra rollout status deployment/astra-cdc --timeout=60s >/dev/null; fi
  else
    docker compose -f deploy/docker/docker-compose.yml --profile cdc "$([[ $1 == 0 ]] && echo stop || echo start)" cdc >/dev/null
  fi
}
cdc_logs() {
  if [[ $DB == k3d ]]; then kubectl -n astra logs deployment/astra-cdc --tail="$1"; else docker compose -f deploy/docker/docker-compose.yml logs --tail="$1" cdc; fi
}

# wait_replica WAYBILL LSN INTERVAL — polls the CARE replica with X-Required-LSN until 200, at most 30 s
wait_replica() {
  local deadline=$(( EPOCHSECONDS + 30 ))
  until REQUIRED_LSN=$2 http ha $T GET "/api/replica/replica_waybill?name=$1" && [[ $HTTP_CODE == 200 ]]; do
    (( EPOCHSECONDS < deadline )) || { echo "replica still behind after 30 s (HTTP $HTTP_CODE): is astra-cdc running?"; return 1; }
    sleep "$3"
  done
}

scene14() {
  title 14 "Rust CDC + Sync-Aware LSN: read-your-writes across modules"
  say "The Rust worker reads the WAL through a logical replication slot and keeps the CARE replicas current"
  api $T GET /api/sync/status '{workerReporting, currentLsn, replicatedLsn, bytesBehind, lastLagMs, events}'
  cdc_logs 3; wait_key
  local w; w=$(http demo $T GET "/api/doc/Waybill?ops_status=At%20Hub&limit=1" && jq -r '.[0].name' <<<"$HTTP_BODY")
  say "Pause the worker, then OPS scans parcel $w: the write answers with its WAL position"
  cdc_scale 0
  AS=quang api $T PUT "/api/doc/Waybill/$w" '{"ops_status":"Out for Delivery"}' '{name, ops_status: .data.ops_status}'
  local lsn=$TX_LSN; wait_key
  say "CARE reads the replica and demands that position: the kernel answers 202 Sync Pending instead of stale data"
  AS=ha REQUIRED_LSN=$lsn api $T GET "/api/replica/replica_waybill?name=$w" '.'
  wait_key
  say "Resume the worker; the same read turns 200 as soon as the watermark passes the write"
  cdc_scale 1
  local started=$EPOCHREALTIME
  wait_replica "$w" "$lsn" 0.05 || return
  printf 'in sync %.0f ms after the worker came back\n' "$(bc <<<"($EPOCHREALTIME - $started) * 1000")"
  AS=ha REQUIRED_LSN=$lsn api $T GET "/api/replica/replica_waybill?name=$w" '{sync: {replicatedLsn: .sync.replicatedLsn, lastLagMs: .sync.lastLagMs}, row: .rows[0]}'
  wait_key
  say "Steady state: write, then read with the LSN straight away"
  AS=quang api $T PUT "/api/doc/Waybill/$w" '{"ops_status":"Delivered"}' '{name, ops_status: .data.ops_status}'
  lsn=$TX_LSN; started=$EPOCHREALTIME
  wait_replica "$w" "$lsn" 0.01 || return
  printf 'replica caught up %.0f ms after the write: %s\n' "$(bc <<<"($EPOCHREALTIME - $started) * 1000")" "$(jq -c '.rows[0] | {ops_status, scans, source_lsn}' <<<"$HTTP_BODY")"
  cdc_logs 4
  if have_db; then
    say "The slot and the replica in PostgreSQL"
    psql_app -c "SELECT slot_name, plugin, active, confirmed_flush_lsn FROM pg_replication_slots;"
  fi
  wait_key
}

# sql_super SQL — the stack's PostgreSQL as superuser (BMS schema belongs to another role than the kernel's)
sql_super() {
  if [[ $DB == k3d ]]; then kubectl -n astra exec -i postgres-0 -- psql -U "$(kubectl -n astra get secret astra-db -o jsonpath='{.data.superuser}' | base64 -d)" -d astra -c "$1"
  else docker compose -f deploy/docker/docker-compose.yml exec -T postgres psql -U astra -d astra -c "$1"; fi
}
redis_cli() {
  if [[ $DB == k3d ]]; then kubectl -n astra exec -i deploy/redis -- redis-cli "$@"; else docker compose -f deploy/docker/docker-compose.yml exec -T redis redis-cli "$@"; fi
}
seata_logs() { [[ $DB == k3d ]] && kubectl -n astra logs deploy/astra-server --all-pods=true --since=2m 2>/dev/null | grep -E "Global transaction" | tail -"$1"; }

scene15() {
  title 15 "Seata: one submit, three resources, all or nothing"
  local day; day=$(date +%F)
  local credit="SELECT customer, credit_limit, used FROM bms.customer_credit WHERE tenant_id = '$T' ORDER BY customer"
  slot_counts() { echo "pickup slots today: reserved=$(redis_cli GET "astra:$T:pickup:$day:reserved") booked=$(redis_cli GET "astra:$T:pickup:$day:booked")"; }
  say "BMS keeps credit in its own schema; OPS keeps today's pickup slots in Redis"
  sql_super "$credit"
  slot_counts; wait_key
  say "Happy path: submitting an order draws BMS credit (AT branch) and books a slot (TCC branch) in one global transaction"
  AS=tuan api $T POST /api/doc/CustomerOrder "$ORDER" '{name, shipping_fee: .data.shipping_fee}'
  local o; o=$(jq -r .name <<<"$LAST")
  AS=tuan api $T POST "/api/doc/CustomerOrder/$o/submit" "" '{name, docstatus, waybill: .data.waybill}'
  sql_super "$credit"; slot_counts; seata_logs 1; wait_key
  say "BMS refuses: C002's credit cannot cover this order, so nothing of the submit survives"
  local d; d=$(http demo $T GET "/api/doc/CustomerOrder?customer=C002&docstatus=0&limit=1" && jq -r '.[0].name' <<<"$HTTP_BODY")
  AS=tuan api $T POST "/api/doc/CustomerOrder/$d/submit"
  api $T GET "/api/doc/CustomerOrder/$d" '{name, docstatus, waybill: .data.waybill}'
  api $T GET "/api/ledger/entries?voucher_no=$d" 'length'
  slot_counts; seata_logs 1; wait_key
  say "Rollback after a branch committed: the day is full, so OPS refuses the slot after BMS already took the credit"
  local booked; booked=$(redis_cli GET "astra:$T:pickup:$day:booked")
  redis_cli SET "astra:$T:pickup:$day:booked" "${PICKUP_CAPACITY:-200}" >/dev/null
  AS=tuan api $T POST /api/doc/CustomerOrder "$ORDER" '{name}'
  o=$(jq -r .name <<<"$LAST")
  AS=tuan api $T POST "/api/doc/CustomerOrder/$o/submit"
  say "Seata restored C001's credit from its undo log; the undo log is empty again"
  sql_super "$credit"
  sql_super "SELECT count(*) AS undo_rows FROM bms.undo_log;"
  seata_logs 1
  slot_counts
  redis_cli SET "astra:$T:pickup:$day:booked" "${booked:-0}" >/dev/null   # reopen the day for the other scenes
  wait_key
}

for s in "${SCENES[@]}"; do "scene$s"; done
printf '\n\033[1;32mDone.\033[0m Desk: %s/desk/\n' "$BASE"
