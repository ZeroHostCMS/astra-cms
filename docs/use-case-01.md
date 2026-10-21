Bản đề xuất triển khai trên nền tảng **Astra Platform** (Java 25 + Quarkus, DocType Engine Zero-DDL, 3-Tier Plugin Isolation, Apache Seata, Compliance-by-Design) với mô hình **Multi-Schema Ownership + Rust CDC (`supabase/etl`) + Sync-Aware LSN + Keycloak Deep Auth** để triển khai 7 phân hệ của 1 hệ thống chuyển phát.

---

# PHẦN 1: KIẾN TRÚC TỔNG THỂ HỆ THỐNG (OVERALL ARCHITECTURE)

### 1. Sơ đồ Kiến trúc Hợp nhất (Astra Core + 7 Enterprise Apps)

```text
+---------------------------------------------------------------------------------------------------+
|                   CLIENT LAYER & API GATEWAY (Keycloak OIDC + Sync-Aware LSN)                     |
|  Desk UI (WCAG 2.2 AA) • REST (/api/doc/<DocType>) • GraphQL • gRPC • MCP Endpoint (AI Agents)    |
|  [Header In/Out: Authorization (Bearer + Scope), X-Astra-Tenant, X-Tx-LSN / X-Required-LSN]       |
+---------------------------------------------------------------------------------------------------+
                                                 |
+---------------------------------------------------------------------------------------------------+
|              ASTRA KERNEL PODS (Java 25 LTS • Virtual Threads • Gen-ZGC • Quarkus 3.x)            |
|                                                                                                   |
|  +---------------------------------------------------------------------------------------------+  |
|  | 1. KERNEL GUARD & CONTEXT (ScopedValue)                                                     |  |
|  | • TenantResolver + RLS Guard (Fail-closed)  • Keycloak Policy Enforcer (RBAC/ABAC/Scope)    |  |
|  | • Sync-Aware LSN Interceptor (Watermark)    • Compliance & Audit Hash-Chain (SOC2/PDPL)     |  |
|  +---------------------------------------------------------------------------------------------+  |
|  +---------------------------------------------------------------------------------------------+  |
|  | 2. DOCTYPE ENGINE (Multi-Schema Aware)                                                      |  |
|  | • Storage: jsonb (Zero-DDL + Expression/Materialised Index) | ledger (Append-only)          |  |
|  | • Lifecycle: Draft(0) -> Submitted(1) -> Cancelled(2) -> Amend | Tenant Overlays (YAML)     |  |
|  +---------------------------------------------------------------------------------------------+  |
|  +---------------------------------------------------------------------------------------------+  |
|  | 3. THREE-TIER PLUGIN HOST ("ASTRA APPS")                                                    |  |
|  | • Tier 0 (Shared Build-time CDI/Jandex): [OMS] [OPS] [BMS] [SCS] [STMS] [CARE] [NOC]        |  |
|  | • Tier 1 (Isolated JVM - MethodHandle): Localization (VN E-Invoice, Tax), Custom Connectors |  |
|  | • Tier 2 (Wasm Sandbox - Chicory/Extism): Tenant Server Scripts, Pricing Rules, AI Code     |  |
|  +---------------------------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------------------------------+
         | (JTA / Seata AT & TCC)                         | (WAL Logical Replication)
         v                                                v
+----------------------------------------+      +---------------------------------------------------+
|   POSTGRESQL 16+ & REDIS CLUSTER (HA)  |      |      RUST CDC PIPELINE (supabase/etl Custom)      |
| • 1 App = 1 Schema (Strict Ownership): |      | • Đọc WAL trực tiếp theo LSN, độ trễ < 10ms       |
|   schema_oms, schema_ops, schema_bms,  | ===> | • Đồng bộ bảng Replica (replica_*) giữa các Schema|
|   schema_scs, schema_stms, schema_care |      | • Cập nhật bảng _cdc_sync_watermark (last_lsn)    |
| • RLS (tenant_id) + Partitioning       |      | • Stream Load realtime sang Apache Doris          |
| • Redis: L2 Cache, Series, Seata TCC   |      +---------------------------------------------------+
+----------------------------------------+                                |
                                                                          v
                                                +---------------------------------------------------+
                                                |        APACHE DORIS (OLAP) & AI INFRASTRUCTURE    |
                                                | • Lưu trữ dữ liệu lớn phục vụ NOC Insights        |
                                                | • kết nối trực tiếp với astra-ai (MCP + LLM GW)   |
                                                +---------------------------------------------------+

```

---

### 2. Năm Trụ cột Kiến trúc Cốt lõi (Core Architectural Pillars)

#### A. Mô hình Lưu trữ Lai: Multi-Schema + Astra DocType Storage (`jsonb` | `ledger` | `hot-column`)

Thay vì đặt tất cả các phân hệ vào chung schema `public`, chúng ta mở rộng thuộc tính `schema` vào định nghĩa DocType của Astra:

* **Cô lập theo Schema (`schema_<app>`):** Mỗi phân hệ (`OMS`, `OPS`, `BMS`, `SCS`, `STMS`, `CARE`) sở hữu độc quyền 1 Schema riêng trên PostgreSQL 16+ kèm RLS (`tenant_id`).


* **3 Chiến lược lưu trữ bên trong mỗi Schema:**
1. **`storage: jsonb` (Zero-DDL):** Dành cho chứng từ, danh mục, cấu hình cần tùy biến trường liên tục qua `Overlay` mà không bao giờ chạy lệnh `ALTER TABLE` gây khóa bảng. Các trường hay lọc/sắp xếp dùng `projected: true` (Expression Index `CONCURRENTLY`) hoặc `projected: materialised`.


2. **`storage: ledger` (Append-only Relational):** Dành cho sổ cái kế toán (`GL Entry`), sổ kho/chuyển phát, bảng lương (`Payroll Ledger`) và `Audit Trail`. Bảng có cột cố định, phân vùng theo kỳ (Partition by Period), không bao giờ `UPDATE`/`DELETE` (chỉ ghi bút toán đảo khi hủy).


3. **Hot-Field Materialisation:** Với các bảng thay đổi trạng thái liên tục hàng chục lần/ngày (như trạng thái bưu kiện trong `OPS` hoặc vị trí xe trong `STMS`), tách các cột nóng ra cột vật lý (Materialised Columns) hoặc bảng phụ hẹp với `fillfactor < 100` để tận dụng PostgreSQL HOT Update, tránh phình TOAST JSONB.





#### B. Quản lý Giao dịch Đa tầng (Multi-Tier Transaction Guarantee)

Kết hợp giữa cơ chế Local/Cross-Schema của PostgreSQL, Apache Seata 2.6 của Astra và Rust CDC:

1. **Giao dịch Nội bộ & Liên Schema Tức thời (ACID 100%):**
* Khi các Core App chạy ở **Tier 0 (Shared JVM)** trên cùng cụm PostgreSQL: Sử dụng `@Transactional` (Narayana JTA) gọi qua `astra-spi` (hoặc `@AstraGlobalTransactional` với **Seata AT Mode** + **Redis TCC Mode** khi cần giữ chỗ tồn kho/hạn mức tức thời).


* Cơ chế **Smart Bypass** của Astra đảm bảo các thao tác lưu đơn lẻ bỏ qua Seata Coordinator, giữ độ trễ `p99 < 15ms`.




2. **Giao dịch Bất đồng bộ & Đồng bộ Đọc (Rust CDC + Sync-Aware LSN):**
* Dữ liệu tham chiếu chéo giữa các phân hệ (ví dụ: `BMS` cần thông tin đơn hàng từ `OMS` để lên hóa đơn; `CARE` cần hành trình đơn từ `OPS`) được **Rust CDC (`supabase/etl`)** đọc từ PostgreSQL WAL và ghi sang các bảng `replica_*` tại schema đích kèm cập nhật `last_synced_lsn` vào bảng `_cdc_sync_watermark`.
* **Sync-Aware Guard:** Khi một mutation hoàn tất, Astra Kernel đính kèm `X-Tx-LSN` (lấy từ `pg_current_wal_lsn()`) vào response. Khi Client chuyển sang phân hệ khác và gửi `X-Required-LSN`, Filter của Astra kiểm tra bộ nhớ đệm L1/Watermark: nếu `last_synced_lsn < X-Required-LSN`, hệ thống trả về cảnh báo trạng thái đồng bộ (`202 Sync Pending`) và tự động mở khóa ngay khi Rust CDC đuổi kịp sau vài mili-giây.



#### C. Kiến trúc Plugin 3 Tầng (Three-Tier Isolation)



* **Tier 0 – Shared (Build-time Quarkus CDI + Jandex):** Biên dịch trực tiếp vào image qua `astra build`, gọi hàm trực tiếp (zero-copy), hỗ trợ GraalVM Native. **Dùng cho toàn bộ lõi của 7 phân hệ (`OMS`, `OPS`, `BMS`, `SCS`, `STMS`, `CARE`, `NOC`).**


* **Tier 1 – Isolated JVM (Runtime `PluginClassLoader` + `MethodHandle`):** Cho phép Hot-swap không cần khởi động lại pod, cô lập bằng Bulkhead/Circuit Breaker. **Dùng cho các bản địa hóa (VN E-Invoice, Thuế GTGT, cổng thanh toán, kết nối đối tác 3PL).**


* **Tier 2 – Wasm Sandbox (Chicory + Extism ABI):** Sandbox cứng về bộ nhớ và thời gian chạy (`timeout: 50ms`), viết bằng Rust/Go/JS/Python. Dùng cho Server Scripts của từng Tenant, công thức tính cước chuyển phát động (Pricing Rules), hoặc code do AI sinh ra.



#### D. Bảo mật Chiều sâu, Đa thuê bao & Tuân thủ Quốc tế (Security, Multi-Tenancy & Compliance)

* **Keycloak OIDC + ScopedValue Tenancy:** Xác thực qua Keycloak với Fine-Grained Scope cho từng microservice/app. Định danh `Tenant` và `SecurityIdentity` được đóng đinh vào Java 25 `ScopedValue` ngay từ đầu request và lan truyền tự động qua mọi Virtual Thread.


* **Fail-Closed Row-Level Security (RLS):** Mọi câu lệnh SQL đều tự động gắn biến phiên `app.current_tenant` cho PostgreSQL RLS; Kernel từ chối khởi động nếu DB role có quyền `SUPERUSER` hoặc `BYPASSRLS`.


* **Compliance-by-Design (SOC 2, ISO 27001, GDPR, Luật dữ liệu cá nhân VN - PDPL 91/2025/QH15):** Khai báo trực tiếp `pii`, `classification`, `retention`, `encrypt: field`, `residency: vn` ngay trên từng field của file `.doctype.yaml`. Tự động băm chuỗi (Hash-chain) mọi thay đổi vào bảng `Audit Ledger` bất biến và che dấu (redact) PII trước khi gửi sang LLM.



---

# PHẦN 2: HƯỚNG TRIỂN KHAI CỤ THỂ CHO CORE & 7 PHÂN HỆ (IMPLEMENTATION BLUEPRINT)

### 1. Triển khai Nền tảng Lõi (`astra-kernel`, `astra-doctype`, `astra-cdc-sync`)

Trước khi viết các phân hệ nghiệp vụ, cần hoàn thiện 4 mảnh ghép kỹ thuật trong Core của Astra:

1. **Mở rộng Schema cho DocType YAML (`astra-doctype`):**
Thêm thuộc tính `db_schema` vào `DocTypeDefinition`. Khi `DocumentStore` hoặc `LedgerContributor` thực thi truy vấn, nó tự động định tuyến về đúng `<db_schema>.astra_doc` hoặc bảng Ledger của schema đó (ví dụ: `schema_oms.astra_doc`, `schema_bms.gl_entry`).
2. **Tích hợp `SyncAwareFilter` vào `astra-kernel`:**
* Trên đường ra của các lệnh `POST/PUT/DELETE` (hoặc `submit/cancel`), lấy `pg_current_wal_lsn()` gắn vào Header `X-Tx-LSN`.
* Trên đường vào của lệnh `GET`, nếu có Header `X-Required-LSN`, đối chiếu với LSN của schema hiện tại (được Rust CDC cập nhật liên tục vào Redis L2 / bảng `_cdc_sync_watermark`).


3. **Dịch vụ `astra-cdc-worker` (Rust - dựa trên `supabase/etl`):**
* Kết nối vào PostgreSQL qua Logical Replication Slot (`pgoutput`).
* Định nghĩa tệp cấu hình `cdc-routes.yaml` chỉ định: bảng gốc nào (ví dụ `schema_oms.astra_doc` có `doctype = 'WaybillOrder'`) sẽ được chiếu (project) và lọc những trường nào sang bảng đích (`schema_bms.replica_orders`, `schema_care.replica_orders`) và đẩy luồng Stream Load sang **Apache Doris**.



---

### 2. Thiết kế & Triển khai Chi tiết 7 Phân hệ (Astra Apps)

#### Phân hệ 1: `astra-app-oms` (Order Management System – Quản lý Đơn Khách hàng)

* **Schema & Phân tầng:** `schema_oms` | **Tier 0** (Lõi đơn hàng) + **Tier 2 Wasm** (Tính cước động / Khuyến mãi theo từng khách hàng B2B).
* **Core DocTypes:**
* `CustomerOrder` (`kind: transaction`, `storage: jsonb`, `submittable: true`, `naming: "ORD-.YYYYMMDD.-.#####"`).
* `OrderPricingRule` (`kind: setup`, cho phép gắn script Wasm để tính giá cước linh hoạt theo trọng lượng/vùng miền).
* `CustomerContract` & `CODReconciliation` (Đối soát tiền thu hộ COD).


* **Tối ưu Hiệu năng & Luồng dữ liệu:**
* Các trường `customer_id`, `order_status`, `cod_amount`, `created_at` được đánh dấu `projected: materialised` để chịu tải ghi và lọc danh sách triệu dòng với `p99 < 20ms`.


* Khi `CustomerOrder` chuyển sang `on_submit`: kích hoạt `@AstraGlobalTransactional` để kiểm tra hạn mức công nợ khách hàng (qua Redis TCC) và phát sinh bản ghi vận đơn sang `OPS` (qua SPI trong cùng transaction hoặc qua CDC Outbox).


* **Tuân thủ (PDPL/GDPR):** Trường `receiver_phone`, `receiver_address` được gắn cờ `pii: true, encrypt: field, mask: "***last4"`.



#### Phân hệ 2: `astra-app-ops` (Core Express Operations – Lõi Vận hành Chuyển phát)

* **Schema & Phân tầng:** `schema_ops` | **Tier 0** (Yêu cầu độ trễ cực thấp cho máy bắn mã vạch PDA/DWS tại bưu cục và kho phân loại).
* **Core DocTypes:**
* `Waybill` (Vận đơn vận hành - tách trường `current_hub`, `ops_status` thành **Materialised Hot Columns** với `fillfactor = 80` để cập nhật trạng thái liên tục không gây khóa hay phình bảng JSONB).


* `SortingBag` (Bao hàng), `SealManifest` (Bảng kê giao nhận chặng), `ScanEventLedger` (`kind: ledger` – Bảng chỉ ghi thêm `Append-only`, lưu toàn bộ lịch sử quét mã vạch: *Nhận hàng, Nhập kho, Đóng bao, Xuất kho, Giao thành công*).


* **Tối ưu Hiệu năng:**
* Tận dụng tối đa **Java 25 Virtual Threads** (`@RunOnVirtualThread`) kết hợp **Redis Cluster** (thông qua `AstraRedisTccTemplate`) để kiểm tra trùng lặp mã quét và khóa trạng thái bao hàng trong `< 2ms`.


* Mọi sự kiện ghi vào `ScanEventLedger` tự động được Rust CDC bắn sang `CARE` (để CSKH tra cứu tức thời) và `NOC` (để hiển thị bản đồ nhiệt tồn đọng bưu cục trên Apache Doris).



#### Phân hệ 3: `astra-app-bms` (Billing & Accounting Management – Chuẩn Kế toán & Hoá đơn ERPNext)

* **Schema & Phân tầng:** `schema_bms` | **Tier 0** (Lõi Sổ cái & Bút toán kép) + **Tier 1 Isolated JVM** (Plugin tích hợp Hóa đơn điện tử VNPT/Viettel/MISA, Thuế Việt Nam).


* Core DocTypes (Thiết kế Clean-room theo hình mẫu `erp.accounts` của Astra):


* `Account` (Hệ thống tài khoản kế toán dạng cây), `CostCenter`, `FiscalYear`, `PeriodClosingVoucher`.


* `SalesInvoice`, `PurchaseInvoice`, `PaymentEntry`, `JournalEntry` (`kind: transaction`, `submittable: true` – áp dụng chặt chẽ vòng đời `Draft (0) -> Submitted (1) -> Cancelled (2)` và `Amend` tạo phiên bản `INV-0001-1`).


* `GLEntry` (`kind: ledger`, `storage: ledger` – Sổ cái bất biến, phân vùng theo tháng/năm tài chính, sử dụng kiểu `BigDecimal` chuẩn xác tuyệt đối, chỉ ghi qua `GeneralLedger` SPI bên trong `@AstraGlobalTransactional`).




* **Luồng Đồng bộ & Kiểm soát Nội bộ (SOX ITGC):**
* Nhận dữ liệu đơn hàng hoàn tất/COD từ `schema_oms` qua bảng `schema_bms.replica_orders` (do Rust CDC đồng bộ) để tự động gom nhóm và sinh `SalesInvoice` hàng loạt.
* Áp dụng quy tắc **Segregation of Duties (SoD)** ngay trên metadata của DocType: Kế toán viên tạo `PaymentEntry` không được phép bấm `Submit` chính phiếu đó.


* Tùy biến Hóa đơn điện tử theo từng quốc gia/công ty bằng cơ chế **Tenant Overlay** (`sales-invoice.overlay.yaml`) mà không làm ảnh hưởng code lõi của `BMS`.





#### Phân hệ 4: `astra-app-scs` (Salary Calculation System – HRM & Tính lương chuẩn ERPNext)

* **Schema & Phân tầng:** `schema_scs` | **Tier 0** (Hồ sơ nhân sự, Chấm công, Sổ cái lương) + **Tier 2 Wasm** (Công thức tính KPI/Đơn giá giao hàng cho Bưu tá/Tài xế).
* Core DocTypes (Theo hình mẫu `erp.hr` của Astra):


* `Employee`, `Contract`, `Attendance`, `LeaveApplication`.


* `SalaryStructure`, `PayrollEntry`, `SalarySlip` (`submittable: true`).


* `PayrollLedger` (`kind: ledger`, `storage: ledger` – Bảng sổ cái chi lương bất biến).




* **Cơ chế Tính lương Tự động & Kết nối Kế toán:**
* Sản lượng giao/nhận thành công của từng Bưu tá/Tài xế được Rust CDC tổng hợp từ `OPS`/`STMS` sang bảng `schema_scs.replica_staff_performance`.
* Sử dụng `JobContributor` (với cơ chế bầu chọn Leader trên cụm K8s) để chạy Batch tính lương cuối tháng trên Virtual Threads. Công thức thưởng phạt phức tạp được thực thi an toàn qua **Tier 2 Wasm Script**.


* Khi `PayrollEntry` được `Submit`, hệ thống gọi qua SPI của `BMS` (trong cùng `@AstraGlobalTransactional`) để tự động hạch toán chi phí lương và bảo hiểm vào bảng `GLEntry`.





#### Phân hệ 5: `astra-app-noc` (Network Operations Center – Insights, Big Data Doris & MCP Ready)

* **Schema & Phân tầng:** `olap_doris` (Apache Doris) + `astra-ai` | **Tier 0**.


* **Kiến trúc Dữ liệu Lớn & AI Native:**
* **Đường ống Realtime OLAP:** Rust CDC (`supabase/etl`) gom các luồng thay đổi từ `OMS`, `OPS`, `BMS`, `STMS`, `CARE` và đẩy trực tiếp vào **Apache Doris** qua giao thức *Routine Load / Stream Load*, cho phép truy vấn phân tích hàng trăm triệu vận đơn dưới 1 giây.
* Virtual DocTypes (`storage: virtual`): Xây dựng `DorisDocTypeAdapter` triển khai `DocTypeAdapter` của Astra, biến các bảng tổng hợp trên Apache Doris thành các Virtual DocType (`NocSlaMetric`, `HubCongestionReport`, `RevenueRealtimeSummary`). Nhờ đó, toàn bộ cơ chế phân quyền RBAC/RLS và sinh giao diện Dashboard/Report của `astra-desk` đều dùng lại được 100%.


* **Chuẩn hóa MCP Server (`astra-ai`):**
* Mọi báo cáo và công cụ phân tích của NOC được khai báo qua `ToolContributor` và `AgentContributor`, tự động phơi bày thành chuẩn **MCP (Model Context Protocol)** có phân trang và giới hạn theo `ai.mcp.expose` của từng Tenant.


* **Grounding & Bảo mật AI:** Khi ban giám đốc hỏi AI Agent *"Tại sao tỷ lệ rớt SLA tại kho Hà Nội tăng hôm nay?"*, `NOC Agent` gọi MCP Tool truy vấn Apache Doris, tự động ẩn dữ liệu nhạy cảm (PII Redaction) qua `LLM Gateway`, và bắt buộc trả về danh sách `sources` (mã chứng từ/báo cáo gốc) để chống ảo giác (hallucination).







#### Phân hệ 6: `astra-app-stms` (Smart Transport Management System – Điều phối Xe Thông minh + AI)

* **Schema & Phân tầng:** `schema_stms` | **Tier 0** (Tích hợp **Timefold Solver** trên Quarkus) + **Tier 1** (Thuật toán dự báo tải AI).
* **Core DocTypes:**
* `Vehicle`, `Driver`, `RouteTemplate`, `HubGeofence`.
* `DispatchPlan` (`submittable: true` – Lệnh điều xe và ghép chuyến), `TripExecution` (Hành trình thực tế).


* **Tối ưu Hóa & Điều phối Thông minh:**
* Tích hợp **`timefold-solver-quarkus`** trực tiếp vào một `JobContributor` và `ToolContributor` của Astra để giải bài toán **VRP (Vehicle Routing Problem with Time Windows & Capacity)**: tự động tối ưu lộ trình ghép tải, gom hàng giữa hàng trăm điểm bưu cục và xe tải.


* Nhận dữ liệu dự báo sản lượng hàng hóa sắp đổ về từ `OPS` và `OMS` (qua Rust CDC) kết hợp mô hình AI (qua `ModelProvider` của `astra-ai`) để đề xuất phương án điều xe sớm.


* Các phương án điều phối cũng được xuất thành MCP Tool (`stms.optimize_route`) để nhân viên điều vận có thể tương tác với AI Copilot ngay trên màn hình `astra-desk`.





#### Phân hệ 7: `astra-app-care` (CRM & Omnichannel Helpdesk – Quản lý Luồng & Chăm sóc Khách hàng)

* **Schema & Phân tầng:** `schema_care` | **Tier 0** (Lõi CRM, Ticket, SLA Engine) + **Tier 2 Wasm** (Luồng tự động phân bổ Ticket & AI Triage).
* **Core DocTypes:**
* `Lead`, `Opportunity`, `CustomerProfile` (Kế thừa mô hình `erp.crm` của Astra).


* `SupportTicket`, `SlaPolicy`, `ClaimCase` (Phiếu đền bù khiếu nại mất/hỏng hàng, `submittable: true`).


* **Luồng Nghiệp vụ 360 độ Không Độ Trễ:**
* `CARE` đọc trực tiếp từ các bảng `schema_care.replica_waybills` và `schema_care.replica_orders` (được Rust CDC đồng bộ liên tục từ `OMS` và `OPS`). Nhân viên CSKH có thể mở 1 màn hình duy nhất xem toàn bộ lịch sử đơn hàng, hành trình quét mã vạch và trạng thái thanh toán COD mà **không tạo bất kỳ tải truy vấn nào lên DB vận hành của `OPS` hay `OMS**`.
* Khi một `ClaimCase` (Đền bù) được duyệt (`on_submit`), `CARE` gọi qua SPI của `BMS` để tự động tạo phiếu chi hoặc cấn trừ công nợ trên `GLEntry`.


* Tích hợp `AgentContributor` sử dụng kho tri thức vector (`pgvector` trong PostgreSQL 16+) để tự động tóm tắt lịch sử khiếu nại và gợi ý câu trả lời cho tổng đài viên.





---

# PHẦN 3: CẤU TRÚC REPOSITORY & LỘ TRÌNH KHỞI CHẠY (ACTIONABLE ROADMAP)

### 1. Cấu trúc Thư mục Dự án (Mở rộng trực tiếp từ `astra` Thin Slice hiện tại)



```text
astra/
├── astra-bom/                          # Quản lý tập trung version: Quarkus 3.39+, JDK 25, Seata 2.6, Timefold, Chicory
├── astra-spi/                          # Giao diện chuẩn (DocType, Hooks, MCP Tool, LSN Context, Ledger SPI)
├── astra-kernel/                       # Tenant ScopedValue, RLS Guard, SyncAwareLsnFilter, Dispatch Chains
├── astra-doctype/                      # Multi-schema JSONB + Expression/Materialised Projections + Append-only Ledgers
├── astra-plugin-host/                  # Tier 0 (CDI), Tier 1 (Isolated ClassLoader), Tier 2 (Chicory Wasm)
├── astra-tx-seata/                     # Quản lý giao dịch phân tán AT (Postgres) & TCC (Redis) + Smart Bypass
├── astra-compliance/                   # Audit Hash-Chain Ledger, Field Encryption, PDPL/GDPR/SOC2 Engine
├── astra-ai/                           # MCP Server, LLM Gateway (PII Redaction), Agent Runtime, pgvector
├── astra-desk/                         # Giao diện tự sinh từ DocType (Form, List, Kanban, Dashboard WCAG 2.2 AA)
├── astra-cdc-rust/                     # [Rust Workspace] Custom từ supabase/etl: Đọc WAL -> Sync Replica & Apache Doris
├── astra-apps/                         # Các phân hệ nghiệp vụ viết trên chuẩn astra-spi:
│   ├── cms/                            #   Astra CMS (Đã có thin slice Article)
│   ├── oms/                            #   Quản lý đơn hàng (schema_oms)
│   ├── ops/                            #   Core chuyển phát & quét mã vạch (schema_ops)
│   ├── bms/                            #   Kế toán chuẩn ERPNext & Hoá đơn (schema_bms + GL Entry)
│   ├── scs/                            #   HRM & Tính lương chuẩn ERPNext (schema_scs + Payroll Ledger)
│   ├── stms/                           #   Điều phối xe thông minh Timefold + AI (schema_stms)
│   ├── care/                           #   CRM & Helpdesk (schema_care)
│   └── noc/                            #   Insights + Apache Doris Virtual DocType + MCP Tools
└── astra-server/                       # Đóng gói thực thi (Modular Monolith hoặc tách Microservice theo Profile)

```

### 2. Thứ tự Triển khai Thực chiến (Phased Execution)

1. **Bước 1 – Nâng cấp Core Astra (Tuần 1–3):**
* Bổ sung hỗ trợ **Multi-Schema (`schema_<app>`)** và **`storage: ledger`** (cho `GL Entry` và `ScanEventLedger`) vào `astra-doctype` hiện tại.


* Hoàn thiện cơ chế **Submittable Lifecycle (`0 -> 1 -> 2`)** và **Audit Hash-Chain Ledger** ngay tại tầng `DocumentStore`.


* Dựng nhánh **`astra-cdc-rust` (`supabase/etl`)** đọc WAL từ PostgreSQL dev stack, cập nhật bảng `_cdc_sync_watermark` và kiểm thử `SyncAwareLsnFilter` trên Quarkus.


2. **Bước 2 – Xây dựng Luồng Vận hành Cốt lõi `OMS` + `OPS` + `CARE` (Tuần 4–7):**
* Định nghĩa các tệp `.doctype.yaml` cho `OMS` và `OPS` (tách cột nóng cho `Waybill`).


* Thiết lập luồng đồng bộ Replica qua Rust CDC sang `CARE` để chứng minh hiệu năng đọc/ghi tách biệt và cơ chế cảnh báo chờ Sync trên giao diện.


3. **Bước 3 – Xây dựng Lõi Tài chính & Nhân sự `BMS` + `SCS` (Tuần 8–11):**
* Triển khai `GLEntry` và `PayrollLedger` cùng `@AstraGlobalTransactional` (kết hợp Narayana JTA / Seata AT & Redis TCC).


* Đóng gói module Hóa đơn điện tử Việt Nam ở **Tier 1 (Isolated JVM)** và công thức tính lương/cước ở **Tier 2 (Wasm)**.




4. **Bước 4 – Kích hoạt Trí tuệ Nhân tạo & Dữ liệu Lớn `NOC` + `STMS` (Tuần 12+):**
* Nối luồng Rust CDC Stream Load vào **Apache Doris**, viết `DorisDocTypeAdapter` và bật **MCP Server (`astra-ai`)** cho `NOC`.


* Tích hợp `timefold-solver-quarkus` vào `STMS` để chạy bài toán tối ưu điều phối xe tự động.