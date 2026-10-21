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