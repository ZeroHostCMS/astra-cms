**Overlay (Lớp phủ tùy biến)** trong Astra giống như **một tấm kính trong suốt đặt đè lên bản vẽ thiết kế gốc**, hoặc giống như cơ chế `docker-compose.override.yml` / CSS Override mà anh em làm kỹ thuật vẫn dùng:

Thay vì phải **sửa trực tiếp vào code gốc (fork code)** của phân hệ `BMS` hay `OMS` khi một khách hàng (Tenant) hoặc một chi nhánh quốc gia có yêu cầu riêng — việc sẽ khiến bạn **không thể nâng cấp bản Core sau này** vì sợ xung đột code — thì bạn giữ nguyên bản gốc 100% và chỉ viết một file mô tả **"những điểm khác biệt"** đè lên trên.

Ví dụ một cách trực Cơ chế **Overlay + Wasm**:

---

### 1. Ví dụ Thực tế Dễ Hình Dung (Hoá đơn ở Việt Nam vs. Campuchia)

Giả sử bạn có **DocType gốc** là `Sales Invoice` (Hoá đơn bán hàng) nằm trong plugin lõi `BMS` (Tier 0). Bản gốc này chỉ có các trường chuẩn quốc tế: `customer`, `grand_total`, `remarks`.

Bây giờ, bạn triển khai cho 2 thị trường (hoặc 2 Tenant khác nhau) với yêu cầu đặc thù:

* **Tenant A (Việt Nam - `acme-vn`):** Luật thuế Việt Nam bắt buộc hoá đơn từ 20 triệu VNĐ trở lên phải có trường **"Mã hoá đơn điện tử" (`e_invoice_code`)**, trường ghi chú (`remarks`) bắt buộc phải nhập, và phải cấp quyền xem cho vai trò `Tax Officer`.


* **Tenant B (Campuchia):** Cần thêm trường **"Tỷ giá KHR/USD"** và một logic kiểm tra riêng.

Nếu làm theo cách truyền thống: Bạn phải sửa code Java của `BMS`, chạy lệnh `ALTER TABLE` thêm cột vào Database, rồi build lại cả hệ thống.

Với Overlay + Wasm trong Astra, bạn không đụng một dòng nào vào `BMS` gốc:

1. **Tạo lớp phủ dữ liệu & giao diện (`sales-invoice.overlay.yaml`):**
Bạn chỉ cần khai báo một file YAML nhỏ gắn riêng cho Tenant `acme-vn`:


* **Thêm trường mới (`add-fields`):** Thêm trường `e_invoice_code` (Mã hoá đơn điện tử) nằm ngay dưới trường `posting_date`. Vì Astra lưu dữ liệu dạng **Zero-DDL JSONB**, trường này xuất hiện ngay lập tức trên API và UI mà **không cần chạy lệnh `ALTER TABLE` gây khóa bảng PostgreSQL**.


* **Sửa thuộc tính trường cũ (`set-properties`):** Đổi thuộc tính trường `remarks` từ không bắt buộc thành bắt buộc (`required: true`).


* **Thêm quyền (`add-permissions`):** Cho phép role `Tax Officer` được quyền đọc (`read: true`).




2. **Đính kèm logic kiểm tra bằng Wasm (`scripts`):**
* Trong file Overlay đó, bạn trỏ sự kiện `validate` vào một file Wasm nhỏ (`com.acme.e-invoice-check` viết bằng Rust/JS biên dịch ra `.wasm`).


* File Wasm này kiểm tra: *"Nếu `grand_total >= 20,000,000` mà `e_invoice_code` bị trống thì báo lỗi chặn lại"*.





---

### 2. Bên dưới Hệ thống (Astra Kernel) Hoạt động Như Thế Nào?

Khi một request của Tenant `acme-vn` gọi vào hệ thống, Astra xử lý qua 3 bước cực nhanh:

```text
[1. Bản gốc (Base DocType)]       [2. Lớp phủ (Tenant Overlay)]
  Sales Invoice (BMS Core)   +     sales-invoice.overlay.yaml (acme-vn)
  - customer                       - +field: e_invoice_code
  - grand_total                    - *prop: remarks (required=true)
  - remarks (required=false)       - +hook: validate -> e-invoice-check.wasm
                 \                        /
                  v                      v
        [3. Bản Hợp Nhất trong RAM (Effective Metadata)]
          Lưu vào L1/L2 Cache theo khóa: (tenant: acme-vn, version)
          -> Tự động sinh lại REST API, GraphQL, Form UI và chạy Wasm!

```

* **Trộn ở tầng RAM (Metadata Merge):** Ngay khi load metadata, Kernel lấy `Base DocType` trộn với `Overlay` của riêng Tenant đó để tạo ra bản **Effective Metadata** và lưu vào bộ nhớ đệm L1/L2 theo từng `(tenant, metadata-version)`. Tenant khác gọi vào vẫn nhìn thấy bản `Sales Invoice` gốc nguyên vẹn.


* **Thực thi an toàn trong hộp cát (Wasm Sandbox):** Khi người dùng bấm Lưu hoá đơn, Kernel chạy các hook gốc của `BMS` trước, sau đó gọi tiếp module Wasm được gắn trong Overlay (chạy bằng Chicory/Extism trong vài mili-giây, giới hạn cứng RAM và CPU, nếu code Wasm lỗi cũng không thể làm sập JVM của hệ thống).



---

### 3. Tại sao Gộp "Overlay + Wasm" lại Tiết Kiệm Thời Gian Rollout Cực Lớn?

Quyết định triển khai **Overlay đi kèm Wasm** của bạn giải quyết được bài toán khó nhất khi vận hành hệ thống lớn:

1. **Zero-Downtime & Hot-Swap trong vài giây (Không cần Build/Deploy lại Docker Image):**
Nếu viết logic tùy biến bằng Java ở Tier 0, mỗi lần sửa 1 câu điều kiện cho khách hàng là phải `astra build`, đẩy Docker Image mới và Rolling Update cả cụm Kubernetes (mất vài phút). Với **Overlay (YAML) + Wasm (Tier 2)**, cả hai đều được coi là dữ liệu/tài nguyên nạp động lúc runtime: bạn đẩy lên là hệ thống **Hot-swap trong vài giây, downtime = 0**.


2. **Nâng cấp Core thoải mái không sợ vỡ (Survive Upgrades):**
Tháng sau, đội Core của bạn nâng cấp phân hệ `BMS` từ bản `1.2` lên `1.3`. Vì mọi tùy biến của khách hàng nằm tách biệt hoàn toàn ở lớp `Overlay + Wasm`, bạn cứ thế nâng cấp `BMS` mà không bị conflict code. Hơn nữa, bộ kiểm thử `conformance check` của Astra sẽ tự động lấy bản `BMS 1.3` lắp thử với tất cả các `Overlay` hiện có xem có trường nào bị xóa nhầm không trước khi cho phép rollout.


3. **Sẵn sàng cho AI tự viết tính năng (AI-Generated Code):**
Sau này khi bạn dùng AI Agent để tự động sinh thêm trường dữ liệu và viết logic nghiệp vụ theo yêu cầu khách hàng, AI chỉ cần sinh ra 1 file `.overlay.yaml` và 1 hàm `.wasm` chạy trong sandbox cô lập hoàn toàn — vừa nhanh tức thì vừa an toàn tuyệt đối cho hệ thống lõi.
---

### 4. ABI của các slot Wasm (đọc trước khi viết một module)

Mọi module Tier 2 chạy trong `WasmSandbox` (Chicory): **không có host function** (module có `import` bị từ chối), bộ nhớ tối đa 16 trang (1 MiB), mỗi lời gọi một instance mới và một deadline. Module được phát hành qua `AppRelease` (lưu là tự kiểm tra, admin Submit là duyệt, canary theo pod rồi Live, lỗi vượt ngân sách thì tự rollback). Build: `rustup target add wasm32-unknown-unknown`, `cargo build --release --target wasm32-unknown-unknown`, profile `opt-level = "s"`, `lto = true`, `panic = "abort"` như `astra-apps/express/wasm/Cargo.toml`.

#### 4.1 `pricing.base` không phải Wasm

Giá chung của tenant là luật Drools (Tier 0, luật là dữ liệu), xem [pricing-rules.md](pricing-rules.md). Module Wasm của khách **kế thừa** các dòng giá mà tầng luật đã tính.

#### 4.2 Slot `pricing.customer`: Khai giá Astra (giá riêng của một khách)

Mỗi đơn của khách được định giá ba tầng: luật (`pricing.base`) → `PricingRule` đang bật thay dòng `base` → **plugin của khách**. Plugin nhận các dòng giá và chỉ trả **thao tác trên dòng**, không tính lại từ đầu; dòng không nhắc tới giữ nguyên, kể cả dòng mà người dùng thêm vào luật sau này.

Viết bằng PDK `astra-apps/express/wasm/astra-pricing-pdk` (no_std, không cần biết wire format):

```rust
#![no_std]
use astra_pricing_pdk::{export_adjust, vec, Op, Quote};
use astra_pricing_pdk::vec::Vec;

fn adjust(q: &Quote) -> Vec<Op> {
    let mut ops = vec![];
    if q.monthly_orders > 500 { ops.push(Op::scale("base", 920, "Phụ lục §2: −8 % cước nền")); }
    if q.has("cod_fee") && q.return_rate_bp < 500 { ops.push(Op::remove("cod_fee", "Phụ lục §3: miễn COD")); }
    ops
}
export_adjust!(adjust);   // sinh export alloc + adjust, allocator, panic handler
```

`Cargo.toml` của module: `crate-type = ["cdylib"]`, `astra-pricing-pdk = { path = "../astra-pricing-pdk" }`, thêm crate vào `members` của workspace. Ví dụ đầy đủ: `khaigia-shopabc/`.

| `Quote` | Nghĩa |
| --- | --- |
| `zone`, `service` | `Same city` / `Domestic` / `Remote`; `Standard` / `Express` (`q.express()`) |
| `weight_g`, `volumetric_g` | cân thật; cân quy đổi D×R×C/6000 (0 khi đơn không có kích thước), gram |
| `cod`, `month`, `day` | tiền thu hộ (đ); ngày tạo đơn |
| `monthly_orders`, `return_rate_bp` | hồ sơ khách 30 ngày: số đơn đã submit; tỉ lệ hoàn theo phần vạn (310 = 3,10 %) |
| `floor` | sàn tổng do luật đặt |
| `lines` | các dòng của tầng luật: `code`, `amount`, `locked`, `source`, `label`; `q.amount("base")`, `q.has("cod_fee")`, `q.total()` |

`code` của dòng là hợp đồng giữa luật và plugin: `base`, `express`, `cod_fee`, `peak`, … (đổi tên `code` trong luật là thay đổi phá vỡ).

| `Op` | Tác dụng |
| --- | --- |
| `Op::scale(code, permille, reason)` | nhân dòng với permille/1000 (920 = −8 %), 0–2000 |
| `Op::set(code, amount, reason)` | đặt lại số tiền của dòng |
| `Op::remove(code, reason)` | bỏ dòng (dòng giữ lại với số tiền 0 để thấy nguồn gốc) |
| `Op::add(code, label, amount, reason)` | thêm dòng mới; `code` chưa có, dạng `[a-z][a-z0-9_]*` |

**Rào chắn ở host** (một thao tác sai là bỏ cả câu trả lời, đơn dùng giá tầng luật, `priced_by` ghi lý do, release bị đếm lỗi): không sửa dòng `locked`, không nhắc `code` không tồn tại, không `add` trùng `code`, không số âm, hệ số ngoài 0–2000 ‰, tổng không dưới `floor`. Deadline 50 ms. Tổng cuối được làm tròn lên nghìn đồng bằng một dòng `rounding`.

Mỗi dòng của đơn ghi vào `price_breakdown`: `source` (`rule:base-remote`, `pricing-rule:<tên>`, `wasm:khaigia-shopabc@1.0.0`), và nếu bị thao tác thì `op`, `before`, `reason`.

Wire format (PDK và host lo, ghi lại để đối chiếu): host gọi export `alloc(len) -> ptr`, ghi đầu vào UTF-8 vào bộ nhớ của module, gọi `adjust(ptr, len) -> i64`, kết quả là `ptr << 32 | len` của câu trả lời (tối đa 64 KiB, kiểm biên). Đầu vào: một dòng `ctx` với các trường `key=value` cách nhau bằng tab, rồi mỗi dòng giá `line<TAB>code<TAB>amount<TAB>locked(0|1)<TAB>source<TAB>label`. Câu trả lời: mỗi dòng một thao tác `scale|set|remove|add<TAB>code<TAB>…<TAB>reason`.

Công cụ MCP: `customer_pricing_profile` (hồ sơ khách 30 ngày), `quote_backtest` (chạy module trên đơn 30 ngày của khách, so doanh thu trước/sau), `release_propose` (tạo bản nháp, ghi `customer` để chỉ áp cho khách đó), `release_status`. Tham số file (module `.wasm`) gửi qua `scripts/astra-mcp.sh <tool> key@=file.wasm`, không gõ base64 tay.

#### 4.3 Slot `ops.anomaly`: bộ phát hiện bất thường

Một hàm chấm một sự kiện, chỉ số nguyên, không bộ nhớ chia sẻ:

```rust
#[unsafe(no_mangle)]
pub extern "C" fn check(kind: i64, a: i64, b: i64, c: i64, d: i64) -> i64   // 0 = bình thường, khác 0 = severity * 100 + reason
```

| `kind` | Sự kiện | `a` | `b` | `c` | Cảnh báo giao cho |
| --- | --- | --- | --- | --- | --- |
| 1 | quét kiện ở hub có cân lại | cân khai báo (g) | cân đo tại hub (g) | 0 | OPS (`ops`) |
| 2 | lưu phiếu chi COD cho shop (`CODRemittance`) | COD đang nợ shop (đ) | số tiền chi (đ) | số ngày từ `collected_until` | Kế toán (`accountant`) |
| 3 | giao thất bại | số lần thất bại của kiện | số lần thất bại của bưu tá trong 7 ngày | số lần giao của bưu tá trong 7 ngày | CSKH (`care`) |

`d` để dành, luôn 0. `severity` 1–3; `reason` là mã do module tự đặt (0–99), host ghi kèm `detail` là các con số của sự kiện. Deadline 20 ms; module lỗi hoặc quá hạn **không chặn** sự kiện, chỉ đếm lỗi cho release. Self-test khi lưu chấm ba sự kiện bình thường và ba sự kiện lạ.

Công cụ MCP: `ops_snapshot` (số liệu 30 ngày theo hub, phiếu COD, bưu tá), `anomaly_backtest` (chạy module trên tối đa 3.000 sự kiện 30 ngày, đếm cảnh báo, trúng/sai/bỏ sót theo nhãn của dữ liệu seed), `my_alerts` (cảnh báo giao cho vai trò của người gọi), `release_propose`, `release_status` (kèm số cảnh báo theo loại, tỉ lệ bị bác và ghi chú khi bác, để viết bản kế tiếp). Ví dụ tham chiếu: `astra-apps/express/wasm/anomaly-ops/`.
