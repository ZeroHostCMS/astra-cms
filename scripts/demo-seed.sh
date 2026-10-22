#!/usr/bin/env bash
# Seeds the Astra Express demo (docs/use-case-01.md) into two tenants through the public REST API only,
# signed in through Keycloak as the realm's demo user.
# Run once against a fresh database; scripts/demo-reset.sh clears the demo tenants of the Docker Compose stack.
# Bash 5 is required (associative arrays, EPOCHSECONDS); macOS ships 3.2, Homebrew has the current one.
if (( BASH_VERSINFO[0] < 5 )); then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [[ -x $b ]] && exec "$b" "$0" "$@"; done
  echo "Bash 5+ is required: brew install bash" >&2; exit 1
fi
set -euo pipefail
BASE="${1:-http://localhost:8080}"
cd "$(dirname "$0")/.."
source scripts/lib/demo-common.sh
SEEDER=demo   # admin of both demo tenants in the Keycloak realm; documents are owned by this user
login "$SEEDER"   # cached here so the $(…) subshells below reuse the token

call() { # call TENANT METHOD PATH [JSON] -> prints the response body, fails on non-2xx; AS=user overrides the seeder
  http "${AS:-$SEEDER}" "$1" "$2" "$3" "${4:-}"
  if [[ $HTTP_CODE != 2* ]]; then echo "FAILED $2 $3 ($HTTP_CODE): $HTTP_BODY" >&2; exit 1; fi
  printf '%s' "$HTTP_BODY"
}
chart_of_accounts() { # Vietnamese chart of accounts (Circular 200), the accounts the courier flow posts to
  local a
  for a in '112|Tiền gửi ngân hàng|Asset' '131|Phải thu của khách hàng|Asset' '1388|Phải thu khác (COD bưu tá đang giữ)|Asset' \
           '3388|Phải trả khác (COD phải trả shop)|Liability' '511|Doanh thu cung cấp dịch vụ|Income'; do
    IFS='|' read -r num name type <<<"$a"
    call "$1" POST /api/doc/Account "{\"account_number\":\"$num\",\"account_name\":\"$name\",\"root_type\":\"$type\"}" >/dev/null
  done
  echo "  chart of accounts: 112, 131, 1388, 3388, 511"
}
customer() { call "$1" POST /api/doc/Customer "$2" >/dev/null; echo "  customer $(jq -r .code <<<"$2")"; }
order()    { call "$1" POST /api/doc/CustomerOrder "$2" | jq -r .name; }
submit()   { call "$1" POST "/api/doc/CustomerOrder/$2/submit" | jq -r .data.waybill; }
scan()     { call "$1" PUT "/api/doc/Waybill/$2" "$3" >/dev/null; }

seed_saigon() {
  local T=saigon-express
  echo "Tenant ${T}"
  chart_of_accounts $T
  customer $T '{"code":"C001","customer_name":"Shop Hoa Mai","segment":"B2B","phone":"0283822111","address":"45 Nguyễn Huệ, Quận 1, TP.HCM","credit_limit":2000000}'
  customer $T '{"code":"C002","customer_name":"Gốm Sứ Minh Long Store","segment":"B2B","phone":"0274366888","address":"Thủ Dầu Một, Bình Dương","credit_limit":150000}'
  customer $T '{"code":"C003","customer_name":"Trần Thị Bích","segment":"Retail","phone":"0908111222"}'

  local o1 o2 o3 o4 o5 o6 w1 w2 w3 w4 w6
  o1=$(order $T '{"customer":"C001","receiver_name":"Lê Minh Tuấn","receiver_phone":"0912345678","receiver_address":"18 Trần Phú, Hải Châu, Đà Nẵng","zone":"Domestic","service":"Express","weight_kg":1.5,"cod_amount":850000}')
  o2=$(order $T '{"customer":"C001","receiver_name":"Phạm Thu Hà","receiver_phone":"0987654321","receiver_address":"22 Lý Thường Kiệt, Hoàn Kiếm, Hà Nội","zone":"Domestic","service":"Standard","weight_kg":3,"cod_amount":1200000}')
  o3=$(order $T '{"customer":"C002","receiver_name":"Nguyễn Văn Bình","receiver_phone":"0933222111","receiver_address":"7 Võ Văn Tần, Quận 3, TP.HCM","zone":"Same city","service":"Express","weight_kg":4.2,"cod_amount":0,"note":"Hàng gốm dễ vỡ"}')
  o4=$(order $T '{"customer":"C003","receiver_name":"Đỗ Quang Huy","receiver_phone":"0977888999","receiver_address":"Xã Đồng Văn, Hà Giang","zone":"Remote","service":"Standard","weight_kg":0.8,"cod_amount":320000}')
  o5=$(order $T '{"customer":"C002","receiver_name":"Võ Thị Mai","receiver_phone":"0944555666","receiver_address":"12 Hùng Vương, Huế","zone":"Domestic","service":"Standard","weight_kg":6,"cod_amount":2500000}')
  o6=$(order $T '{"customer":"C001","receiver_name":"Bùi Anh Khoa","receiver_phone":"0966777888","receiver_address":"3 Lê Lai, Quận 1, TP.HCM","zone":"Same city","service":"Standard","weight_kg":1,"cod_amount":150000}')
  echo "  orders $o1 … $o6"

  w1=$(submit $T "$o1"); w2=$(submit $T "$o2"); w3=$(submit $T "$o3"); w4=$(submit $T "$o4"); w6=$(submit $T "$o6")
  echo "  submitted 5 orders → waybills $w1 … $w6 ($o5 stays a draft)"

  for s in '{"ops_status":"Picked Up"}' '{"ops_status":"At Hub","current_hub":"HCM-Tân Bình"}' \
           '{"ops_status":"At Hub","current_hub":"DN-Hải Châu"}' '{"ops_status":"Out for Delivery"}' '{"ops_status":"Delivered"}'; do
    scan $T "$w1" "$s"
  done
  for s in '{"ops_status":"Picked Up"}' '{"ops_status":"At Hub","current_hub":"HCM-Tân Bình"}' \
           '{"ops_status":"At Hub","current_hub":"HN-Long Biên"}' '{"ops_status":"Out for Delivery"}'; do
    scan $T "$w2" "$s"
  done
  scan $T "$w3" '{"ops_status":"Picked Up"}'; scan $T "$w3" '{"ops_status":"At Hub","current_hub":"HCM-Tân Bình"}'
  echo "  scans: $w1 Delivered, $w2 Out for Delivery, $w3 At Hub, $w4 Created"

  call $T POST "/api/doc/CustomerOrder/$o6/cancel" >/dev/null
  local o6a; o6a=$(call $T POST "/api/doc/CustomerOrder/$o6/amend" | jq -r .name)
  echo "  $o6 cancelled (waybill $w6 cancelled with it) and amended as draft $o6a"

  call $T POST /api/doc/SupportTicket "{\"subject\":\"Khách báo hàng gốm bị vỡ\",\"waybill\":\"$w3\",\"customer\":\"C002\",\"channel\":\"Zalo\",\"description\":\"Ảnh chụp kèm theo, thùng móp góc\"}" >/dev/null
  call $T POST /api/doc/SupportTicket "{\"subject\":\"Hỏi giờ giao hàng\",\"waybill\":\"$w2\",\"customer\":\"C001\",\"channel\":\"Hotline\",\"priority\":\"Low\"}" >/dev/null
  echo "  2 support tickets (one auto-escalated)"

  local wasm
  wasm=$(base64 < astra-apps/express/wasm/modules/tet-promo.wasm | tr -d '\n')
  call $T POST /api/doc/PricingRule "{\"rule_name\":\"Khuyến mãi Tết B2B\",\"segment\":\"B2B\",\"active\":false,\"module_wasm\":\"$wasm\"}" >/dev/null
  echo "  pricing rule 'Khuyến mãi Tết B2B' uploaded as WebAssembly (inactive; scene 12 switches it on)"

  local payout
  payout=$(AS=khoa call $T POST /api/doc/CODRemittance '{"customer":"C001","amount":500000,"bank_reference":"VCB-20261021-001","note":"Đối soát đợt 1"}' | jq -r .name)
  AS=thu call $T POST "/api/doc/CODRemittance/$payout/submit" >/dev/null
  echo "  COD payout $payout of 500,000 to C001: prepared by khoa, approved by thu (maker/checker)"
  local draft
  draft=$(AS=khoa call $T POST /api/doc/CODRemittance '{"customer":"C001","amount":350000,"note":"Đối soát đợt 2"}' | jq -r .name)
  echo "  COD payout $draft of 350,000 prepared by khoa, waiting for a second accountant"

  call $T POST /api/doc/Article '{"title":"Bảng giá cước mới từ tháng 11","body":"Nội thành từ 18.000đ","status":"Published","tags":["pricing"]}' >/dev/null
}

seed_hanoi() {
  local T=hanoi-express
  echo "Tenant ${T}"
  CT=application/yaml call $T PUT /api/overlay/CustomerOrder "$(cat tenants/hanoi-express/overlays/customer-order.overlay.yaml)" >/dev/null
  echo "  overlay on CustomerOrder: VAT invoice fields, Island zone, delivery note required (tenants/hanoi-express/overlays)"
  chart_of_accounts $T
  customer $T '{"code":"C001","customer_name":"Nhà sách Tràng Tiền","segment":"B2B","phone":"0243825111"}'
  customer $T '{"code":"C002","customer_name":"Hoàng Văn Nam","segment":"Retail"}'
  local o1 o2 w1
  o1=$(order $T '{"customer":"C001","receiver_name":"Ngô Bảo Châu","receiver_phone":"0911000111","receiver_address":"5 Đinh Tiên Hoàng, Hoàn Kiếm, Hà Nội","zone":"Same city","service":"Express","weight_kg":2,"cod_amount":450000,"vat_invoice":true,"tax_code":"0100109106","invoice_email":"ketoan@trangtien.test","note":"Giao giờ hành chính"}')
  o2=$(order $T '{"customer":"C002","receiver_name":"Lý Thu Trang","receiver_phone":"0922000222","receiver_address":"Phường Bến Nghé, Quận 1, TP.HCM","zone":"Island","service":"Standard","weight_kg":1.2,"cod_amount":0,"note":"Đi tàu ra Cô Tô, giao trong 3 ngày"}')
  w1=$(submit $T "$o1")
  scan $T "$w1" '{"ops_status":"Picked Up"}'
  echo "  orders $o1, $o2 (draft); waybill $w1 Picked Up"
  echo "  note: hanoi-express also has customer C001 and order ${o1} — same names as saigon-express, different data"
}

seed_saigon
seed_hanoi
echo
echo "Done. Open ${BASE}/desk/  ·  compare tenants: ${BASE}/desk/compare.html"
