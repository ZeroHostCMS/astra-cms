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

# ---------- HRM: employees and payroll setup (astra-apps/hrm) ----------
# Demo seed configuration, not legal advice: employee insurance at the statutory rates (BHXH 8 %, BHYT 1.5 %,
# BHTN 1 % of the contract salary), capped at 20 × the base salary (2,340,000) for BHXH/BHYT and 20 × the
# region I minimum wage (5,310,000) for BHTN; verify current rates and caps before any real use. Employer
# contributions and personal income tax are not modelled. Every employee below is fictional.
hrm_submit() { # hrm_submit TENANT DOCTYPE JSON -> creates and submits a document, prints its name
  local name; name=$(call "$1" POST "/api/doc/$2" "$3" | jq -r .name)
  call "$1" POST "/api/doc/$2/$name/submit" >/dev/null
  printf '%s' "$name"
}
hrm_setup() { # payroll accounts, salary components and structures of one tenant
  local T=$1 a c
  for a in '334|Phải trả người lao động|Liability' '3383|Bảo hiểm xã hội|Liability' '3384|Bảo hiểm y tế|Liability' \
           '3386|Bảo hiểm thất nghiệp|Liability' '642|Chi phí quản lý doanh nghiệp|Expense'; do
    IFS='|' read -r num name type <<<"$a"
    call "$T" POST /api/doc/Account "{\"account_number\":\"$num\",\"account_name\":\"$name\",\"root_type\":\"$type\"}" >/dev/null
  done
  local kpi; kpi=$(base64 < astra-apps/hrm/wasm/modules/courier-kpi.wasm | tr -d '\n')
  for c in \
    '{"abbr":"BASIC","component_name":"Lương cơ bản","component_type":"Earning","calculation":"Percent of Base","rate":100,"depends_on_payment_days":true,"account":"642"}' \
    '{"abbr":"LUNCH","component_name":"Phụ cấp ăn trưa","component_type":"Earning","calculation":"Fixed","amount":730000,"depends_on_payment_days":true,"account":"642"}' \
    '{"abbr":"FUEL","component_name":"Phụ cấp xăng xe","component_type":"Earning","calculation":"Fixed","amount":500000,"depends_on_payment_days":true,"account":"642"}' \
    "{\"abbr\":\"KPI\",\"component_name\":\"Thưởng sản lượng giao hàng\",\"component_type\":\"Earning\",\"calculation\":\"Wasm\",\"depends_on_payment_days\":false,\"account\":\"642\",\"module_wasm\":\"$kpi\"}" \
    '{"abbr":"BONUS","component_name":"Thưởng","component_type":"Earning","calculation":"Fixed","depends_on_payment_days":false,"account":"642"}' \
    '{"abbr":"BHXH","component_name":"BHXH người lao động (8%)","component_type":"Deduction","calculation":"Percent of Base","rate":8,"base_cap":46800000,"depends_on_payment_days":false,"account":"3383"}' \
    '{"abbr":"BHYT","component_name":"BHYT người lao động (1.5%)","component_type":"Deduction","calculation":"Percent of Base","rate":1.5,"base_cap":46800000,"depends_on_payment_days":false,"account":"3384"}' \
    '{"abbr":"BHTN","component_name":"BHTN người lao động (1%)","component_type":"Deduction","calculation":"Percent of Base","rate":1,"base_cap":106200000,"depends_on_payment_days":false,"account":"3386"}'; do
    call "$T" POST /api/doc/SalaryComponent "$c" >/dev/null
  done
  local ins='[{"component":"BHXH"},{"component":"BHYT"},{"component":"BHTN"}]'
  hrm_submit "$T" SalaryStructure "{\"code\":\"COURIER\",\"structure_name\":\"Bưu tá giao nhận\",\"earnings\":[{\"component\":\"BASIC\"},{\"component\":\"LUNCH\"},{\"component\":\"FUEL\"},{\"component\":\"KPI\"}],\"deductions\":$ins}" >/dev/null
  hrm_submit "$T" SalaryStructure "{\"code\":\"OFFICE\",\"structure_name\":\"Văn phòng và kho\",\"earnings\":[{\"component\":\"BASIC\"},{\"component\":\"LUNCH\"}],\"deductions\":$ins}" >/dev/null
  echo "  payroll: accounts 334, 3383, 3384, 3386, 642; 8 salary components (KPI in WebAssembly); structures COURIER, OFFICE"
}
hrm_employee() { # hrm_employee TENANT ID NAME DESIGNATION DEPARTMENT JOINED STRUCTURE BASE
  call "$1" POST /api/doc/Employee "{\"employee_id\":\"$2\",\"employee_name\":\"$3\",\"designation\":\"$4\",\"department\":\"$5\",\"date_of_joining\":\"$6\"}" >/dev/null
  hrm_submit "$1" SalaryStructureAssignment "{\"employee\":\"$2\",\"salary_structure\":\"$7\",\"from_date\":\"2026-01-01\",\"base\":$8}" >/dev/null
  printf '  employee %s %s (%s, %s, base %s)\n' "$2" "$3" "$4" "$7" "$8"
}
hrm_attendance() { # hrm_attendance TENANT EMPLOYEE [DAY=STATUS...] — every working day of September 2026 (Mon–Sat)
  local T=$1 e=$2; shift 2
  local -A special=()
  local o; for o in "$@"; do special[${o%%=*}]=${o#*=}; done
  local d day status pieces body n=0 total=0
  for d in $(seq 1 30); do
    day=$(printf '2026-09-%02d' "$d")
    (( (d - 1) % 7 == 5 )) && continue   # 1 September 2026 is a Tuesday, so the 6th, 13th, 20th and 27th are Sundays
    status=${special[$d]:-Present}
    pieces=$(( 36 + d * 7 % 11 ))
    case $status in
      Present)    body="{\"employee\":\"$e\",\"attendance_date\":\"$day\",\"status\":\"Present\",\"delivered_pieces\":$pieces}"; total=$(( total + pieces )) ;;
      "Half Day") body="{\"employee\":\"$e\",\"attendance_date\":\"$day\",\"status\":\"Half Day\",\"delivered_pieces\":$(( pieces / 2 ))}"; total=$(( total + pieces / 2 )) ;;
      *)          body="{\"employee\":\"$e\",\"attendance_date\":\"$day\",\"status\":\"$status\"}" ;;
    esac
    hrm_submit "$T" Attendance "$body" >/dev/null; n=$(( n + 1 ))
  done
  printf '  attendance %s, September 2026: %d working days marked, %d pieces delivered\n' "$e" "$n" "$total"
}

seed_hrm_saigon() {
  local T=saigon-express
  echo "Tenant ${T}: HRM"
  hrm_setup $T
  hrm_employee $T NV001 "Nguyễn Văn An"  Courier     "Giao nhận Quận 1" 2024-03-01 COURIER 7000000
  hrm_employee $T NV002 "Trần Thị Bình"  Courier     "Giao nhận Quận 3" 2025-06-01 COURIER 6500000
  hrm_employee $T NV003 "Lê Hoàng Cường" Office      "Kế toán"          2026-09-14 OFFICE  12000000
  hrm_employee $T NV004 "Phạm Minh Dũng" "Hub Staff" "Hub Tân Bình"     2025-01-01 OFFICE  8000000
  hrm_attendance $T NV001
  hrm_attendance $T NV002 15=Absent 22="Half Day"
  hrm_submit $T Attendance '{"employee":"NV004","attendance_date":"2026-09-29","status":"On Leave","leave_type":"Leave Without Pay"}' >/dev/null
  echo "  attendance NV004: unpaid leave on 2026-09-29 (unmarked working days count as worked)"
  hrm_submit $T AdditionalSalary '{"employee":"NV003","salary_component":"BONUS","amount":500000,"payroll_date":"2026-09-30","reason":"Thưởng hoàn thành hội nhập"}' >/dev/null
  echo "  additional salary: NV003 bonus 500,000 in September; scripts/demo-hrm.sh runs the payroll"
}

seed_hrm_hanoi() {
  local T=hanoi-express
  echo "Tenant ${T}: HRM"
  hrm_setup $T
  hrm_employee $T NV001 "Đặng Quốc Khánh" Courier "Giao nhận Hoàn Kiếm" 2025-02-10 COURIER 6800000
  hrm_employee $T NV002 "Vũ Thu Hằng"     Office  "Chăm sóc khách hàng" 2024-08-01 OFFICE  10000000
}

# STMS (vehicle routing): one fictional hub per tenant, three vehicles, twenty stops for STMS_DATE (default: today in
# Vietnam). Every fifth stop must be served 09:00–11:00, three are VIP; 452 kg in total, so the plan stays feasible
# when one vehicle breaks down (scripts/demo-stms.sh).
STMS_DATE="${STMS_DATE:-$(TZ=Asia/Ho_Chi_Minh date +%F)}"
# stms_seed TENANT HUB HUB_NAME CITY LAT LON "PLATE:KG ..." STOP... — each STOP is "label|lat|lon[|waybill]"
stms_seed() {
  local T=$1 hub=$2 name=$3 city=$4 lat=$5 lon=$6 fleet=$7; shift 7
  call "$T" POST /api/doc/Hub "{\"code\":\"$hub\",\"hub_name\":\"$name\",\"city\":\"$city\",\"latitude\":$lat,\"longitude\":$lon,\"open_from\":\"07:00\",\"open_to\":\"19:00\"}" >/dev/null
  local v plate kg
  for v in $fleet; do
    plate=${v%%:*}; kg=${v##*:}
    call "$T" POST /api/doc/Vehicle "{\"plate\":\"$plate\",\"depot_hub\":\"$hub\",\"capacity_kg\":$kg}" >/dev/null
  done
  local i=0 s label slat slon wb priority from to body kg10
  for s in "$@"; do
    IFS='|' read -r label slat slon wb <<<"$s"
    priority=Normal; (( i % 7 == 3 )) && priority=VIP
    from=08:00; to=17:00; (( i % 5 == 0 )) && { from=09:00; to=11:00; }
    kg10=$(( 150 + i * 8 ))   # 15.0 kg, then 0.8 kg more per stop
    body="{\"hub\":\"$hub\",\"plan_date\":\"$STMS_DATE\",\"label\":\"$label\",\"priority\":\"$priority\",\"latitude\":$slat,\"longitude\":$slon,"
    body+="\"demand_kg\":$(( kg10 / 10 )).$(( kg10 % 10 )),\"window_from\":\"$from\",\"window_to\":\"$to\",\"service_minutes\":5"
    [[ -n $wb ]] && body+=",\"waybill\":\"$wb\""
    call "$T" POST /api/doc/DeliveryStop "$body}" >/dev/null
    i=$(( i + 1 ))
  done
  echo "  STMS: hub $hub ($name), vehicles (plate:kg) $fleet, $i stops for $STMS_DATE"
}

seed_stms() {
  local wb
  wb=$(call saigon-express GET "/api/doc/Waybill?ops_status=At%20Hub&limit=1" | jq -r '.[0].name // empty')
  echo "Tenant saigon-express"
  stms_seed saigon-express HCM-TB "Kho Tân Bình" "TP.HCM" 10.8016 106.6527 "51C-12345:300 51C-67890:250 51D-24680:250" \
    "Shop Hoa Mai – Nguyễn Huệ, Q1|10.7769|106.7009" "Tiệm bánh Hạnh Phúc – Q10|10.7626|106.6602" \
    "Kiện gốm – Võ Văn Tần, Q3|10.7867|106.6779|$wb" "Nhà thuốc An Khang – Thủ Đức|10.8505|106.7717" \
    "Mỹ phẩm Lan Chi – Q7|10.7380|106.7220" "Văn phòng phẩm Trí Đức – Tân Bình|10.8231|106.6297" \
    "Vải sợi Kim Thành – Q11|10.7546|106.6370" "Điện máy Phú Gia – Bình Thạnh|10.7950|106.7218" \
    "Nông sản Sạch Xanh – Thủ Đức|10.8700|106.8030" "Phụ tùng Minh Phát – Q7|10.7300|106.7040" \
    "Sách Phương Nam – Bình Thạnh|10.8120|106.7100" "Quà tặng Mộc – Q1|10.7720|106.6880" \
    "Gốm sứ Lái Thiêu – Gò Vấp|10.8390|106.6650" "Thời trang Ngọc Diệp – Q5|10.7600|106.6800" \
    "Linh kiện Tân Tiến – Tân Bình|10.8050|106.6380" "Trà sữa Mây – Tân Bình|10.7900|106.6550" \
    "Siêu thị mini Xanh – Thủ Đức|10.8450|106.7550" "Đồ chơi Bé Yêu – Q4|10.7480|106.6950" \
    "Nội thất Gỗ Việt – Thảo Điền|10.8000|106.7400" "Hoa tươi Đà Lạt – Q1|10.7680|106.6950"
  echo "Tenant hanoi-express"
  stms_seed hanoi-express HN-LB "Kho Long Biên" "Hà Nội" 21.0466 105.8888 "29C-11223:300 29C-44556:250 29D-77889:250" \
    "Nhà sách Tràng Tiền – Hoàn Kiếm|21.0287|105.8524" "Cà phê Phố Cổ – Ba Đình|21.0340|105.8140" \
    "Điện thoại Minh Anh – Cầu Giấy|21.0362|105.7906" "Hoa Tây Hồ – Quảng An|21.0700|105.8180" \
    "Gạo sạch Hà Đông|20.9714|105.7788" "Bánh cốm Hàng Than – Hai Bà Trưng|21.0050|105.8450" \
    "Thiết bị y tế An Bình – Đống Đa|21.0180|105.8290" "Siêu thị mini Long Biên|21.0450|105.8750" \
    "Đồ gỗ Hoàng Mai|20.9900|105.8600" "Văn phòng phẩm Thanh Xuân|21.0010|105.8200" \
    "Mỹ phẩm Thu Hà – Bắc Từ Liêm|21.0590|105.7800" "Lụa Hà Đông – Phố Huế|21.0230|105.8560" \
    "Linh kiện Nhân Chính|21.0080|105.8000" "Trà Thái Nguyên – Quán Thánh|21.0400|105.8460" \
    "Phụ tùng Đức Giang|21.0660|105.9000" "Thời trang Mỹ Đình|21.0280|105.7700" \
    "Sơn mài Giáp Bát|20.9800|105.8400" "Quà lưu niệm Hàng Bông|21.0310|105.8480" \
    "Điện máy Minh Khai|21.0150|105.8650" "Cá cảnh Yên Phụ|21.0520|105.8350"
}

seed_saigon
seed_hanoi
seed_hrm_saigon
seed_hrm_hanoi
seed_stms
echo
echo "Done. Open ${BASE}/desk/  ·  compare tenants: ${BASE}/desk/compare.html"
