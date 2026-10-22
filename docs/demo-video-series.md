# Astra Express: bộ demo và kịch bản video giới thiệu Astra

English version: [demo-video-series.en.md](demo-video-series.en.md).

Tài liệu này dùng để quay một loạt video marketing giới thiệu Astra. Câu chuyện xuyên suốt lấy từ
[`use-case-01.md`](use-case-01.md): một công ty chuyển phát nhận đơn (**OMS**), vận hành bưu kiện
(**OPS**), chăm sóc khách hàng (**CARE**) và quản lý công nợ, sổ cái (**BMS**). Mọi thứ chiếu trong video
đều là **code đang chạy thật** trong repository, ở dạng *thin slice*: đủ để chứng minh cơ chế, chưa phải
toàn bộ phạm vi của use-case. Các video về cơ chế nền tảng (9, 11 đến 17) và các video nghiệp vụ mới (19 đến 21) có mục **"Nói đúng"** ghi rõ giới hạn hiện tại; mỗi video link tới đoạn code để chiếu cho developer trong
[`demo-code-snippets.md`](demo-code-snippets.md) (code trích từ repo, kèm sơ đồ luồng).

Nhạc nền của các video: "Corporate Tower" của Mare Production ([freesound.org/s/612264](https://freesound.org/s/612264/)), CC BY 4.0, đã cắt ghép.

Giọng đọc tiếng Việt: giọng nam "Xuân Vĩnh" của [VieNeu-TTS](https://github.com/pnnbao97/VieNeu-TTS) v3 Turbo (Apache-2.0), tổng hợp bằng `scripts/video-voiceover.py` từ file `narration.json` trong từng thư mục video.
Bản tiếng Anh dùng giọng `am_michael` của Kokoro-82M (Apache-2.0) qua `hyperframes tts`, tổng hợp bằng `scripts/video-voiceover.py --lang en` từ file `narration.en.json`. Font: Be Vietnam Pro (SIL OFL 1.1) cho chữ thường, JetBrains Mono cho mã.

---

## 1. Chuẩn bị môi trường quay

Yêu cầu: JDK 25, Docker Desktop (cả stack chạy được với 8 GB RAM cấp cho Docker), `kubectl`, `k3d`, `jq`, **Bash 5**
(`brew install bash`; các script tự chạy lại bằng Bash 5 nếu shell mặc định là Bash 3.2 của macOS),
Rust (chỉ cần khi sửa module Wasm hoặc worker CDC).

```bash
make k3d-up          # cụm k3d: 2 pod kernel, PostgreSQL (wal_level=logical), Redis, Keycloak, Seata, worker CDC Rust
make demo-seed       # 2 tenant: saigon-express, hanoi-express; dữ liệu qua REST API, đăng nhập Keycloak
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
| `hanh` | saigon-express | hr | nhân sự và tiền lương (Video 19) |
| `linh` | cả hai | dispatcher | điều phối xe (Video 20) |

### Công cụ quay

| Công cụ | Dùng để | Lệnh / URL |
| --- | --- | --- |
| **Astra Desk** | Giao diện sinh từ metadata: list, form, vòng đời, sổ cái, replica CDC, overlay | `/desk/` |
| **So sánh 2 tenant** | Hai tenant cạnh nhau (đăng nhập `demo` trước) | `/desk/compare.html` |
| **API inspector** | Request/response thật mà Desk gọi, kể cả `X-Tx-LSN` | nút "API inspector" |
| **Script theo cảnh** | 15 cảnh terminal, mỗi cảnh ứng với một video | `scripts/demo-express.sh` |
| **Claude Code + MCP** | Agent AI thật gọi Astra | `scripts/mcp-claude.sh tuan saigon-express` |

Mẹo: cửa sổ 1440×900; terminal 16 đến 18 pt; quay ngay sau `demo-reset` + `demo-seed` để mã chứng từ khớp
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
| Kubernetes: 2 pod, series an toàn giữa pod | 10 | (không có) | `deploy/k8s`, `SeriesService` |
| **HRM: chấm công, Payroll Entry, phiếu lương, thưởng KPI bằng Wasm, bút toán dồn tích** | 19 | `demo-hrm` 1 đến 8 | `astra-apps/hrm`, `PayrollEntryHook`, `SalaryCalculator` |
| **STMS: Timefold VRP, lập lại kế hoạch, tool MCP, copilot (LLM giả lập)** | 20 | `demo-stms` 1 đến 8 | `astra-apps/stms`, `RoutePlanner`, `Replanner`, `DispatchCopilot` |
| **SAML SSO qua Keycloak, SCIM 2.0 theo tenant** | 21 | `demo-sso` 1 đến 8 | `deploy/keycloak/realm`, `ScimResource`, `ScimDirectory` |

---

## 3. Danh sách video

Thời lượng gợi ý: teaser 60 đến 90 giây, video tính năng 3 đến 6 phút, video cho developer 6 đến 8 phút.

### Video 0: Teaser "Một nền tảng, mọi nghiệp vụ" (90 giây)

**Code cho developer:** [S0](demo-code-snippets.md#s0-big-picture)

Dựng nhanh từ cảnh quay của các video sau:
1. File `customer-order.doctype.yaml` → Desk "Customer Order" xuất hiện.
2. Submit đơn → vận đơn tự sinh, bút toán Nợ 131 / Có 511 hiện dưới form.
3. Hai tenant cạnh nhau: Hà Nội có thêm trường VAT (overlay), Sài Gòn thì không.
4. Script Wasm vòng lặp vô hạn: "stopped after 50 ms".
5. Claude hỏi "bưu kiện WB-… đang ở đâu?", số điện thoại hiện `*******567`.
6. Thẻ "sync pending" → "in sync" sau vài chục ms (CDC Rust).
7. Seata: đơn vượt hạn mức, mọi thứ rollback, undo log sạch.
8. Kết: "Astra: Java 25, Quarkus, PostgreSQL, Rust. Mã nguồn mở."

### Video 1: Astra là gì & chạy trong 5 phút

**Code cho developer:** [S0](demo-code-snippets.md#s0-big-picture)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Sơ đồ kiến trúc trong `README.md` | Kernel, DocType Engine, Plugin Host; hôm nay xem phần đã chạy thật. |
| 2 | `make k3d-up` (tua nhanh), `kubectl -n astra get pods` | 2 pod kernel, PostgreSQL, Redis, Keycloak, Seata, worker CDC Rust. |
| 3 | `make demo-seed` | Dữ liệu nạp hoàn toàn qua REST API, đăng nhập bằng Keycloak. |
| 4 | `make demo-express SCENES=1` | `/api/app`: 2 app; `/api/doctype`: 8 DocType. |
| 5 | Desk → đăng nhập `mai` → Installed apps | App Express đóng góp OMS, OPS, CARE, BMS. |

### Video 2: DocType: từ một file YAML đến API, giao diện và index

**Code cho developer:** [S1](demo-code-snippets.md#s1-doctype-is-the-source-of-truth), [S12](demo-code-snippets.md#s12-desk-generated-from-metadata)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `SCENES=2`: YAML của `CustomerOrder` | Trường, Select, Link, read-only, `pii`, `submittable`, `transaction: global`. |
| 2 | Desk → DocType metadata → CustomerOrder | Bảng trường đọc từ `GET /api/doctype` (cùng định nghĩa với `GET /api/doctype/CustomerOrder`, đã áp overlay của tenant). |
| 3 | Mục Projections | `projected: true` → `CREATE INDEX CONCURRENTLY`, không khoá ghi. |
| 4 | Terminal: `\d astra_doc` | Mọi DocType chung một bảng JSONB. |

### Video 3: Quy tắc nghiệp vụ: hook & validation

**Code cho developer:** [S2](demo-code-snippets.md#s2-an-app-and-a-hook)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`tuan`) → New Customer Order: C001, Domestic, Express, 2.2 kg, COD 500.000 → Create | Mã từ series theo ngày; **phí 73.000 ₫** do `ShippingFeeHook` tính; "Priced by: Standard tariff". |
| 2 | Giải thích phí | 30.000 + 3 × 5.000 (mỗi 0,5 kg trên kg đầu) = 45.000; ×1,5 (Express) = 67.500; phí COD max(5.000; 1%) = 5.000 → 72.500; làm tròn lên nghìn → 73.000. |
| 3 | Sửa SĐT `123`, cân nặng `45` → Save | Hook trả 2 lỗi cùng lúc (422). |
| 4 | Sửa lại hợp lệ, khách `C999` → Save | Kernel kiểm tra Link. |
| 5 | `SCENES=3` + API inspector | Read-only, trường lạ, Select sai đều bị chặn. |

### Video 4: Vòng đời chứng từ: Submit, Cancel, Amend

**Code cho developer:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`mai`) → New Customer Order cho C001 → Create | Thanh trạng thái 0 → 1 → 2 → Amend. (Đơn nháp `ORD-…-00005` của C002 dành cho Video 17: nó vượt hạn mức công nợ.) |
| 2 | **Submit** | Vận đơn tự tạo, bút toán cước hiện ở thẻ "Ledger entries", trong cùng một transaction. |
| 3 | Form bị khoá | Chứng từ đã duyệt là bất biến. |
| 4 | **Cancel** | Vận đơn hủy theo; sổ cái ghi bút toán đảo (không sửa dòng cũ). |
| 5 | **Amend**, rồi Amend lần nữa trên bản đã hủy | Bản sửa đổi `-1`; lần hai bị từ chối. |

### Video 5: Vận hành: máy trạng thái bưu kiện

**Code cho developer:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`quang`) → Waybill, filter `Ops status = At Hub` | Filter trên trường projected (⚡). |
| 2 | `WB-…-000001` | Timeline quét mã đầy đủ đến Delivered. |
| 3 | Vận đơn `Created` → chọn `Delivered` | Không nhảy cóc trạng thái. |
| 4 | `Picked Up`; rồi `At Hub` để trống hub; rồi nhập hub | Lỗi rồi thành công, timeline thêm mốc. |
| 5 | Desk (`mai`) → hủy đơn đã giao | Bị từ chối; không dữ liệu nào thay đổi. |

### Video 6: CSKH nhìn thấy vận hành

**Code cho developer:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction) (hook `TicketTriageHook` cùng kiểu với các hook ở S2/S3)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`ha`) → New Support Ticket "Hàng bị vỡ, khách yêu cầu đền bù", chọn waybill | Priority tự lên High, SLA +24h, trạng thái waybill. |
| 2 | Cuối form: thẻ "CARE replica (CDC)" | Thẻ này đọc bản sao do worker CDC giữ, không đọc thẳng bảng vận hành (chi tiết ở Video 16). Trường `waybill_status` của ticket thì do hook đọc Waybill lúc lưu. |
| 3 | `SCENES=6` | Bản API. |

### Video 7: Đa thuê bao

**Code cho developer:** [S4](demo-code-snippets.md#s4-tenants-and-row-level-security)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Đăng nhập `demo`, mở `compare.html`, Screen `#/list/Customer` | Cùng URL, cùng DocType, hai tenant. |
| 2 | Screen `#/form/Customer/C001` | Cùng mã, dữ liệu khác nhau. |
| 3 | `SCENES=7` | Link không vượt tenant; `nam` vào saigon → 403; thiếu tenant → 400; SQL: chưa gắn tenant → 0 dòng. |

### Video 8: Bảo mật fail-closed

**Code cho developer:** [S4](demo-code-snippets.md#s4-tenants-and-row-level-security)

`make infra-up && make build && make demo-express SCENES=8`: kernel thứ hai kết nối bằng superuser và tự
từ chối khởi động. Mở `deploy/docker/init/01-app-role.sql`: role ứng dụng `NOSUPERUSER NOBYPASSRLS`.

### Video 9: Dành cho developer, viết một app Astra

**Code cho developer:** [S2](demo-code-snippets.md#s2-an-app-and-a-hook), [S12](demo-code-snippets.md#s12-desk-generated-from-metadata)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `make infra-up && make dev` | Dev mode live reload (Keycloak của Compose ở `localhost:8180`). |
| 2 | Cây `astra-apps/express` | App = `ExpressApp` + YAML + hook + tool MCP + participant TCC; chỉ phụ thuộc `astra-spi`. |
| 3 | `OrderWaybillHook`, `OrderPostingHook` | `DocumentService` và `GeneralLedger` tham gia transaction của chứng từ gốc. |
| 4 | **Live**: thêm `- { name: zalo_id, type: Data, in-list: true }` vào `customer.doctype.yaml` | Không migration, không restart: cột mới xuất hiện ngay. |
| 5 | `ExpressFlowTest`, `LedgerTest`, `SeataTest` | Test tích hợp với PostgreSQL thật; `SeataTest` chạy cả Seata Server và Redis thật. |

**Nói đúng:** sau bước 4 hãy `make demo-reset && make demo-seed`, vì xoá trường khỏi DocType khiến chứng từ
cũ còn trường đó không sửa được nữa; tiến hoá schema an toàn còn trên roadmap.

### Video 10: Chạy trên Kubernetes

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `kubectl -n astra get pods` | 2 replica kernel + hạ tầng (Keycloak, Seata, CDC, PostgreSQL, Redis). |
| 2 | Desk → Customer Order | Mã đơn liên tục dù 2 pod cùng ghi: counter dùng row lock. |
| 3 | `kubectl -n astra rollout restart deployment/astra-server` trong khi Desk mở | Rolling restart, `maxUnavailable: 0`: Desk vẫn dùng được. **Nói đúng:** chưa có preStop/drain, request rơi đúng lúc pod cũ dừng có thể lỗi (lần đo: 2/47). |

### Video 11: Keycloak, một danh tính, role theo tenant, quyền theo DocType (cảnh 9)

**Code cho developer:** [S5](demo-code-snippets.md#s5-keycloak-permissions-and-sod)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk → Sign in → trang đăng nhập theme Astra (vi/en) → `tuan` | Authorization Code + PKCE ngay trong trình duyệt; không mật khẩu nào đi qua Astra. |
| 2 | Góc phải: "Tuấn Lê · sales"; `tuan` tạo một đơn rồi mở lại: nút Submit có, nút Cancel không (đơn của người khác chỉ đọc) | Desk đọc quyền hiệu lực từ `/api/iam/me` và ẩn nút không được phép. |
| 3 | `SCENES=9` | `tuan` (sales) không quét mã, không hủy đơn; `quang` (ops) quét được; `ha` (care) mở ticket; `khoa` (accountant) hủy được. |
| 4 | Nhóm Keycloak `/tenants/saigon-express/ops` | Role là nhóm trong Keycloak; kernel đọc từ token, không tin header. |
| 5 | Desk (`khoa`) → CODRemittance `CODR-…-0002` (350.000, do `khoa` lập khi seed) → Submit; rồi đăng nhập `thu` → Submit | **Segregation of duties**: người lập không được tự duyệt (403); `thu` duyệt được. Quay ngay sau `demo-seed`: cảnh 10 chi thêm 100.000 cho C001, sau đó 350.000 sẽ vượt số đang nợ. |

**Nói đúng:** điều kiện quyền theo từng dòng (row-level conditions) và SSO SAML còn trên roadmap.

### Video 12: Sổ cái append-only (cảnh 10)

**Code cho developer:** [S6](demo-code-snippets.md#s6-append-only-ledger)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk (`khoa`) → General ledger | Bảng cân đối theo hệ tài khoản VN (TT200: 112, 131, 1388, 3388, 511). |
| 2 | `tuan` submit đơn → form hiện thẻ "Ledger entries" | Nợ 131 / Có 511 trong cùng transaction với đơn. |
| 3 | Hủy đơn | Hai dòng **đảo** mới (`reversal`), dòng cũ giữ nguyên. |
| 4 | `quang` quét Delivered | Nợ 1388 / Có 3388: tiền COD bưu tá thu, nợ shop. |
| 5 | Terminal: `UPDATE astra_ledger …` | `ERROR: astra_ledger is append-only`, chặn ở database, kể cả chủ bảng. |
| 6 | Mục Partitions | Phân vùng theo tháng, kernel tạo trước. |
| 7 | CODRemittance vượt số nợ shop → 422; maker/checker | Không chi quá số đang nợ; người lập ≠ người duyệt. |

**Nói đúng:** trigger chặn UPDATE/DELETE; role sở hữu bảng vẫn có thể DROP/ALTER; production nên chạy
kernel bằng role không sở hữu bảng. Chuỗi hash audit (hash-chain) còn trên roadmap.

### Video 13: Overlay theo tenant (cảnh 11)

**Code cho developer:** [S7](demo-code-snippets.md#s7-tenant-overlays)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `tenants/hanoi-express/overlays/customer-order.overlay.yaml` | Hà Nội thêm trường VAT, vùng "Island", bắt buộc ghi chú; app Express không đổi dòng nào. |
| 2 | `compare.html`, Screen `#/new/CustomerOrder` | Bên Hà Nội có trường gắn nhãn "✚ tenant overlay". |
| 3 | Desk (`mai`) → DocType metadata → CustomerOrder → Tenant overlay → thêm `gift_wrap` → Save overlay | Áp dụng ngay, không restart; tenant khác không bị ảnh hưởng. Quay xong bấm Remove overlay, để cảnh 11 vẫn cho thấy saigon-express không có overlay. |
| 4 | Overlay đổi kiểu trường → 422; `tuan` sửa overlay → 403 | Overlay không được phá app; chỉ admin của tenant được sửa. |

**Nói đúng:** overlay thêm/chỉnh trường, chưa thêm hook hay quyền; không xoá được trường của app.

### Video 14: Tier 2 Wasm, công thức cước của riêng tenant (cảnh 12)

**Code cho developer:** [S8](demo-code-snippets.md#s8-wasm-tier-2)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `astra-apps/express/wasm/tet-promo/src/lib.rs` | Khuyến mãi Tết viết bằng Rust, biên dịch ra 421 byte WebAssembly. |
| 2 | Desk (`mai`) → Pricing Rule → mở rule → `self_test` | Kernel chạy thử module trong sandbox khi lưu. |
| 3 | Bật `active` → tạo đơn B2B (C001, Domestic, Express, 2.2 kg, COD 500.000) | Phí 73.000 → **51.000 ₫**, "Priced by: PRL-001 … (Wasm)". Quay xong tắt `active` lại, nếu không mọi đơn B2B ở các video khác sẽ không còn 73.000. |
| 4 | New Pricing Rule → chọn file `astra-apps/express/wasm/modules/runaway.wasm` (vòng lặp vô hạn) → Create | "module rejected (timeout): script exceeded its 50 ms deadline and was stopped"; kernel vẫn chạy bình thường. |
| 5 | Nói về giới hạn | Bộ nhớ tối đa 1 MiB, không host function, mỗi lần gọi một instance mới. |

**Nói đúng:** Chicory chưa có đếm lệnh (fuel), nên giới hạn CPU là deadline thời gian. Tier 1
(hot-swap JVM) còn trên roadmap.

### Video 15: MCP, agent AI dùng Astra bằng quyền của người dùng (cảnh 13)

**Code cho developer:** [S9](demo-code-snippets.md#s9-mcp-for-ai-agents)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `SCENES=13` | Handshake MCP 2025-11-25, 7 tool (5 của kernel, 2 do app Express đóng góp). |
| 2 | `search_documents` | Trường `pii: true` bị che trước khi tới mô hình: SĐT chỉ còn 3 số cuối (`*******567`), tên và địa chỉ chỉ còn chữ cái đầu mỗi từ (`L*** V*** D***`); `shipping_fee` giữ nguyên. |
| 3 | `scripts/mcp-claude.sh ha saigon-express` → `claude` → "Bưu kiện WB-2610-000002 đang ở đâu, có khiếu nại không?" | Claude gọi `track_waybill`, trả lời kèm hành trình và ticket. |
| 4 | Hỏi "tạo đơn hàng mới cho C001" | Bị từ chối: `ha` (care) không có quyền tạo đơn, agent không vượt quyền người dùng. |
| 5 | `scripts/mcp-claude.sh khoa` → "Khách nào đang chờ trả COD?" | `cod_outstanding` đọc từ sổ cái. |

**Nói đúng:** client dùng bearer token tĩnh (token 1 giờ của client dev); OAuth discovery cho MCP,
AI agent tự hành và vector search còn trên roadmap. Che PII áp dụng cho kênh MCP, không áp cho Desk.

### Video 16: Rust CDC + Sync-Aware LSN (cảnh 14)

**Code cho developer:** [S10](demo-code-snippets.md#s10-rust-cdc-and-sync-aware-lsn)

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | Desk → Replication (CDC) | Worker Rust giải mã WAL (`pgoutput`), lag vài ms, số byte còn lại. |
| 2 | `SCENES=14`: tạm dừng worker, `quang` quét mã | Response có `X-Tx-LSN`. |
| 3 | `ha` đọc replica với `X-Required-LSN` | **202 Sync Pending**, không trả dữ liệu cũ. |
| 4 | Bật lại worker | 200 sau vài chục ms; trạng thái ổn định: replica bắt kịp vài chục tới hơn trăm ms sau lần ghi (đo mỗi lần mỗi khác). |
| 5 | Desk: mở Waybill vừa quét → thẻ "CARE replica (CDC)" | "sync pending" → "in sync" tự động (read-your-writes) nếu worker bắt kịp trong 10 s Desk tự hỏi lại; worker tắt lâu hơn thì mở lại form. |
| 6 | `pg_replication_slots` | Slot logical `astra_cdc` (cột `active = f` là bình thường: worker đọc slot bằng SQL peek/advance, không giữ kết nối streaming); worker ghi replica **dưới RLS**, không BYPASSRLS. |

**Nói đúng:** worker đọc slot bằng `pg_logical_slot_peek_binary_changes` mỗi 50 ms (ít nhất một lần,
upsert idempotent), chưa dùng giao thức streaming; chưa đẩy sang Apache Doris.

### Video 17: Seata, một lần submit trên ba tài nguyên (cảnh 15)

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

### Video 19: HRM, nhân sự và tiền lương

**Code cho developer:** `astra-apps/hrm` (`PayrollEntryHook`, `SalaryCalculator`, `astra-apps/hrm/wasm/courier-kpi`); test `HrmPayrollTest`.

Mục tiêu: một app nhân sự và tiền lương viết trên SPI công khai, theo khái niệm Frappe HRMS (Employee, Salary
Component, Salary Structure, Salary Structure Assignment, Attendance, Additional Salary, Payroll Entry, Salary
Slip). Chấm công quyết định ngày công, thưởng bưu tá là Wasm của tenant, và mỗi Payroll Entry ghi đúng **một**
bút toán dồn tích vào sổ cái. Chỉ lấy khái niệm; không chép code, schema hay giao diện của ERPNext/HRMS.

Chuẩn bị: `make demo-seed` nạp tháng 9/2026 (26 ngày làm việc) cho saigon-express: NV001 đủ công, giao 1.067
kiện; NV002 vắng 15/9, nửa ngày 22/9, giao 1.007 kiện; NV003 vào làm 14/9, có thưởng 500.000; NV004 nghỉ không
lương 29/9. Script đăng nhập `hanh` (hr). Nếu realm vừa đổi, chạy `scripts/keycloak-sync-realm.sh` để có `hanh`.

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `make demo-hrm SCENES=1` | App `io.astra.hrm`, 8 DocType module `hrm`, chỉ phụ thuộc `astra-spi`. 8 Salary Component, gồm BHXH 8 %, BHYT 1,5 %, BHTN 1 % (tài khoản 3383, 3384, 3386); Salary Structure COURIER và 4 assignment. |
| 2 | `SCENES=2`: mã Rust trong `astra-apps/hrm/wasm/courier-kpi` | Thưởng theo bậc: 1.500 đ/kiện đến kiện 600, 2.000 đ đến kiện 1.000, 2.500 đ từ kiện 1.001, cộng 500.000 chuyên cần nếu đủ công. Khi lưu, kernel chạy thử: `kpi(1000 pieces, 26/26 days) = 2200000 VND`. Module vòng lặp vô hạn (LOOP) → 422 "module rejected (timeout)". |
| 3 | `SCENES=3` | NV002: Absent 15/9, Half Day 22/9; NV004: On Leave / Leave Without Pay 29/9. `quang` (ops) tạo và submit chấm công hôm nay được, xem Salary Slip bị 403; `tuan` (sales) xem Payroll Entry bị 403. |
| 4 | `SCENES=4` | Lưu nháp một Payroll Entry `PAY-2610-001` (series theo ngày tạo): 4 phiếu lương nháp, tính song song mỗi phiếu một virtual thread, trong cùng transaction với entry. |
| 5 | `SCENES=5` | Phiếu NV002 từng dòng: `100% of 6500000 (base) × 24.5/26 days` = 6.125.000; KPI `Wasm kpi(1007 pieces, 24.5/26 days)` = 1.717.500; BHXH `8% of 6500000 (base)` = 520.000. NV003: 15 ngày công, BONUS 500.000. Sửa `net_pay` → 422 read-only. |
| 6 | `SCENES=6`: submit entry | Phiếu lương và sổ cái trong một transaction. Entry: 4 nhân viên, gross 35.837.501, khấu trừ 3.517.500, net 32.320.001. Một bút toán dồn tích 5 dòng: Nợ 642 35.837.501; Có 3383 2.680.000; Có 3384 502.500; Có 3386 335.000; Có 334 32.320.001. Mỗi phiếu có `accrual_voucher` là tên entry. Cancel riêng phiếu NV001 → 422 "cancel the payroll entry instead"; chạy lương tháng 9 lần nữa → 422 "NV001 already has submitted salary slip …". |
| 7 | `SCENES=7` | Tool MCP `payroll_summary` trả tổng và dòng phiếu, tên nhân viên đã che (`N*** V*** A***`); `tuan` gọi → `isError: true`, not permitted. |
| 8 | `SCENES=8`: cancel entry | Các phiếu sang docstatus 2; bút toán của entry có thêm 5 dòng `is_reversal: true`; số dư các tài khoản lương về 0. Không dòng nào bị xoá. |

**Nói đúng:**
- Không tính thuế TNCN; net là gross trừ bảo hiểm phần người lao động. Không tính phần doanh nghiệp đóng
  (BHXH, BHYT, BHTN, KPCĐ). Tỷ lệ và mức trần bảo hiểm là cấu hình seed để demo, chưa đối chiếu với quy định
  hiện hành; chưa xử lý quy tắc nghỉ từ 14 ngày trở lên thì không đóng BHXH.
- Chấm công tối giản: không có lịch ngày lễ, ca làm, check-in hay đơn nghỉ phép có số dư phép; tuần làm việc
  cố định Thứ 2 đến Thứ 7; ngày không chấm công tính là có đi làm. Số kiện do ops nhập khi chấm công, chưa lấy
  tự động từ Waybill hay STMS qua CDC như use-case mô tả.
- Chỉ có 4 kiểu tính (Fixed, % base, % gross, Wasm), chưa có trình soạn công thức. Không có bút toán chi trả
  lương (Nợ 334 / Có 112), không xuất file ngân hàng. Bút toán dồn tích ghi tổng theo tài khoản, không ghi
  theo từng nhân viên trên 334; toàn bộ chi phí vào 642, không phân bổ theo trung tâm chi phí.
- Batch chạy ngay trong request lưu entry (tối đa 1.000 nhân viên mỗi lượt), chưa có job nền cuối tháng hay
  bầu leader. Phiếu lương và sổ cái chung một transaction PostgreSQL, không dùng Seata.
- Nhân viên chưa tự xem được phiếu lương của mình: quyền `if-owner` mới áp cho ghi, chưa áp cho đọc.
- Chưa có Desk riêng cho tính lương: Desk chung hiển thị các dòng phiếu dưới dạng "N item(s)".
- Số liệu ở bảng trên là kết quả mong đợi tính theo logic của hook; lấy số trên màn hình từ chính lần quay.

### Video 20: STMS, điều phối xe với Timefold + AI copilot

**Code cho developer:** `astra-apps/stms` (`solver/RoutePlanner`, `Replanner`, `copilot/DispatchCopilot`, `copilot/MockLlmClient`); test `RoutePlannerTest`, `StmsDispatchTest`, `StmsMcpTest`.

Mục tiêu: bài toán điều xe (VRP có tải trọng và khung giờ) giải bằng Timefold Solver ngay trong kernel; khi có
sự cố, kế hoạch được giải lại từ tuyến hiện tại; người điều phối ra lệnh bằng tiếng Việt qua copilot, copilot
gọi đúng các tool MCP đó. **Solver, việc lập lại kế hoạch và các tool là thật; mô hình ngôn ngữ của copilot là
kịch bản giả lập cho demo** (`llm: "mock"`). Mọi khung hình có copilot phải hiện nhãn "LLM giả lập (kịch bản demo)".

Chuẩn bị: `make demo-seed` tạo cho mỗi tenant một kho hư cấu (saigon-express: HCM-TB Kho Tân Bình), 3 xe và 20
điểm giao/nhận cho ngày chạy seed (`STMS_DATE`, mặc định hôm nay theo giờ Việt Nam; dùng cùng giá trị cho seed
và demo). Script đăng nhập `linh` (dispatcher). Chạy các cảnh theo thứ tự: cảnh 3 đến 8 dùng plan do cảnh 2 tạo.

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `make demo-stms SCENES=1` | 5 DocType: Hub, Vehicle, DeliveryStop, DispatchPlan, DispatchEvent. Kho HCM-TB, 3 xe (300/250/250 kg), 20 điểm có khung giờ và mức ưu tiên Normal/VIP/Urgent. |
| 2 | `SCENES=2` | Lưu DispatchPlan với `solve: true`: Timefold giải trong ngân sách 5 giây. Ràng buộc cứng: tải trọng, khung giờ; mềm: quãng đường, phục vụ điểm ưu tiên sớm, cân bằng tải. Kết quả Solved, `0hard/…soft` (khả thi), khoảng 99 km, 2 xe, khoảng 1 giây; in bảng tuyến. |
| 3 | `SCENES=3`: copilot "Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác" | Response có `llm: "mock"` và `llm_note`; tool call `stms_vehicle_unavailable` rồi `stms_explain_plan`. Lời giải thích bắt đầu bằng `[LLM giả lập – kịch bản demo]`, nêu số điểm chuyển xe, điểm số trước → sau, vẫn 0hard. Solver giải lại từ tuyến hiện tại (warm start). |
| 4 | `SCENES=4`: "Ưu tiên khách VIP trước 10h, cân bằng tải giữa các xe" | Copilot gọi `stms_set_weights` (priority 500, balance 10). Các điểm VIP/gấp được phục vụ trước 10:00, đổi lại quãng đường dài hơn. Điểm mềm đổi thang đo khi đổi trọng số, chỉ so sánh giữa các plan cùng trọng số. |
| 5 | `SCENES=5`: "Thêm đơn gấp 30kg ở Bến Thành trước 11h" | `stms_add_urgent_stop` với toạ độ lấy từ danh sách địa danh cố định của bản giả lập; đơn được chèn vào một xe, các điểm VIP/gấp vẫn đúng hạn. |
| 6 | `SCENES=6` | Không qua copilot: `stms_traffic_delay` (Thủ Đức, bán kính 4 km, chậm 2,5 lần) và `stms_change_time_window` (Linh kiện Tân Tiến → 08:00 đến 09:00). Mỗi lần in điểm số trước/sau, km, `solve_ms` và các điểm bị dời; vẫn 0hard. |
| 7 | Desk (`linh`) → trang copilot điều phối, gõ một câu lệnh của cảnh 3 đến 5 | Trang gửi câu lệnh tới `POST /api/stms/copilot`, cùng copilot với tool `stms_copilot`; hiện nhãn LLM giả lập, các tool call đã chạy và lời giải thích. Mở DispatchPlan: `route_summary` (bảng tuyến dạng chữ) và `last_change` (các điểm bị dời). |
| 8 | `SCENES=7` | `quang` (ops) gọi `stms_vehicle_unavailable` → "not permitted: read DispatchEvent"; `ha` (care) gọi `stms_explain_plan` → not permitted; cùng tên plan ở hanoi-express → "not found". |
| 9 | `SCENES=8` | Nhật ký DispatchEvent: nguồn (Copilot hoặc MCP), điểm số trước → sau, điểm bị dời, `solve_ms`. Submit → docstatus 1, Dispatched; PUT `{solve: true}` → 422 "submitted documents are immutable"; `stms_set_weights` → "is dispatched and frozen; amend it to replan". |

**Nói đúng:**
- Copilot không gọi mô hình ngôn ngữ nào: `MockLlmClient` khớp câu lệnh bằng mẫu tiếng Việt cố định và một
  danh sách địa danh nhỏ; câu lệnh ngoài kịch bản không sinh tool call. Lời giải thích được viết từ kết quả
  thật của solver. Chỗ cắm nhà cung cấp LLM thật là interface `LlmClient`; chưa có bản cài đặt nào.
- Không có bản đồ, mạng đường thật hay giao thông trực tiếp: khoảng cách là đường chim bay × 1,3, tốc độ cố
  định 25 km/h, "kẹt xe" là vùng chậm do người điều phối khai báo. Không geocoding: điểm mang sẵn toạ độ.
- Lập lại kế hoạch là giải lại từ tuyến hiện tại, không giữ solver chạy cả ngày; thời gian không trôi, nên
  không khoá các điểm đã giao hay xe đang chạy. Thêm đơn gấp và giải lại là hai transaction: nếu giải lại lỗi,
  đơn mới vẫn được lưu.
- Tải trọng là một tổng chung mỗi xe (giao và nhận cộng lại); không có ràng buộc lấy-trước-giao, nhiều chuyến,
  ca và nghỉ của tài xế, hạn quay về kho, loại xe hay chi phí xe. Mỗi plan một kho, không trung chuyển giữa kho.
- Chưa có dự báo tải bằng AI (Tier 1), app tài xế, GPS hay cập nhật ETA từ hiện trường. Chưa có bản đồ tuyến:
  Desk hiện bảng tuyến dạng chữ và JSON.
- Demo khoảng 20 điểm, giải trong 1 đến 2 giây; chưa đo với bài toán lớn hơn. Số trên màn hình lấy từ chính lần
  quay; số trong báo cáo phase đo trên dữ liệu test, sẽ lệch nhẹ so với dữ liệu seed.
- DispatchEvent và đơn gấp do tool tạo mang chủ sở hữu `stms-mcp` / `stms-copilot`; người gọi thật được ghi
  trong log của MCP server.

### Video 21: SAML SSO và SCIM

**Code cho developer:** `deploy/keycloak/realm/acme-corp-idp-realm.json`, `deploy/keycloak/realm/astra-realm.json` (identity provider `acme-corp`), `astra-iam` (`ScimResource`, `ScimDirectory`, `ScimPatch`); test `SamlBrokerTest`, `ScimTest`.

Mục tiêu: nhân viên của một doanh nghiệp đăng nhập Astra bằng IdP của công ty mình qua SAML 2.0, nhóm bên IdP
thành role của tenant; hệ thống quản lý danh tính của doanh nghiệp tạo, phân quyền, khoá và xoá tài khoản qua
SCIM 2.0, chỉ trong tenant của mình.

Chuẩn bị: Keycloak phải có realm `acme-corp-idp`, identity provider `acme-corp` và các client SCIM.
`make infra-up` (chạy `scripts/keycloak-compose-dev.sh`) hoặc `make k3d-up` đồng bộ realm; nếu Keycloak đang
chạy từ trước, chạy lại `scripts/keycloak-sync-realm.sh`, rồi khởi động lại astra-server để có `/scim/v2`. Tenant
của video là `acme`. User `linh` và `bao` ở đây thuộc realm IdP `acme-corp-idp`, không phải `linh` dispatcher
của Video 20.

| Bước | Thao tác | Lời dẫn |
| --- | --- | --- |
| 1 | `make demo-sso SCENES=1` | Luồng SP-initiated phát lại bằng curl: Desk → Keycloak (`kc_idp_hint=acme-corp`) → AuthnRequest tới IdP → `linh` đăng nhập ở IdP → SAMLResponse có chữ ký quay về broker → mã OIDC. Assertion: NameID `linh`, Audience `urn:astra:sp`, `memberOf astra-editors`, ký RSA-SHA256 cả assertion lẫn response. Token: `preferred_username: acme-corp.linh`, `groups: ["/tenants/acme/editor"]`; `/api/iam/me` → acme `[editor]`; tạo Article → 201. |
| 2 | `SCENES=2` | SAMLResponse của `bao` bị sửa `astra-readers` thành `astra-admins` trên đường đi → Keycloak trả 400 "Chữ ký không hợp lệ…", không cấp mã. |
| 3 | `SCENES=3` | Discovery: `ServiceProviderConfig` (`patch: true`, filter tối đa 200, `bulk: false`, `etag: false`), ResourceTypes User và Group. Token client-credentials của `scim-acme`: `scim_tenant: acme`, role `scim`; tenant chỉ lấy từ claim này. |
| 4 | `SCENES=4` | `POST /scim/v2/Users` → 201, `application/scim+json`; filter `userName eq "…"` → 1 kết quả; tạo trùng → 409 `uniqueness`. User mới chưa có role: `/api/doc/Article` → 403 "not a member of tenant acme". |
| 5 | `SCENES=5` | Groups chính là role của tenant: admin, editor, author, reader. PATCH thêm thành viên vào editor → sau lần đăng nhập mới token có `/tenants/acme/editor`, tạo Article → 201. |
| 6 | `SCENES=6` | PATCH `{"op":"Replace","path":"active","value":"False"}` (kiểu Entra gửi) → tài khoản bị khoá: password grant → `invalid_grant` "Account disabled", refresh token → "Session not active". Access token đã cấp vẫn dùng được đến khi hết hạn; cảnh in rõ thời điểm hết hạn. |
| 7 | `SCENES=7` | Token của globex: user của acme → 404; filter → 0 kết quả; header `X-Astra-Tenant: acme` → 403; thêm user của acme vào role globex → 400 `invalidValue`; filter `co` → 400 `invalidFilter`. Lỗi theo định dạng RFC 7644. |
| 8 | `SCENES=8` | `DELETE` → 204, GET lại → 404. User SCIM của demo bị xoá khi script kết thúc; tài khoản liên kết `acme-corp.linh`, `acme-corp.bao` được giữ như tài khoản liên kết thật. |

**Nói đúng:**
- IdP là một realm Keycloak (`acme-corp-idp`) trong cùng container, không phải Okta, Entra hay ADFS; giao thức
  SAML giữa hai bên là thật. Demo phát lại các bước trình duyệt bằng curl và không theo redirect về Desk.
- Assertion và response có chữ ký và được kiểm; assertion **không mã hoá**, AuthnRequest **không ký**. Chỉ có
  SP-initiated; chưa cấu hình IdP-initiated; **Single Logout chưa nối và chưa test**.
- Lần đăng nhập đầu Keycloak tự tạo tài khoản `acme-corp.<username>`; trùng email với tài khoản local thì
  Keycloak hỏi liên kết, demo không chiếu bước này. Ánh xạ nhóm → role cố định trong cấu hình realm và chỉ cho
  tenant `acme`; mỗi doanh nghiệp khác cần IdP và mapper riêng.
- SCIM chưa có Bulk, ETag/`meta.version`, sort, `/Me`, `meta.lastModified`, tham số `attributes`;
  `excludedAttributes` chỉ áp cho `members` của Groups. Bộ lọc chỉ có `userName eq` và `displayName eq`.
  `externalId` bị bỏ qua; chỉ giữ một email; không đổi được `userName`.
- Groups cố định vì chính là role của tenant: tạo hoặc xoá group trả 501, chỉ đổi được thành viên. Client
  SCIM của tenant chỉ thấy và sửa user do chính nó cấp. Xoá qua SCIM xoá luôn tài khoản Keycloak.
- Đổi role có hiệu lực từ lần đăng nhập hoặc refresh tiếp theo. Sau `active=false`, access token đã cấp vẫn hợp
  lệ đến khi hết hạn vì Astra xác minh JWT offline (5 phút với client thường, 1 giờ với client demo
  `astra-dev-cli`).
- Liệt kê user lấy thành viên từ Keycloak và phân trang trong bộ nhớ, mỗi user thêm một lời gọi đọc group: đủ
  cho demo, chưa dành cho hàng trăm nghìn user. Secret của client SCIM trong realm JSON chỉ dùng cho môi trường local.

### Video 18: Roadmap, phần còn lại của use-case-01 *(Đang phát triển)*

Mọi khung hình gắn nhãn **"Roadmap / Đang phát triển"**.

| Đã có (Video 1 đến 17, 19 đến 21) | Còn trên roadmap |
| --- | --- |
| JSONB + projection index, ledger, schema riêng cho BMS | Multi-schema cho mọi module, hot-column materialised |
| Seata AT + TCC | AT cho chính kernel, bảng fence TCC, Seata DB/Raft store |
| Keycloak, quyền DocType, SoD | Điều kiện quyền theo dòng |
| Tier 0, Tier 2 Wasm | Tier 1 Isolated JVM (hot-swap), đếm lệnh Wasm |
| Rust CDC + LSN | Giao thức streaming, Apache Doris, NOC dashboards |
| MCP + che PII | Agent tự hành, pgvector RAG, OAuth cho MCP |
| Video 19: HRM (nhân sự, tiền lương), bút toán dồn tích qua `GeneralLedger` | Thuế TNCN, phần doanh nghiệp đóng bảo hiểm, chi trả lương, job lương nền cuối tháng, nhân viên tự xem phiếu lương |
| Video 20: STMS (Timefold), lập lại kế hoạch, copilot điều phối | LLM thật cho copilot, bản đồ và giao thông thật, dự báo tải (Tier 1), app tài xế |
| Video 21: SAML SSO, SCIM 2.0 theo tenant | Single Logout, SCIM Bulk/ETag, bộ lọc SCIM đầy đủ |
| (chưa có) | Audit hash-chain, mã hoá trường |

---

## 4. Danh sách kiểm tra trước khi xuất bản

- [ ] Mỗi video chỉ khẳng định những gì có trong bảng mục 2; đọc mục "Nói đúng" trước khi thu âm.
- [ ] Video 18 và mọi khung hình roadmap có nhãn "Đang phát triển".
- [ ] Video 20: mọi khung hình có copilot ghi rõ "LLM giả lập (kịch bản demo)"; không nói hay ngụ ý là có mô hình ngôn ngữ thật.
- [ ] Số liệu hiệu năng (lag CDC, thời gian sandbox) lấy từ chính lần quay, không làm tròn đẹp hơn thực tế.
- [ ] Dữ liệu khách hàng là dữ liệu seed hư cấu (`scripts/demo-seed.sh`).
- [ ] Mật khẩu demo chỉ xuất hiện trên stack local; không quay màn hình có token.
- [ ] Video có nhạc CC BY: mô tả video có dòng ghi công (Video 0: xem `docs/video/teaser/CREDITS.txt`).
