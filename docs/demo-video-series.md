# Astra Express — Bộ demo & kịch bản video giới thiệu Astra

Tài liệu này dùng để quay một loạt video marketing giới thiệu Astra. Câu chuyện xuyên suốt lấy từ
[`use-case-01.md`](use-case-01.md): một công ty chuyển phát nhận đơn (**OMS**), vận hành bưu kiện
(**OPS**), chăm sóc khách hàng (**CARE**) và quản lý công nợ, sổ cái (**BMS**). Mọi thứ chiếu trong video
đều là **code đang chạy thật** trong repository, ở dạng *thin slice*: đủ để chứng minh cơ chế, chưa phải
toàn bộ phạm vi của use-case. Các video về cơ chế nền tảng (9, 11–17) có mục **"Nói đúng"** ghi rõ giới hạn hiện tại; mỗi video link tới đoạn code để chiếu cho developer trong
[`demo-code-snippets.md`](demo-code-snippets.md) (code trích từ repo, kèm sơ đồ luồng).

---

## 1. Chuẩn bị môi trường quay

Yêu cầu: JDK 25, Docker Desktop (cả stack chạy được với 8 GB RAM cấp cho Docker), `kubectl`, `k3d`, `jq`, **Bash 5**
(`brew install bash`; các script tự chạy lại bằng Bash 5 nếu shell mặc định là Bash 3.2 của macOS),
Rust (chỉ cần khi sửa module Wasm hoặc worker CDC).

```bash
make k3d-up          # cụm k3d: 2 pod kernel, PostgreSQL (wal_level=logical), Redis, Keycloak, Seata, worker CDC Rust
make demo-seed       # 2 tenant: saigon-express, hanoi-express — dữ liệu qua REST API, đăng nhập Keycloak
open http://localhost:8080/desk/          # Desk (đăng nhập Keycloak, PKCE)

make demo-reset && make demo-seed         # làm sạch trước mỗi buổi quay (chứng từ, sổ cái, overlay, BMS, Redis)
make demo-express SCENES="10 11" PAUSE=0  # chạy cảnh terminal; bỏ PAUSE=0 để bấm Enter qua từng bước
```

`make k3d-up` dựng lại image, đồng bộ realm Keycloak (`scripts/keycloak-sync-realm.sh`), chuẩn bị database
(`scripts/db-setup.sh`: schema BMS, role/publication CDC) và triển khai mọi thứ.

Video 8 (kernel từ chối superuser) và Video 9 (live reload) cần thêm stack Docker Compose. k3d và dev mode
cùng mặc định cổng 8080, nên chạy dev mode ở cổng khác và trỏ mọi script vào đó:

```bash
make infra-up && make build                          # PostgreSQL :5432, Redis, Keycloak :8180 (Video 8 chỉ cần tới đây)
                                                     # infra-up chạy scripts/keycloak-compose-dev.sh: Keycloak dev nhận HTTP từ máy host + đồng bộ realm
make dev DEV_ARGS=-Dquarkus.http.port=8081           # Video 9
BASE=http://localhost:8081 make demo-seed            # seed vào dev mode; reset bằng: scripts/demo-reset.sh compose
```

### Người dùng demo (mật khẩu chung: `Astra@123`, realm `deploy/keycloak/realm/astra-realm.json`)

| User | Tenant | Role | Dùng cho |
| --- | --- | --- | --- |
| `mai` | saigon-express | admin | quản trị tenant, overlay |
| `tuan` | saigon-express | sales | tạo / submit đơn |
| `quang` | saigon-express | ops | quét mã bưu kiện |
| `ha` | saigon-express | care | ticket CSKH, đọc replica |
| `khoa`, `thu` | saigon-express | accountant | sổ cái, đối soát COD (maker / checker) |
| `nam` | hanoi-express | admin | tenant thứ hai |
| `demo` | cả hai | admin | seed dữ liệu, màn hình so sánh 2 tenant |

### Công cụ quay

| Công cụ | Dùng để | Lệnh / URL |
| --- | --- | --- |
| **Astra Desk** | Giao diện sinh từ metadata: list, form, vòng đời, sổ cái, replica CDC, overlay | `/desk/` |
| **So sánh 2 tenant** | Hai tenant cạnh nhau (đăng nhập `demo` trước) | `/desk/compare.html` |
| **API inspector** | Request/response thật mà Desk gọi, kể cả `X-Tx-LSN` | nút "API inspector" |
| **Script theo cảnh** | 15 cảnh terminal, mỗi cảnh ứng với một video | `scripts/demo-express.sh` |
| **Claude Code + MCP** | Agent AI thật gọi Astra | `scripts/mcp-claude.sh tuan saigon-express` |

Mẹo: cửa sổ 1440×900; terminal 16–18pt; quay ngay sau `demo-reset` + `demo-seed` để mã chứng từ khớp
kịch bản (`ORD-<ngày>-00007`…). Ngày trong mã đơn là ngày chạy seed.

---

## 2. Bản đồ tính năng → nơi chứng minh

| Tính năng (đang chạy thật) | Video | Cảnh | Code |
| --- | --- | --- | --- |
| App là plugin Tier 0, chỉ dùng `astra-spi` | 1, 9 | 1 | `astra-apps/express`, `astra-apps/cms` |
| DocType YAML → REST + Desk tự sinh; JSONB, không ALTER TABLE | 2, 9 | 2 | `*.doctype.yaml`, `DocResource`, Desk |
| `projected: true` → index biểu thức online | 2, 5 | 2, 5 | `ProjectionIndexer` |
| Validation theo metadata, hook nghiệp vụ | 3 | 3 | `DocumentValidator`, `ShippingFeeHook` |
| Vòng đời Draft → Submitted → Cancelled → Amend | 4 | 4 | `DocumentStore.submit/cancel/amend` |
| Máy trạng thái + chứng từ liên kết trong 1 transaction | 5 | 5 | `WaybillScanHook`, `OrderWaybillHook` |
| CARE thấy OPS, triage, SLA | 6 | 6 | `TicketTriageHook` |
| Multi-tenant RLS, `ScopedValue` | 7 | 7 | `TenantContext`, `TenantResolverFilter` |
| Fail-closed khi kết nối bằng superuser | 8 | 8 | `RowLevelSecurityGuard` |
| **Keycloak OIDC, role theo tenant, quyền DocType, SoD** | 11 | 9 | `astra-iam`, `DocPermissions`, realm |
| **Ledger append-only, phân vùng tháng, bút toán đảo** | 12 | 10 | `V3__ledger.sql`, `LedgerStore`, `GeneralLedger` SPI |
| **Overlay theo tenant, áp dụng không restart** | 13 | 11 | `Overlays`, `DocTypeOverlays`, `tenants/*/overlays` |
| **Tier 2 Wasm (Chicory 1.7.5), script Rust của tenant** | 14 | 12 | `astra-plugin-host`, `astra-apps/express/wasm` |
| **MCP server, PII masking, tool do app đóng góp** | 15 | 13 | `astra-ai`, `McpTool` SPI |
| **Rust CDC (pgoutput) + Sync-Aware LSN** | 16 | 14 | `astra-cdc-rust`, `SyncAwareLsnFilter` |
| **Seata 2.6 AT + TCC: global transaction** | 17 | 15 | `astra-tx-seata`, `OrderCreditHook`, `PickupCapacity` |
| Kubernetes: 2 pod, series an toàn giữa pod | 10 | — | `deploy/k8s`, `SeriesService` |

---

## 3. Danh sách video

Thời lượng gợi ý: teaser 60–90 giây, video tính năng 3–6 phút, video cho developer 6–8 phút.

### Video 0 — Teaser: "Một nền tảng, mọi nghiệp vụ" (90 giây)

**Code cho developer:** [S0](demo-code-snippets.md#s0-big-picture)

Dựng nhanh từ cảnh quay của các video sau:
1. File `customer-order.doctype.yaml` → Desk "Customer Order" xuất hiện.
2. Submit đơn → vận đơn tự sinh, bút toán Nợ 131 / Có 511 hiện dưới form.
3. Hai tenant cạnh nhau: Hà Nội có thêm trường VAT (overlay), Sài Gòn thì không.
4. Script Wasm vòng lặp vô hạn: "stopped after 50 ms".
5. Claude hỏi "bưu kiện WB-… đang ở đâu?", số điện thoại hiện `*******567`.
6. Thẻ "sync pending" → "in sync" sau vài chục ms (CDC Rust).
7. Seata: đơn vượt hạn mức, mọi thứ rollback, undo log sạch.
8. Kết: "Astra — Java 25, Quarkus, PostgreSQL, Rust. Mã nguồn mở."

### Video 1 — Astra là gì & chạy trong 5 phút

**Code cho developer:** [S0](demo-code-snippets.md#s0-big-picture)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Sơ đồ kiến trúc trong `README.md` | Kernel, DocType Engine, Plugin Host; hôm nay xem phần đã chạy thật. |
| 2 | `make k3d-up` (tua nhanh), `kubectl -n astra get pods` | 2 pod kernel, PostgreSQL, Redis, Keycloak, Seata, worker CDC Rust. |
| 3 | `make demo-seed` | Dữ liệu nạp hoàn toàn qua REST API, đăng nhập bằng Keycloak. |
| 4 | `make demo-express SCENES=1` | `/api/app`: 2 app; `/api/doctype`: 8 DocType. |
| 5 | Desk → đăng nhập `mai` → Installed apps | App Express đóng góp OMS, OPS, CARE, BMS. |

### Video 2 — DocType: từ một file YAML đến API, giao diện và index

**Code cho developer:** [S1](demo-code-snippets.md#s1-doctype-is-the-source-of-truth), [S12](demo-code-snippets.md#s12-desk-generated-from-metadata)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `SCENES=2`: YAML của `CustomerOrder` | Trường, Select, Link, read-only, `pii`, `submittable`, `transaction: global`. |
| 2 | Desk → DocType metadata → CustomerOrder | Bảng trường đọc từ `GET /api/doctype` (cùng định nghĩa với `GET /api/doctype/CustomerOrder`, đã áp overlay của tenant). |
| 3 | Mục Projections | `projected: true` → `CREATE INDEX CONCURRENTLY`, không khoá ghi. |
| 4 | Terminal: `\d astra_doc` | Mọi DocType chung một bảng JSONB. |

### Video 3 — Quy tắc nghiệp vụ: hook & validation

**Code cho developer:** [S2](demo-code-snippets.md#s2-an-app-and-a-hook)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`tuan`) → New Customer Order: C001, Domestic, Express, 2.2 kg, COD 500.000 → Create | Mã từ series theo ngày; **phí 73.000 ₫** do `ShippingFeeHook` tính; "Priced by: Standard tariff". |
| 2 | Giải thích phí | 30.000 + 3 × 5.000 (mỗi 0,5 kg trên kg đầu) = 45.000; ×1,5 (Express) = 67.500; phí COD max(5.000; 1%) = 5.000 → 72.500; làm tròn lên nghìn → 73.000. |
| 3 | Sửa SĐT `123`, cân nặng `45` → Save | Hook trả 2 lỗi cùng lúc (422). |
| 4 | Sửa lại hợp lệ, khách `C999` → Save | Kernel kiểm tra Link. |
| 5 | `SCENES=3` + API inspector | Read-only, trường lạ, Select sai đều bị chặn. |

### Video 4 — Vòng đời chứng từ: Submit, Cancel, Amend

**Code cho developer:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`mai`) → New Customer Order cho C001 → Create | Thanh trạng thái 0 → 1 → 2 → Amend. (Đơn nháp `ORD-…-00005` của C002 dành cho Video 17: nó vượt hạn mức công nợ.) |
| 2 | **Submit** | Vận đơn tự tạo, bút toán cước hiện ở thẻ "Ledger entries" — tất cả trong một transaction. |
| 3 | Form bị khoá | Chứng từ đã duyệt là bất biến. |
| 4 | **Cancel** | Vận đơn hủy theo; sổ cái ghi bút toán đảo (không sửa dòng cũ). |
| 5 | **Amend**, rồi Amend lần nữa trên bản đã hủy | Bản sửa đổi `-1`; lần hai bị từ chối. |

### Video 5 — Vận hành: máy trạng thái bưu kiện

**Code cho developer:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`quang`) → Waybill, filter `Ops status = At Hub` | Filter trên trường projected (⚡). |
| 2 | `WB-…-000001` | Timeline quét mã đầy đủ đến Delivered. |
| 3 | Vận đơn `Created` → chọn `Delivered` | Không nhảy cóc trạng thái. |
| 4 | `Picked Up`; rồi `At Hub` để trống hub; rồi nhập hub | Lỗi rồi thành công, timeline thêm mốc. |
| 5 | Desk (`mai`) → hủy đơn đã giao | Bị từ chối, không có gì thay đổi nửa vời. |

### Video 6 — CSKH nhìn thấy vận hành

**Code cho developer:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction) (hook `TicketTriageHook` cùng kiểu với các hook ở S2/S3)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`ha`) → New Support Ticket "Hàng bị vỡ, khách yêu cầu đền bù", chọn waybill | Priority tự lên High, SLA +24h, trạng thái waybill. |
| 2 | Cuối form: thẻ "CARE replica (CDC)" | Thẻ này đọc bản sao do worker CDC giữ, không đọc thẳng bảng vận hành (chi tiết ở Video 16). Trường `waybill_status` của ticket thì do hook đọc Waybill lúc lưu. |
| 3 | `SCENES=6` | Bản API. |

### Video 7 — Đa thuê bao

**Code cho developer:** [S4](demo-code-snippets.md#s4-tenants-and-row-level-security)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Đăng nhập `demo`, mở `compare.html`, Screen `#/list/Customer` | Cùng URL, cùng DocType, hai tenant. |
| 2 | Screen `#/form/Customer/C001` | Cùng mã, dữ liệu khác hẳn. |
| 3 | `SCENES=7` | Link không vượt tenant; `nam` vào saigon → 403; thiếu tenant → 400; SQL: chưa gắn tenant → 0 dòng. |

### Video 8 — Bảo mật fail-closed

**Code cho developer:** [S4](demo-code-snippets.md#s4-tenants-and-row-level-security)

`make infra-up && make build && make demo-express SCENES=8`: kernel thứ hai kết nối bằng superuser và tự
từ chối khởi động. Mở `deploy/docker/init/01-app-role.sql`: role ứng dụng `NOSUPERUSER NOBYPASSRLS`.

### Video 9 — Dành cho developer: viết một app Astra

**Code cho developer:** [S2](demo-code-snippets.md#s2-an-app-and-a-hook), [S12](demo-code-snippets.md#s12-desk-generated-from-metadata)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `make infra-up && make dev` | Dev mode live reload (Keycloak của Compose ở `localhost:8180`). |
| 2 | Cây `astra-apps/express` | App = `ExpressApp` + YAML + hook + tool MCP + participant TCC; chỉ phụ thuộc `astra-spi`. |
| 3 | `OrderWaybillHook`, `OrderPostingHook` | `DocumentService` và `GeneralLedger` tham gia transaction của chứng từ gốc. |
| 4 | **Live**: thêm `- { name: zalo_id, type: Data, in-list: true }` vào `customer.doctype.yaml` | Không migration, không restart: cột mới xuất hiện ngay. |
| 5 | `ExpressFlowTest`, `LedgerTest`, `SeataTest` | Test tích hợp với PostgreSQL thật; `SeataTest` chạy cả Seata Server và Redis thật. |

**Nói đúng:** sau bước 4 hãy `make demo-reset && make demo-seed` — xoá trường khỏi DocType khiến chứng từ
cũ còn trường đó không sửa được nữa; tiến hoá schema an toàn còn trên roadmap.

### Video 10 — Chạy trên Kubernetes

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `kubectl -n astra get pods` | 2 replica kernel + hạ tầng (Keycloak, Seata, CDC, PostgreSQL, Redis). |
| 2 | Desk → Customer Order | Mã đơn liên tục dù 2 pod cùng ghi: counter dùng row lock. |
| 3 | `kubectl -n astra rollout restart deployment/astra-server` trong khi Desk mở | Rolling restart, `maxUnavailable: 0`: Desk vẫn dùng được. **Nói đúng:** chưa có preStop/drain, request rơi đúng lúc pod cũ dừng có thể lỗi (lần đo: 2/47). |

### Video 11 — Keycloak: một danh tính, role theo tenant, quyền theo DocType (cảnh 9)

**Code cho developer:** [S5](demo-code-snippets.md#s5-keycloak-permissions-and-sod)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk → Sign in → trang đăng nhập theme Astra (vi/en) → `tuan` | Authorization Code + PKCE ngay trong trình duyệt; không mật khẩu nào đi qua Astra. |
| 2 | Góc phải: "Tuấn Lê · sales"; `tuan` tạo một đơn rồi mở lại: nút Submit có, nút Cancel không (đơn của người khác chỉ đọc) | Desk đọc quyền hiệu lực từ `/api/iam/me` và ẩn nút không được phép. |
| 3 | `SCENES=9` | `tuan` (sales) không quét mã, không hủy đơn; `quang` (ops) quét được; `ha` (care) mở ticket; `khoa` (accountant) hủy được. |
| 4 | Nhóm Keycloak `/tenants/saigon-express/ops` | Role là nhóm trong Keycloak; kernel đọc từ token, không tin header. |
| 5 | Desk (`khoa`) → CODRemittance `CODR-…-0002` (350.000, do `khoa` lập khi seed) → Submit; rồi đăng nhập `thu` → Submit | **Segregation of duties**: người lập không được tự duyệt (403); `thu` duyệt được. Quay ngay sau `demo-seed`: cảnh 10 chi thêm 100.000 cho C001, sau đó 350.000 sẽ vượt số đang nợ. |

**Nói đúng:** điều kiện quyền theo từng dòng (row-level conditions) và SSO SAML còn trên roadmap.

### Video 12 — Sổ cái append-only (cảnh 10)

**Code cho developer:** [S6](demo-code-snippets.md#s6-append-only-ledger)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`khoa`) → General ledger | Bảng cân đối theo hệ tài khoản VN (TT200: 112, 131, 1388, 3388, 511). |
| 2 | `tuan` submit đơn → form hiện thẻ "Ledger entries" | Nợ 131 / Có 511 trong cùng transaction với đơn. |
| 3 | Hủy đơn | Hai dòng **đảo** mới (`reversal`), dòng cũ giữ nguyên. |
| 4 | `quang` quét Delivered | Nợ 1388 / Có 3388: tiền COD bưu tá thu, nợ shop. |
| 5 | Terminal: `UPDATE astra_ledger …` | `ERROR: astra_ledger is append-only` — chặn ở database, kể cả chủ bảng. |
| 6 | Mục Partitions | Phân vùng theo tháng, kernel tạo trước. |
| 7 | CODRemittance vượt số nợ shop → 422; maker/checker | Không chi quá số đang nợ; người lập ≠ người duyệt. |

**Nói đúng:** trigger chặn UPDATE/DELETE; role sở hữu bảng vẫn có thể DROP/ALTER — production nên chạy
kernel bằng role không sở hữu bảng. Chuỗi hash audit (hash-chain) còn trên roadmap.

### Video 13 — Overlay theo tenant (cảnh 11)

**Code cho developer:** [S7](demo-code-snippets.md#s7-tenant-overlays)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `tenants/hanoi-express/overlays/customer-order.overlay.yaml` | Hà Nội thêm trường VAT, vùng "Island", bắt buộc ghi chú — app Express không đổi một dòng. |
| 2 | `compare.html`, Screen `#/new/CustomerOrder` | Bên Hà Nội có trường gắn nhãn "✚ tenant overlay". |
| 3 | Desk (`mai`) → DocType metadata → CustomerOrder → Tenant overlay → thêm `gift_wrap` → Save overlay | Áp dụng ngay, không restart; tenant khác không bị ảnh hưởng. Quay xong bấm Remove overlay, để cảnh 11 vẫn cho thấy saigon-express không có overlay. |
| 4 | Overlay đổi kiểu trường → 422; `tuan` sửa overlay → 403 | Overlay không được phá app; chỉ admin của tenant được sửa. |

**Nói đúng:** overlay thêm/chỉnh trường, chưa thêm hook hay quyền; không xoá được trường của app.

### Video 14 — Tier 2 Wasm: công thức cước của riêng tenant (cảnh 12)

**Code cho developer:** [S8](demo-code-snippets.md#s8-wasm-tier-2)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `astra-apps/express/wasm/tet-promo/src/lib.rs` | Khuyến mãi Tết viết bằng Rust, biên dịch ra 421 byte WebAssembly. |
| 2 | Desk (`mai`) → Pricing Rule → mở rule → `self_test` | Kernel chạy thử module trong sandbox khi lưu. |
| 3 | Bật `active` → tạo đơn B2B (C001, Domestic, Express, 2.2 kg, COD 500.000) | Phí 73.000 → **51.000 ₫**, "Priced by: PRL-001 … (Wasm)". Quay xong tắt `active` lại, nếu không mọi đơn B2B ở các video khác sẽ không còn 73.000. |
| 4 | New Pricing Rule → chọn file `astra-apps/express/wasm/modules/runaway.wasm` (vòng lặp vô hạn) → Create | "module rejected (timeout): script exceeded its 50 ms deadline and was stopped" — kernel không hề hấn gì. |
| 5 | Nói về giới hạn | Bộ nhớ tối đa 1 MiB, không host function, mỗi lần gọi một instance mới. |

**Nói đúng:** Chicory chưa có đếm lệnh (fuel), nên giới hạn CPU là deadline thời gian. Tier 1
(hot-swap JVM) còn trên roadmap.

### Video 15 — MCP: agent AI dùng Astra bằng quyền của người dùng (cảnh 13)

**Code cho developer:** [S9](demo-code-snippets.md#s9-mcp-for-ai-agents)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `SCENES=13` | Handshake MCP 2025-11-25, 7 tool (5 của kernel, 2 do app Express đóng góp). |
| 2 | `search_documents` | Trường `pii: true` bị che trước khi tới mô hình: SĐT chỉ còn 3 số cuối (`*******567`), tên và địa chỉ chỉ còn chữ cái đầu mỗi từ (`L*** V*** D***`); `shipping_fee` giữ nguyên. |
| 3 | `scripts/mcp-claude.sh ha saigon-express` → `claude` → "Bưu kiện WB-2610-000002 đang ở đâu, có khiếu nại không?" | Claude gọi `track_waybill`, trả lời kèm hành trình và ticket. |
| 4 | Hỏi "tạo đơn hàng mới cho C001" | Bị từ chối: `ha` (care) không có quyền tạo đơn — agent không vượt quyền người dùng. |
| 5 | `scripts/mcp-claude.sh khoa` → "Khách nào đang chờ trả COD?" | `cod_outstanding` đọc từ sổ cái. |

**Nói đúng:** client dùng bearer token tĩnh (token 1 giờ của client dev); OAuth discovery cho MCP,
AI agent tự hành và vector search còn trên roadmap. Che PII áp dụng cho kênh MCP, không áp cho Desk.

### Video 16 — Rust CDC + Sync-Aware LSN (cảnh 14)

**Code cho developer:** [S10](demo-code-snippets.md#s10-rust-cdc-and-sync-aware-lsn)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk → Replication (CDC) | Worker Rust giải mã WAL (`pgoutput`), lag vài ms, số byte còn lại. |
| 2 | `SCENES=14`: tạm dừng worker, `quang` quét mã | Response có `X-Tx-LSN`. |
| 3 | `ha` đọc replica với `X-Required-LSN` | **202 Sync Pending** — không trả dữ liệu cũ. |
| 4 | Bật lại worker | 200 sau vài chục ms; trạng thái ổn định: replica bắt kịp vài chục tới hơn trăm ms sau lần ghi (đo mỗi lần mỗi khác). |
| 5 | Desk: mở Waybill vừa quét → thẻ "CARE replica (CDC)" | "sync pending" → "in sync" tự động (read-your-writes) nếu worker bắt kịp trong 10 s Desk tự hỏi lại; worker tắt lâu hơn thì mở lại form. |
| 6 | `pg_replication_slots` | Slot logical `astra_cdc` (cột `active = f` là bình thường: worker đọc slot bằng SQL peek/advance, không giữ kết nối streaming); worker ghi replica **dưới RLS**, không BYPASSRLS. |

**Nói đúng:** worker đọc slot bằng `pg_logical_slot_peek_binary_changes` mỗi 50 ms (ít nhất một lần,
upsert idempotent), chưa dùng giao thức streaming; chưa đẩy sang Apache Doris.

### Video 17 — Seata: một lần submit, ba tài nguyên, tất cả hoặc không gì cả (cảnh 15)

**Code cho developer:** [S11](demo-code-snippets.md#s11-seata-at-and-tcc)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `customer-order.doctype.yaml`: `transaction: global` | Kernel mở global transaction quanh submit/cancel. |
| 2 | `SCENES=15`: submit đơn C001 | Nhánh **AT** trừ hạn mức trong schema `bms` (role riêng); nhánh **TCC** giữ chỗ lấy hàng trên Redis; transaction của kernel commit cuối. |
| 3 | Đơn C002 vượt hạn mức | 422; đơn vẫn nháp, không vận đơn, không bút toán, chỗ lấy hàng không bị chiếm (BMS từ chối trước khi tới nhánh TCC). |
| 4 | Ngày đã kín chỗ: TCC từ chối **sau khi** AT đã commit | Seata hoàn hạn mức từ undo log; `undo_log` sạch; Redis reserved về 0. |
| 5 | Log `Global transaction … rolled back` | Seata Server 2.6.0 (JDK 25) điều phối thật. |

**Nói đúng:** Seata Server dùng store file (đủ cho demo; production dùng DB/Raft); TCC tự xử lý
idempotent/empty-rollback/suspension theo branch, chưa dùng bảng fence của Seata; kernel commit transaction
của mình cuối cùng (last-resource), chưa là một nhánh Seata.

### Video 18 — Roadmap: phần còn lại của use-case-01 *(Đang phát triển)*

Mọi khung hình gắn nhãn **"Roadmap / Đang phát triển"**.

| Đã có (Video 1–17) | Còn trên roadmap |
| --- | --- |
| JSONB + projection index, ledger, schema riêng cho BMS | Multi-schema cho mọi module, hot-column materialised |
| Seata AT + TCC | AT cho chính kernel, bảng fence TCC, Seata DB/Raft store |
| Keycloak, quyền DocType, SoD | Điều kiện quyền theo dòng, SAML, SCIM |
| Tier 0, Tier 2 Wasm | Tier 1 Isolated JVM (hot-swap), đếm lệnh Wasm |
| Rust CDC + LSN | Giao thức streaming, Apache Doris, NOC dashboards |
| MCP + che PII | Agent tự hành, pgvector RAG, OAuth cho MCP |
| — | STMS (Timefold), SCS (lương), audit hash-chain, mã hoá trường |

---

## 4. Danh sách kiểm tra trước khi xuất bản

- [ ] Mỗi video chỉ khẳng định những gì có trong bảng mục 2; đọc mục "Nói đúng" trước khi thu âm.
- [ ] Video 18 và mọi khung hình roadmap có nhãn "Đang phát triển".
- [ ] Số liệu hiệu năng (lag CDC, thời gian sandbox) lấy từ chính lần quay, không làm tròn đẹp hơn thực tế.
- [ ] Dữ liệu khách hàng là dữ liệu seed hư cấu (`scripts/demo-seed.sh`).
- [ ] Mật khẩu demo chỉ xuất hiện trên stack local; không quay màn hình có token.
- [ ] Video có nhạc CC BY: mô tả video có dòng ghi công (Video 0: xem `docs/video/teaser/CREDITS.txt`).
