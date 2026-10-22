#!/usr/bin/env bash
# Scene-by-scene terminal demo of Astra HRM: attendance → payroll entry → salary slips → general ledger.
#   scripts/demo-hrm.sh [BASE] [SCENE...]     e.g. scripts/demo-hrm.sh http://localhost:8080 4 5 6
# PAUSE=0 runs without waiting for Enter. Run scripts/demo-seed.sh first; it seeds September 2026.
# Scene 4 opens a payroll run, 6 submits it, 7 cancels it, so 4 → 7 can be replayed any number of times.
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
START=2026-09-01; END=2026-09-30
cd "$(dirname "$0")/.."
source scripts/lib/demo-common.sh
AS=lan   # the signed-in Keycloak user for the next calls: lan is HR of saigon-express

title() { printf '\n\033[1;35m━━━ HRM %s · %s ━━━\033[0m\n' "$1" "$2"; }
say()   { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
wait_key() { [[ $PAUSE == 1 ]] && read -rsp $'\033[2m  [Enter]\033[0m' _ && echo; return 0; }

# api TENANT METHOD PATH [JSON] [JQ] — as user $AS: shows the call, prints status and (filtered) JSON body.
# GET carries no body, so for it the fourth argument is the jq filter.
api() {
  local tenant=$1 method=$2 path=$3 body=${4:-} filter=${5:-.}
  if [[ $method == GET ]] && (( $# == 4 )); then filter=$body; body=""; fi
  printf '\033[2m$ curl -X %s %s%s -H "Authorization: Bearer <%s>" -H "X-Astra-Tenant: %s"%s\033[0m\n' \
    "$method" "$BASE" "$path" "$AS" "$tenant" "${body:+ -d '${body:0:160}$( (( ${#body} > 160 )) && echo …)'}"
  http "$AS" "$tenant" "$method" "$path" "$body"
  if [[ $HTTP_CODE == 2* ]]; then printf '\033[1;32mHTTP %s\033[0m\n' "$HTTP_CODE"; else printf '\033[1;31mHTTP %s\033[0m\n' "$HTTP_CODE"; fi
  [[ $HTTP_CODE == 2* ]] || filter=.   # errors are shown whole
  if [[ -n $HTTP_BODY ]]; then jq -C "$filter" <<<"$HTTP_BODY" 2>/dev/null || echo "$HTTP_BODY"; fi
  LAST="$HTTP_BODY"
}
as() { AS=$1; say "Signed in as ${1}"; }
# mcp TENANT JSON-RPC-METHOD PARAMS [JQ] — one MCP request as user $AS
mcp() { api "$1" POST /mcp "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$2\",\"params\":$3}" "${4:-.}"; }

# run_of DOCSTATUS — the newest payroll entry for September 2026 in that state (0 draft, 1 submitted), or empty
run_of() {
  http lan $T GET "/api/doc/PayrollEntry?start_date=${START}&docstatus=$1&limit=1"
  jq -r '.[0].name // empty' <<<"$HTTP_BODY"
}
SLIPS='[.[] | {name, employee: .data.employee, payment_days: .data.payment_days, gross: .data.gross_pay, deductions: .data.total_deduction, net: .data.net_pay, docstatus}] | sort_by(.employee)'
PAYROLL_ACCOUNTS='[.[] | select(.account | IN("334","3383","3384","3386","642")) | {account, debit, credit, balance}]'

scene1() {
  title 1 "An HR and payroll app on the public SPI"
  say "One more Tier 0 app in the same image: eight DocTypes, its hooks and an MCP tool"
  api $T GET /api/app '[.[] | select(.id == "io.astra.hrm")]'
  api $T GET /api/doctype '[.[] | select(.module == "hrm") | {name, kind, submittable, naming: (.naming.series // .naming.field)}]'; wait_key
  say "Salary components: earnings, and the employee's statutory insurance (BHXH 8%, BHYT 1.5%, BHTN 1% — demo seed config)"
  api $T GET "/api/doc/SalaryComponent?limit=20" '[.[] | {abbr: .name, type: .data.component_type, calculation: .data.calculation, rate: .data.rate, amount: .data.amount, prorated: .data.depends_on_payment_days, account: .data.account}] | sort_by(.type, .abbr)'; wait_key
  say "A submitted salary structure for couriers, and who is paid by what"
  api $T GET /api/doc/SalaryStructure/COURIER '{name, docstatus, earnings: .data.earnings, deductions: .data.deductions}'
  api $T GET "/api/doc/SalaryStructureAssignment?docstatus=1" '[.[] | {employee: .data.employee, structure: .data.salary_structure, base: .data.base, from: .data.from_date}] | sort_by(.employee)'; wait_key
}

scene2() {
  title 2 "Tier 2: the courier bonus is the tenant's own WebAssembly"
  say "The formula, in Rust (astra-apps/hrm/wasm/courier-kpi), compiled to a few hundred bytes of Wasm"
  sed -n '1,25p' astra-apps/hrm/wasm/courier-kpi/src/lib.rs; wait_key
  say "On upload SalaryComponentHook ran it in the sandbox: a self-test, and the module's fingerprint"
  api $T GET /api/doc/SalaryComponent/KPI '{abbr: .name, calculation: .data.calculation, self_test: .data.self_test, sha256: .data.module_sha256}'; wait_key
  say "A formula that never returns is stopped at its deadline and refused — payroll never sees it"
  local loop; loop=$(base64 < astra-apps/hrm/wasm/modules/runaway-kpi.wasm | tr -d '\n')
  api $T POST /api/doc/SalaryComponent "{\"abbr\":\"LOOP\",\"component_name\":\"Vòng lặp\",\"component_type\":\"Earning\",\"calculation\":\"Wasm\",\"account\":\"642\",\"module_wasm\":\"$loop\"}"; wait_key
}

scene3() {
  title 3 "Attendance decides payment days"
  say "September for NV002: one day absent, one half day (unmarked working days count as worked)"
  api $T GET "/api/doc/Attendance?employee=NV002&month=2026-09&limit=40" '[.[] | select(.data.status != "Present") | {date: .data.attendance_date, status: .data.status, pieces: .data.delivered_pieces}]'
  api $T GET "/api/doc/Attendance?employee=NV004&month=2026-09" '[.[] | {date: .data.attendance_date, status: .data.status, leave_type: .data.leave_type}]'; wait_key
  as quang
  say "The hub supervisor (ops) records today's attendance and delivered pieces"
  api $T POST /api/doc/Attendance "{\"employee\":\"NV001\",\"attendance_date\":\"$(date +%F)\",\"status\":\"Present\",\"delivered_pieces\":41}" '{name, employee: .data.employee, status: .data.status, pieces: .data.delivered_pieces}'
  local a; a=$(jq -r '.name // empty' <<<"$LAST")
  [[ -n $a ]] && api $T POST "/api/doc/Attendance/$a/submit" "" '{name, docstatus}'
  say "…but salaries are not ops' business"
  api $T GET /api/doc/SalarySlip; wait_key
  as tuan
  say "Nor sales'"
  api $T GET /api/doc/PayrollEntry
  as lan; wait_key
}

scene4() {
  title 4 "Payroll Entry: one save, every slip, virtual threads"
  local draft; draft=$(run_of 0)
  if [[ -z $draft ]]; then
    say "Open the September payroll for every active employee"
    api $T POST /api/doc/PayrollEntry "{\"start_date\":\"$START\",\"end_date\":\"$END\",\"note\":\"Lương tháng 9/2026\"}" '{name, docstatus, period: "\(.data.start_date) – \(.data.end_date)", posting_date: .data.posting_date}'
    draft=$(jq -r .name <<<"$LAST")
  else
    say "A September payroll is already open as a draft: $draft"
  fi
  wait_key
  say "Its draft salary slips: inputs read in the entry's transaction, slips computed in parallel, written in the same transaction"
  api $T GET "/api/doc/SalarySlip?payroll_entry=$draft" "$SLIPS"; wait_key
}

scene5() {
  title 5 "A salary slip, line by line"
  local run; run=$(run_of 0); [[ -n $run ]] || run=$(run_of 1)
  [[ -n $run ]] || { say "No September payroll yet: run scene 4 first"; return; }
  say "NV002, courier: 24.5 of 26 days, so fixed pay is prorated; the Wasm bonus counts pieces; insurance is on the contract salary"
  api $T GET "/api/doc/SalarySlip?payroll_entry=$run&employee=NV002" '.[0] | {name, employee: .data.employee, base: .data.base, working_days: .data.working_days, payment_days: .data.payment_days, pieces: .data.delivered_pieces, earnings: [.data.earnings[] | {component, amount, basis}], deductions: [.data.deductions[] | {component, amount, basis}], gross: .data.gross_pay, net: .data.net_pay}'
  wait_key
  say "NV003 joined on 14 September (15 working days) and gets a one-off Additional Salary"
  api $T GET "/api/doc/SalarySlip?payroll_entry=$run&employee=NV003" '.[0] | {payment_days: .data.payment_days, earnings: [.data.earnings[] | {component, amount, basis}], net: .data.net_pay}'
  say "Computed fields belong to the hook: a client cannot set its own net pay"
  local slip; slip=$(jq -r '.[0].name' <<<"$LAST")
  api $T PUT "/api/doc/SalarySlip/$slip" '{"net_pay":99000000}'; wait_key
}

scene6() {
  title 6 "Submit: slips and ledger in one transaction"
  local run; run=$(run_of 0)
  [[ -n $run ]] || { say "No draft September payroll: run scene 4 first"; return; }
  say "Submitting the entry submits every slip; each slip posts Dr 642 / Cr 3383, 3384, 3386 / Cr 334 through the GeneralLedger SPI"
  api $T POST "/api/doc/PayrollEntry/$run/submit" "" '{name, docstatus, employees: .data.employee_count, gross: .data.total_gross, deductions: .data.total_deduction, net: .data.total_net}'
  api $T GET "/api/doc/SalarySlip?payroll_entry=$run" '[.[] | {name, employee: .data.employee, docstatus}] | sort_by(.employee)'; wait_key
  as khoa
  say "The accountant sees one balanced voucher per slip"
  local slip; slip=$(http lan $T GET "/api/doc/SalarySlip?payroll_entry=$run&employee=NV001" && jq -r '.[0].name' <<<"$HTTP_BODY")
  api $T GET "/api/ledger/entries?voucher_no=$slip" '[.[] | {account, party, debit, credit}]'
  api $T GET /api/ledger/trial-balance "$PAYROLL_ACCOUNTS"
  say "A salary period is paid once: a second September run is refused"
  as lan
  api $T POST /api/doc/PayrollEntry "{\"start_date\":\"$START\",\"end_date\":\"$END\"}"; wait_key
}

scene7() {
  title 7 "Cancel: every voucher reversed, nothing deleted"
  local run; run=$(run_of 1)
  [[ -n $run ]] || { say "No submitted September payroll: run scene 6 first"; return; }
  api $T POST "/api/doc/PayrollEntry/$run/cancel" "" '{name, docstatus}'
  api $T GET "/api/doc/SalarySlip?payroll_entry=$run" '[.[] | {name, employee: .data.employee, docstatus}] | sort_by(.employee)'; wait_key
  as khoa
  local slip; slip=$(http lan $T GET "/api/doc/SalarySlip?payroll_entry=$run&employee=NV001" && jq -r '.[0].name' <<<"$HTTP_BODY")
  say "The ledger is append-only: the reversal mirrors every line"
  api $T GET "/api/ledger/entries?voucher_no=$slip" '[.[] | {account, debit, credit, is_reversal}]'
  api $T GET /api/ledger/trial-balance "$PAYROLL_ACCOUNTS"
  as lan; wait_key
}

scene8() {
  title 8 "An AI agent asks for the payroll — without names"
  mcp $T initialize '{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"demo","version":"1"}}' '.result | {protocolVersion, serverInfo}'
  mcp $T tools/list '{}' '[.result.tools[] | select(.name == "payroll_summary") | {name, description}]'; wait_key
  local run; run=$(run_of 0); [[ -n $run ]] || run=$(run_of 1)
  say "payroll_summary: totals and one line per slip; employee names are personal data and leave masked"
  if [[ -n $run ]]; then
    mcp $T tools/call "{\"name\":\"payroll_summary\",\"arguments\":{\"payroll_entry\":\"$run\"}}" '.result.structuredContent'
  else
    mcp $T tools/call '{"name":"payroll_summary","arguments":{}}' '.result.structuredContent'
  fi
  wait_key
  as tuan
  say "The tool reads PayrollEntry and SalarySlip, so a salesperson's agent is refused"
  mcp $T tools/call '{"name":"payroll_summary","arguments":{}}' '.result | {isError, text: .content[0].text}'
  as lan; wait_key
}

for s in "${SCENES[@]}"; do "scene$s"; done
printf '\n\033[1;32mDone.\033[0m Desk: %s/desk/\n' "$BASE"
