# Astra — Vì sao chọn nền tảng metadata-driven kiểu Frappe/ERPNext, viết lại bằng Java, thay đổi liên tục không downtime, và AI-native?

> Tài liệu để trình bày trước công chúng (meetup, talk, bài viết). Nói liền mạch trong 40 phút. Mỗi phần có **ý chính để nói**, **tham chiếu nền tảng lớn**, **lý do**, và **đánh đổi**. Số liệu hiệu năng của Astra là **mục tiêu thiết kế**, chưa đo; số của nền tảng khác là tuyên bố của chính họ.

---

## 0. Mở đầu: ERPNext đã chứng minh điều gì

**Ý chính để nói:** ERPNext chứng minh một điều ngành phần mềm doanh nghiệp gần như đã bỏ cuộc: **một nền tảng mở có thể chạy nội dung, bán hàng, kế toán, kho, sản xuất, nhân sự, dự án của cả một công ty**, và team nhỏ tùy biến được mà không phải fork.

Lý do không nằm ở module nào. Lý do là ý tưởng lõi của Frappe: **ứng dụng là metadata**. Một DocType định nghĩa field, validation, quyền, form, list, API và workflow của một loại chứng từ dưới dạng dữ liệu; mọi thứ còn lại (REST, UI, báo cáo, in, thông báo) được sinh ra từ đó.

Astra lấy ý tưởng đó và xây lại cho ba ràng buộc mà Frappe không được thiết kế để giải:

1. **Thông lượng và độ trễ ở quy mô cụm.** Java 25 virtual thread, Generational ZGC, Quarkus wiring lúc build, cô lập plugin trong tiến trình. Hàng nghìn tenant và tùy biến của họ trên một pod, không phải mỗi site một tiến trình.
2. **Đổi mỗi ngày mà không phá hôm qua.** DocType zero-DDL (tùy biến form không bao giờ khóa bảng), phiên bản app chạy song song, ghim theo tenant, canary tự rollback, SPI có chính sách deprecation, và tùy biến của tenant lưu dạng overlay sống sót qua mọi lần nâng cấp.
3. **AI là tác giả và người dùng hạng nhất.** Agent xây app trên SPI nhỏ có kiểu với sandbox và testkit; agent dùng nền tảng qua MCP tool sinh từ chính DocType, dưới cùng quyền và audit.
4. **Tuân thủ là thuộc tính của metadata, không phải dự án làm cuối.** Mỗi field DocType mang phân loại, thời hạn lưu, cơ sở pháp lý, nơi lưu trữ. Hồ sơ xử lý dữ liệu, yêu cầu của chủ thể dữ liệu, lịch xoá, audit trail, rà soát truy cập đều sinh từ metadata đó. Nhà vận hành đi tới SOC 2 Type II, ISO 27001, GDPR, Luật Bảo vệ dữ liệu cá nhân Việt Nam mà không phải làm lại mô hình dữ liệu.

> **Nói rõ hai điều ngay đầu:** (1) Astra đang ở giai đoạn thiết kế, chưa có code module. (2) **Clean-room:** Frappe là MIT, ERPNext là GPL-3.0, Astra là MIT. Astra không dùng một dòng code, schema hay asset nào của cả hai; chỉ mượn khái niệm và từ vựng ("DocType"). Nói điều này trước để không ai hiểu nhầm là port.

**Một câu tóm tắt:** *Astra là nền tảng ứng dụng metadata-driven kiểu Frappe, viết lại từ đầu bằng Java 25, nơi mọi thứ là plugin; ứng dụng đầu tiên là CMS, ứng dụng thứ hai là ERP; đổi mỗi ngày, không downtime, và mọi DocType đồng thời là tool cho AI agent.*

---

## 1. Học từ ai

| Nền tảng | Họ làm gì | Astra mượn gì |
| --- | --- | --- |
| **Frappe / ERPNext** | DocType metadata điều khiển schema, form, API, quyền, workflow. App là đơn vị cài đặt có hook. Custom Field và Property Setter cho phép tùy biến DocType lõi mà không fork, sống qua nâng cấp. Chứng từ submittable (nháp → đã ghi sổ → hủy; amend tạo bản mới) cho bất biến cấp kế toán. Bench chạy nhiều site. | Mô hình DocType, app-là-plugin, tùy-biến-là-overlay, vòng đời submittable, kỷ luật "mọi thứ sinh từ metadata". Xây lại bằng Java với lưu trữ zero-DDL, rollout cụm, và các tầng cô lập Frappe không có. |
| **Linux kernel** | Không bao giờ phá userspace. | `astra-spi` là userspace ABI. |
| **Kubernetes** | API alpha → beta → GA; GA deprecate vẫn chạy 12 tháng hoặc 3 release; rolling update dưới PDB. | Mức trưởng thành SPI, cùng cửa sổ deprecation, nâng kernel rolling có drain giao dịch. |
| **Stripe** | Phiên bản API theo ngày, ghim theo tài khoản. | Phiên bản app và API ghim theo tenant. |
| **Erlang / OTP** | Code cũ và mới cùng sống; lời gọi đang chạy kết thúc trên code cũ. | Phiên bản plugin song song, drain trước khi giải phóng. |
| **Shopify Functions** | Logic merchant chạy Wasm với giới hạn lệnh xác định. | Tầng 2 Wasm với ngân sách và metric theo lời gọi. |
| **Cloudflare Workers** | Isolate trong tiến trình thay container; tuyên bố khởi động nhanh ~100 lần. | Cô lập trong tiến trình thay vì mỗi tùy biến một microservice. |
| **Envoy / Proxy-Wasm** | Control plane phân phối filter Wasm tới mọi proxy. | Control plane plugin đưa cùng phiên bản tới mọi pod. |
| **VS Code** | Extension host riêng, API ổn định có kiểu, hàng chục nghìn extension. | Extension point kiểu mạnh + cô lập. |
| **Google SRE / Netflix** | Canary, error budget, rollback tự động. | Health gate, auto-rollback, DORA metric theo app. |
| **Alibaba → Apache Seata** | Điều phối giao dịch ở quy mô Singles' Day. | AT trên PostgreSQL, TCC trên Redis Cluster, smart bypass. |
| **MCP (Linux Foundation / AAIF)** | Chuẩn mở agent gọi tool; ChatGPT, Gemini, Copilot, Cursor, VS Code chấp nhận; 10.000+ server. | DocType và extension point chiếu thành MCP tool dưới cùng quyền. |

**Cách kể:** "Chúng tôi không hỏi 'làm ERP thế nào'. Chúng tôi hỏi 'Frappe làm gì đúng, Frappe thiếu gì, và Erlang, Stripe, Kubernetes giải phần thiếu ra sao', rồi ghép lại cho Java 25."

---

## 2. DocType engine: trái tim của nền tảng

**Ý chính để nói:** DocType engine là thứ biến Astra từ "CMS có plugin" thành nền tảng cùng hạng với Frappe. Một DocType mô tả đầy đủ một loại bản ghi nghiệp vụ. Mọi thứ người dùng hay agent chạm vào đều là DocType: bài viết, khách hàng, hoá đơn bán, bút toán sổ kho, định nghĩa workflow, thậm chí chính DocType.

### 2.1 DocType khai báo gì

Tên, module, loại (master, transaction, child, setup, ledger, virtual), chiến lược lưu trữ, submittable hay không, quy tắc đánh số, theo dõi thay đổi, danh sách field (Link, Table, Currency, Select, fetch-from…), index trên cột chiếu, quyền theo role và perm level kèm điều kiện cấp hàng, hook vòng đời (validate, on_submit, on_cancel…), workflow, và cấu hình API sinh ra (REST, GraphQL, MCP, phiên bản theo ngày).

### 2.2 Lưu trữ: zero-DDL mặc định, ledger nơi cần

Frappe tạo một bảng mỗi DocType và chạy DDL khi thêm Custom Field. Astra không bao giờ chạy DDL cho thay đổi form trong production, và vẫn có lưu trữ cấp kế toán nơi ERP cần.

| `storage` | Dùng cho | Cách làm | DDL |
| --- | --- | --- | --- |
| `jsonb` (mặc định) | Master, chứng từ, cấu hình, nội dung | Metadata lõi ở cột quan hệ có index; field ở JSONB với GIN. Field đánh dấu `projected` thành **generated column** có B-Tree để lọc, sắp xếp, báo cáo với tốc độ quan hệ. Chiếu và index tạo `CONCURRENTLY`, online. | Không bao giờ cho field; chiếu là online, additive, đảo ngược được |
| `ledger` | GL Entry, Stock Ledger Entry, bút toán lương, audit | Bảng quan hệ append-only, phân vùng theo kỳ, cột cố định. Không bao giờ update; sửa bằng bút toán đảo. Chỉ ghi trong `@AstraGlobalTransactional` bởi app sở hữu. | App sở hữu, tiến hóa bằng expand/contract |
| `virtual` | Hệ thống ngoài | `DocTypeAdapter` ánh xạ DocType lên ERP/CRM/DB cũ. Cùng API, quyền, tool. | n/a |

**Câu để nói:** "Một hoá đơn ở JSONB để tùy biến tự do; bút toán của nó ở ledger để bất biến và kiểm toán được. Hai chiến lược, một DocType engine."

### 2.3 Vòng đời chứng từ, phiên bản, amend

* **Submittable** mang `docstatus`: nháp, đã ghi sổ, hủy. Đã ghi sổ thì bất biến trừ field `allow-on-submit`. Hủy thì đảo bút toán; **amend** tạo bản mới (`INV-0001-1`) liên kết bản gốc. Đây là thứ làm hoá đơn khác bài blog.
* **Track changes** lưu diff mỗi lần save; phiên bản truy vấn được.
* **Link** kiểm tra khi ghi, bảo vệ toàn vẹn tham chiếu xuyên JSONB và ledger; `fetch-from` sao chép giá trị denormalized lúc ghi như chứng từ ERP cần.
* **Child table** là sub-document có thứ tự.
* **Naming series** an toàn trên cụm (bộ đếm Redis, chế độ không hở số cho chứng từ tài chính).

### 2.4 Tùy biến là overlay, sống qua nâng cấp

Tenant tùy biến DocType của app mà không fork, và tùy biến **sống qua mọi lần nâng cấp app**: thêm field, đổi thuộc tính field, thêm quyền, gắn server script Wasm, gắn client script cho Desk. Overlay là dữ liệu, có phiên bản, ghim theo tenant, áp lúc nạp metadata. App nâng cấp mà bỏ field overlay phụ thuộc thì **conformance check** bắt được trước rollout, không phải người dùng bắt được lúc chốt sổ cuối tháng.

Đây chính là bài học lớn nhất từ Frappe: Custom Field và Property Setter là lý do khách hàng ERPNext nâng cấp được. Astra giữ ý tưởng, làm chặt hơn bằng kiểm tra tự động.

### 2.5 Mọi thứ sinh từ metadata

REST `/api/doc/<DocType>`, GraphQL, MCP tool, Desk UI (form, list, kanban, calendar, tree, report builder, dashboard, workspace), print/PDF, notification/email/webhook, search và vector index, OpenAPI/JSON Schema/client type. Viết DocType một lần, có tất cả.

### 2.6 Báo cáo và script

Report Builder chạy trên cột chiếu. Query report là SQL có tham số trên chiếu và ledger, review như code. Script report và server script chạy ở tầng 2 Wasm có ngân sách, hoặc tầng 1 Java cho app tin cậy. Đây là ý tưởng Server Script của Frappe với sandbox thật thay vì interpreter bị hạn chế.

---

## 3. Mọi thứ là plugin ("app")

**Ý chính để nói:** Một app là plugin mang DocType, hook vòng đời, cài đặt extension point, fixture, patch, asset Desk. Astra CMS và các module Astra ERP là app; module riêng của tenant cũng là app. Kernel **không có đường tắt đặc quyền**.

* Một ứng dụng = kernel + danh sách app trong `astra.yaml`; `astra build` ra image bất biến.
* App có thể **định nghĩa extension point mới** cho app khác, như `PricingRule` của module bán hàng được tenant cài đặt bằng Wasm.
* Extension point là **interface Java có kiểu** với JSON Schema, kiểm tra lúc nạp, dispatch không reflection: hook DocType, workflow, report, print, Desk, route, event, job, nhánh TCC, ledger, adapter, auth, tool, agent, model provider, CLI.

### Ba tầng cô lập, một SPI

| | Tầng 0: Shared (build-time) | Tầng 1: Isolated JVM (runtime) | Tầng 2: Wasm sandbox (runtime) |
| --- | --- | --- | --- |
| Nạp | Compile vào image, Quarkus `@BuildStep` + Jandex | ClassLoader riêng theo phiên bản app | Module Chicory, ABI tương thích Extism |
| Chi phí gọi | Trực tiếp | `MethodHandle` bind lúc nạp | Bytecode compile từ Wasm; interpreter trên native |
| Đổi code | Rebuild image, rolling | Hot-swap | Hot-swap |
| Quyền | Tất cả, kể cả ledger và extension point mới | DocType, hook, workflow, report, route, event, job, TCC, tool, agent | Server script, script report, enricher, validator, tool |
| Bán kính lỗi | Cả pod | Bulkhead: timeout, giới hạn đồng thời, circuit breaker | Sandbox cứng: bộ nhớ, deadline, ngắt, không host call chưa cấp |
| Ngôn ngữ | Java/Kotlin | Java/Kotlin | Rust, Go, C/C++, C#, Zig, AssemblyScript, JS, Python (Extism PDK) |
| Mặc định cho | App lõi (kế toán, kho), thứ chạy trên mọi request | Module tenant, tích hợp, bản địa hoá | Script tenant, marketplace, **code do AI sinh** |

**Cách kể:** "Kế toán và kho ở tầng 0 vì mọi chứng từ đi qua. Bản địa hoá Việt Nam ở tầng 1 vì đổi theo thông tư. Script kiểm tra tín dụng của tenant ở tầng 2 vì ta chưa tin code đó nhưng muốn nó chạy ngay." Chuyển tầng là cờ build.

Chicory: pure Java, không JNI, có compiler Wasm → bytecode. ABI Extism: PDK sẵn cho nhiều ngôn ngữ; Astra tự cài ABI trên Chicory vì SDK Chicory chính thức của Extism còn thử nghiệm. Đánh đổi: Chicory chưa có fuel metering (dùng deadline + bộ nhớ); native image chỉ interpreter; tầng 1 "chống rò rỉ" ClassLoader chứ không tuyệt đối.

### Manifest app: ít quyền nhất, có xuất xứ

`astra-app.yaml`: id, version, khoảng SPI, tầng, phụ thuộc, danh sách DocType, overlay, fixture, patch, **capability** (host, secret, DocType, ledger chỉ đọc, ngân sách LLM), tenant, giới hạn runtime, compat, rollout, và **provenance** (ai hoặc công cụ nào sinh). Security review = review manifest. Ký Sigstore, SBOM, allow-list nhà phát hành, policy bắt code do AI sinh chạy tầng Wasm.

### Hot-swap toàn cụm

Publish → xác minh (chữ ký, SPI, xuất xứ) → announce qua Redis Pub/Sub → canary theo tỷ lệ pod với health gate → nạp song song, warm-up, trỏ dispatch nguyên tử, drain, kiểm tra rò rỉ → tự rollback toàn cụm khi health fail → pod mới từ HPA kéo trạng thái lúc boot.

---

## 4. Thay đổi liên tục không phá cái cũ: hợp đồng tương thích

**Ý chính để nói:** Kiến trúc sư doanh nghiệp nên nghe phần này đầu tiên. *Ship hàng ngày thì tính năng hôm qua còn chạy không, và downtime bằng không bằng cách nào?*

### 4.1 SPI là ABI

App chỉ phụ thuộc `astra-spi`. Mức trưởng thành `alpha`/`beta`/`stable`. Deprecate `stable` vẫn chạy ít nhất **12 tháng hoặc 3 minor release** kèm cảnh báo có cấu trúc. **Kernel N chạy app SPI N-1** qua shim. **Conformance suite:** golden app từ mọi SPI còn hỗ trợ, **cộng mọi overlay tenant đã đăng ký**, chạy trong CI; phá một cái thì không ship.

### 4.2 Phiên bản cùng sống, tenant tự ghim

N phiên bản app song song; bản cũ ở lại chừng nào còn tenant ghim hoặc còn lời gọi đang bay. Ghim theo tenant cho app và API. API sinh ra đánh phiên bản theo ngày, phục vụ song song.

### 4.3 Dữ liệu tiến hóa không khóa

| Dữ liệu | Cơ chế | DDL | Rollback |
| --- | --- | --- | --- |
| Field DocType, overlay | Zero-DDL JSONB; upcaster khi đọc, ghi lại dần; chiếu online | Không cho field | Tức thì |
| Ledger | Expand/contract do CI cưỡng chế; append-only | Additive, online | Bản trước vẫn đọc |
| Data patch | Idempotent, có phiên bản, chạy theo tenant lúc rollout, resume được | n/a | Patch bù |
| Event | `schemaVersion`; consumer khai báo khoảng; upcaster | n/a | Consumer cũ vẫn chạy |
| Cache | Key gồm phiên bản app và schema; vô hiệu hóa toàn cụm | n/a | Không stale |

### 4.4 Ma trận zero-downtime (slide một trang)

| Thay đổi | Cơ chế | Downtime | Rollback |
| --- | --- | --- | --- |
| App tầng 1/2, overlay, server script | Hot-swap, canary, drain | 0 | Giây, tự động |
| App tầng 0 | Image mới, rolling dưới PDB, drain giao dịch | 0 | Phút |
| Field, form, quyền, workflow của DocType | Phiên bản metadata, zero-DDL | 0 | Tức thì |
| Schema ledger | Expand/contract | 0 | App cũ vẫn chạy |
| Prompt, model, policy agent | Artifact có phiên bản, cùng đường rollout | 0 | Giây |
| Kernel minor / major | Rolling với shim / blue-green, tenant di chuyển bằng ghim | 0 | Phút / chuyển traffic |
| PostgreSQL / Redis / Seata TC | HA chuẩn; retry với TCC idempotent | 0 đọc, failover dưới giây cho ghi | n/a |

### 4.5 Thay đổi quan sát được

Mỗi rollout là sự kiện gắn metric. **DORA metric theo app** có sẵn.

---

## 4A. Đa tenant: thuộc tính của kernel, không phải tính năng của app

**Ý chính để nói:** Mọi dịch vụ kernel tự áp ranh giới tenant. App hay agent không thể vượt tenant kể cả khi cố, vì không API nào nhận tenant từ người gọi.

### 4A.1 Mô hình

`Tenant` là DocType kiểu `setup` của kernel, nên onboarding, tạm ngưng, offboarding là vòng đời chứng từ bình thường, có audit. Một Tenant khai báo: trạng thái, tổ chức cha (tuỳ chọn), profile cô lập, region, cách nhận diện và nhà cung cấp danh tính (OIDC, SAML, SCIM), khoá (BYOK, xoay khoá), app được bật kèm ghim và cấu hình, overlay, phiên bản API, gói dịch vụ, quota, feature flag.

### 4A.2 Nhận diện và ngữ cảnh

Thứ tự nhận diện: domain riêng hoặc subdomain, claim trong token OIDC, header cho tài khoản dịch vụ, tiền tố đường dẫn cho Desk. Kết quả bind một lần mỗi request vào `ScopedValue` và kế thừa sang mọi virtual thread, subtask, hook, job, agent. Một người dùng có thể thuộc nhiều tenant; đổi tenant là phiên mới với scope mới.

### 4A.3 Ba profile cô lập

| Profile | Dữ liệu | Khoá | Compute | Dùng cho |
| --- | --- | --- | --- | --- |
| `shared-rls` (mặc định) | Một cụm PostgreSQL; cột `tenant_id` + row-level security trên mọi bảng kể cả ledger và audit; partition theo hash tenant cho bảng lớn | Khoá dữ liệu riêng theo tenant dưới KMS của tenant | Pod chung, bulkhead theo tenant và app | Phần lớn tenant; hàng nghìn tenant mỗi pod |
| `database-per-tenant` | Database riêng, cùng schema, vẫn RLS | BYOK | Pod chung | Khách hàng bị quản lý chặt, tenant lớn |
| `cluster-per-tenant` | PostgreSQL, Redis, storage, pod Astra riêng | HYOK / HSM | Riêng | Chủ quyền dữ liệu, air-gapped |

Di chuyển giữa profile bằng `astra tenant migrate`: snapshot, giai đoạn ghi kép, cutover, kiểm chứng; đọc không gián đoạn, ghi dừng dưới giây lúc cutover.

### 4A.4 Cái gì được scope theo tenant

Dữ liệu và ledger (RLS, partition), metadata (DocType gốc toàn cục; overlay, ghim, feature flag theo tenant; metadata hiệu lực cache theo cặp tenant và phiên bản), app (cài toàn cục, bật, ghim, cấu hình theo tenant), cache (key gồm tenant), event và job (fair scheduling, giới hạn đồng thời theo tenant), giao dịch (key Seata và hash tag Redis gồm tenant), search và vector, object storage, danh tính (IdP, MFA, SCIM theo tenant), AI (model, region, ngân sách, che PII, cổng phê duyệt, opt-out theo tenant), tuân thủ (RoPA, DSAR, retention, residency theo tenant), quan sát (mọi span, metric, log gắn tenant; dashboard và SLO theo tenant).

### 4A.5 Tổ chức và chia sẻ

`Organisation` gom nhiều tenant (tập đoàn và công ty con, chuỗi nhượng quyền, agency và khách hàng). Trong tổ chức, DocType có thể khai báo `shareable` với policy rõ (master chỉ đọc như Item, mẫu hệ thống tài khoản; báo cáo hợp nhất). Chia sẻ luôn opt-in, có log, là metadata; mặc định vẫn cô lập chặt. Đa công ty trong một tenant là việc của DocType `Company`, không liên quan tenancy.

### 4A.6 Công bằng, quota, đo đếm

Bulkhead theo tenant và app: giới hạn virtual thread, rate limit, đồng thời job, ngân sách token agent. Tenant ồn tự làm chậm mình, không làm chậm hàng xóm. Quota từ gói dịch vụ, ghi đè theo tenant, có soft limit trước hard limit. Đo đếm phát bản ghi sử dụng (request, dung lượng, job, token agent, seat) dưới dạng DocType `ledger` cho tích hợp tính phí. Astra không ship billing; Astra ship đồng hồ đo.

### 4A.7 Vòng đời tenant

Provision (tạo Tenant, khoá, đặt region, bật app, chạy fixture và patch, kiểm chứng bằng conformance suite) → tùy biến (overlay, ghim, cấu hình, IdP; có phiên bản, có audit) → tạm ngưng (từ chối request với trạng thái riêng, job dừng, dữ liệu giữ) → export (toàn bộ DocType, file, audit, metadata, overlay ở định dạng mở) → offboard (áp retention, xoá bằng huỷ khoá, ledger giữ theo nghĩa vụ pháp lý với tham chiếu ẩn danh, ghi bằng chứng xoá) → migrate (giữa profile hoặc region).

### 4A.8 Chế độ một tenant

`tenancy.mode: single` giữ mọi cơ chế nhưng cố định một tenant: triển khai tự host cho một công ty không trả overhead tenancy, và sau này chuyển sang `multi` mà không cần migrate dữ liệu.

**Câu để nói:** "Một công ty tự host hôm nay, một SaaS phục vụ hàng nghìn công ty ngày mai, cùng một codebase, cùng một mô hình dữ liệu, không migration."

---

## 4B. Tuân thủ từ thiết kế: sẵn sàng go global

**Nói thẳng trước:** SOC 2 Type II và ISO 27001 chứng nhận *nhà vận hành* (tổ chức chạy dịch vụ) qua một kỳ quan sát, không chứng nhận phần mềm. GDPR và các luật tương đương ràng buộc *bên kiểm soát và bên xử lý*. Không có mã nguồn mở nào "được chứng nhận SOC 2". Việc một nền tảng làm được là **khiến các kết quả đó rẻ**: có sẵn control, tự sinh bằng chứng, và giữ mô hình dữ liệu tuân thủ ngay từ cấu trúc. Astra Cloud (nếu có) sẽ tự theo đuổi chứng nhận; người tự host thừa hưởng control và đường ống bằng chứng.

**Ý chính để nói:** Phần lớn công sức tuân thủ là thu thập bằng chứng và biết dữ liệu nào nằm ở đâu, vì sao, bao lâu. Nếu DocType đã trả lời sẵn bốn câu đó cho từng field, thì RoPA, DSAR, retention, residency là **sinh ra**, không phải **làm tay**.

### 4B.1 Khung tuân thủ trong phạm vi

| Khung | Phạm vi | Astra cung cấp |
| --- | --- | --- |
| **SOC 2 Type II** | Security, Availability, Processing Integrity, Confidentiality, Privacy | Control cho các tiêu chí CC6 (truy cập), CC7 (giám sát), CC8 (quản lý thay đổi), A1, PI1, P-series; xuất bằng chứng liên tục; ma trận control trong `docs/compliance/` |
| **ISO/IEC 27001:2022** + 27017/27018/27701 | ISMS, cloud, PII trên cloud, quản lý quyền riêng tư | Ánh xạ Annex A, kiểm kê tài sản từ metadata DocType, workflow rà soát truy cập, sổ đăng ký nhà xử lý phụ |
| **GDPR / UK GDPR** | Dữ liệu cá nhân EU/UK | Phân loại, cơ sở pháp lý, sinh RoPA, tự động DSAR, retention và xoá, quy trình vi phạm 72 giờ, mẫu DPIA, hồ sơ xử lý, profile lưu trữ EU |
| **Luật Bảo vệ dữ liệu cá nhân Việt Nam** (Luật 91/2025/QH15, Nghị định 356/2025, hiệu lực 1/1/2026, thay Nghị định 13/2023) | Dữ liệu cá nhân của chủ thể Việt Nam | Phân loại dữ liệu cơ bản / nhạy cảm, hồ sơ đồng ý, hồ sơ đánh giá tác động, hồ sơ chuyển dữ liệu xuyên biên giới, thời hạn phản hồi yêu cầu, profile lưu trữ VN |
| **CCPA/CPRA, LGPD, PDPA (SG), APPI, PIPL** | Luật riêng tư khu vực | Cùng một privacy engine với profile theo khu vực pháp lý; quy tắc nội địa hoá (PIPL, VN) cưỡng chế bởi `ResidencyPolicy` |
| **SOX ITGC** (khách hàng ERP) | Quản lý thay đổi, truy cập, phân tách nhiệm vụ, audit trail | Rollout có ký, có phê duyệt, có audit; quy tắc SoD trên hành động DocType; ledger bất biến; khoá kỳ; rà soát truy cập |
| **PCI DSS 4.0.1** | Dữ liệu thẻ | Thiết kế để dữ liệu thẻ **nằm ngoài phạm vi**: token hoá qua nhà cung cấp thanh toán, không lưu PAN |
| **HIPAA** (profile tuỳ chọn) | Dữ liệu y tế Mỹ | Mã hoá cấp field, log truy cập, control sẵn cho BAA |
| **EU AI Act** (theo giai đoạn 2025–2027; mốc high-risk đang được lùi theo Digital Omnibus) + **ISO/IEC 42001**, **NIST AI RMF** | Hệ thống AI và quản trị | Log hành động agent, cổng phê duyệt của con người, phiên bản model và prompt, thông báo minh bạch, che PII trước khi gọi model ngoài, tenant opt-out, kiểm kê AI |
| **EU DORA** (tài chính, từ 1/2025), **NIS2** | Khả năng phục hồi vận hành, rủi ro bên thứ ba ICT | Hook kiểm thử phục hồi, workflow báo cáo sự cố, bằng chứng RTO/RPO, sổ nhà xử lý phụ |
| **EU Cyber Resilience Act** (báo cáo từ 9/2026, áp dụng đầy đủ 12/2027) | Sản phẩm có yếu tố số | SBOM mỗi bản phát hành, quy trình xử lý lỗ hổng, công bố có phối hợp, chính sách cập nhật bảo mật; dự án Astra hoạt động như open-source steward |
| **WCAG 2.2 AA**, **EU Accessibility Act** (6/2025), **Section 508** | Khả năng tiếp cận | Desk UI xây theo WCAG 2.2 AA; kiểm tra tự động và thủ công trong CI |
| **OWASP ASVS, NIST SSDF, SLSA, OpenSSF Scorecard, CIS Kubernetes** | Kỹ thuật an toàn và chuỗi cung ứng | ASVS Level 2 nền, Level 3 cho auth, thanh toán, ledger; SDLC theo SSDF; build SLSA Level 3; Scorecard trong CI; Helm mặc định cứng hoá |

### 4B.2 Metadata tuân thủ trên DocType

Khai báo tuân thủ ở đúng chỗ khai báo dữ liệu. Mỗi field có thể mang: `classification` (public → restricted), `pii` / `sensitive` (kể cả nhóm đặc biệt: sức khoẻ, sinh trắc), `legal-basis` (consent, contract, legal-obligation…), `retention` (thời hạn, mốc, lý do, rồi xoá / ẩn danh / lưu trữ), `residency` (tenant-region, eu-only, vn-only), `encrypt` (field, deterministic để tìm được), `mask` và `dsar` (export / redact / exclude).

Từ đó nền tảng **sinh ra**: Record of Processing Activities, bản đồ dữ liệu theo tenant, gói DSAR, lịch retention, khung DPIA và hồ sơ đánh giá tác động theo luật Việt Nam, và kiểm kê dữ liệu mà ISO 27001 yêu cầu.

**Câu để nói:** "Ở Astra, câu hỏi 'dữ liệu cá nhân nằm ở đâu' có câu trả lời bằng một truy vấn metadata, không phải một dự án ba tháng."

### 4B.3 Control trong kernel

* **Danh tính và truy cập.** OIDC và SAML SSO, bắt buộc MFA, SCIM cấp/thu hồi, RBAC cộng quy tắc thuộc tính, mặc định ít quyền nhất, **rà soát truy cập** định kỳ sinh thành workflow, tài khoản break-glass có lý do bắt buộc và tự hết hạn.
* **Phân tách nhiệm vụ (SoD).** Quy tắc cấp DocType: người tạo Payment Entry không được submit nó; chuỗi phê duyệt là trạng thái workflow; vi phạm bị chặn và ghi log.
* **Audit trail chống sửa.** Mọi thay đổi DocType, quyền, rollout, hành động agent, hành động admin, export được ghi vào DocType `ledger` append-only có chuỗi hash, xuất được ra WORM và SIEM (`AuditSink`: OpenTelemetry, syslog, OCSF). Retention và legal hold áp cả cho audit.
* **Mã hoá và khoá.** TLS 1.3, mã hoá at rest, mã hoá cấp field, khoá từ `KeyProvider` (AWS KMS, GCP KMS, Azure Key Vault, Vault, HSM), **BYOK theo tenant**, xoay khoá, crypto-shredding để xoá nơi bản ghi phải bất biến.
* **Vận hành quyền riêng tư.** Cổng và API DSAR: truy cập, export (JSON/CSV), sửa, xoá, hạn chế, di chuyển, phản đối, theo dõi thời hạn theo khu vực pháp lý. Xoá trên ledger: ẩn danh tham chiếu chủ thể và huỷ khoá, giữ bản ghi tài chính theo cơ sở nghĩa vụ pháp lý. Đồng ý là DocType có mục đích phiên bản hoá. Workflow vi phạm với đồng hồ 72 giờ.
* **Nơi lưu trữ và chủ quyền dữ liệu.** Tenant ghim region; `ResidencyPolicy` cưỡng chế trên lưu trữ chính, cache, backup, search, object storage, **và nhà cung cấp LLM**. Profile `eu`, `us`, `vn`, `sg`, `custom`. Không nhân bản PII xuyên region trừ khi hồ sơ chuyển dữ liệu cho phép.
* **Quản lý thay đổi.** Artifact có ký, SBOM, xuất xứ; phê duyệt bốn mắt cho production; tách môi trường; mọi rollout có audit; canary và rollback là control bù; DORA metric là bằng chứng.
* **Sẵn sàng và phục hồi.** HA mọi tầng, backup mã hoá có PITR, **kiểm thử restore là job định kỳ có bằng chứng**, RPO/RTO công bố, hook kiểm thử chaos và failover, workflow sự cố.
* **Lỗ hổng và chuỗi cung ứng.** SCA, SAST, DAST, quét container trong CI; SLA phản hồi CVE; `SECURITY.md`; SBOM CycloneDX; xuất xứ SLSA Level 3; OpenSSF Scorecard; pentest bên thứ ba hàng năm cho triển khai tham chiếu.
* **Cô lập tenant.** Row-level security, khoá theo tenant, bulkhead chống hàng xóm ồn, và profile **DB-per-tenant** hoặc **cluster-per-tenant** cho khách hàng bị quản lý chặt.
* **Quản trị AI.** Mọi hành động agent log kèm model, phiên bản prompt, hash input, output; **cổng phê duyệt con người** cho hành động tác động lớn (ghi ledger, đổi quyền, thao tác hàng loạt) cấu hình theo tenant; che PII trước khi gọi model ngoài; model ghim region hoặc local; kiểm kê AI và thông báo minh bạch sinh từ metadata.
* **Khả năng tiếp cận.** Desk theo WCAG 2.2 AA, kiểm tra axe tự động và audit thủ công.

### 4B.4 Tự động hoá bằng chứng

`astra compliance evidence --framework soc2 --period 2026-Q3` xuất hồ sơ rà soát truy cập, hồ sơ thay đổi có phê duyệt, kết quả backup và restore, SLA lỗ hổng, log sự cố, chứng minh toàn vẹn audit trail. Connector đẩy cùng bằng chứng sang nền tảng tự động hoá tuân thủ (Vanta, Drata, Secureframe…). Catalogue control ở dạng máy đọc được (OSCAL) trong `docs/compliance/`.

### 4B.5 Ma trận trách nhiệm chia sẻ

| Lĩnh vực | Nền tảng Astra | Nhà vận hành / tenant |
| --- | --- | --- |
| Control trong phần mềm | Cung cấp và kiểm thử | Cấu hình, rà soát |
| Sinh bằng chứng | Tự động hoá và xuất | Lưu giữ, trình auditor |
| Chính sách, đánh giá rủi ro, đào tạo, quản lý nhà cung cấp | Mẫu và sổ đăng ký | Sở hữu |
| Chứng nhận (báo cáo SOC 2, chứng chỉ ISO) | Astra Cloud tự theo đuổi | Người tự host theo đuổi cho phạm vi của mình |
| Vai trò pháp lý (controller, processor, DPO) | Hỗ trợ cả hai vai | Quyết định và ghi nhận |

---

## 5. Hướng tới ERP: Astra ERP trên Astra

**Ý chính để nói:** Astra ERP là bằng chứng nền tảng đủ tổng quát. Xây đúng cách ERPNext xây trên Frappe: **mỗi module là app, mỗi bản ghi là DocType, mỗi bút toán là ledger entry**. Sao chép *hình dạng* của ERPNext, không sao chép code.

| Module (app) | DocType lõi | Ledger | Ghi chú |
| --- | --- | --- | --- |
| `erp.core` | Company, Fiscal Year, Currency, Exchange Rate, UoM, Address, Contact | | Đa công ty, đa tiền tệ từ ngày đầu |
| `erp.accounts` | Account (cây), Journal Entry, Payment Entry, Sales/Purchase Invoice, Tax Template, Cost Center, Period Closing | **GL Entry** | Kế toán kép; bút toán đảo, không sửa; khoá kỳ |
| `erp.stock` | Item, Warehouse (cây), Stock Entry, Delivery Note, Purchase Receipt, Batch, Serial No | **Stock Ledger Entry** | FIFO / bình quân; repost lùi ngày là job; giữ hàng bằng Redis TCC cho flash sale |
| `erp.selling` | Customer, Quotation, Sales Order, Pricing Rule | | Pricing Rule là extension point tenant cài đặt |
| `erp.buying` | Supplier, RFQ, Purchase Order | | |
| `erp.manufacturing` | BOM, Work Order, Job Card, Routing | | |
| `erp.hr` | Employee, Leave, Attendance, Payroll Entry, Salary Slip | **Payroll Ledger** | |
| `erp.projects` / `erp.crm` / `erp.assets` | Project, Task, Timesheet / Lead, Opportunity / Asset, Depreciation | GL qua `erp.accounts` | |

Tính năng nền tảng mà ERP cần và kernel đã có sẵn:

* **Ghi sổ một hoá đơn bán** chạm GL Entry, Stock Ledger Entry, công nợ khách, và giữ hàng trong Redis. Đó là **một** phương thức `@AstraGlobalTransactional`: Postgres qua Seata AT, Redis qua TCC, tất cả hoặc không gì.
* **Chốt sổ cuối tháng** trên 50 pod: khoá kỳ là trạng thái DocType do kernel cưỡng chế trong hook vòng đời, không phải code ứng dụng nhớ kiểm tra.
* **Bản địa hoá** (hoá đơn điện tử, VAT, hệ thống tài khoản Việt Nam) là app tầng 1 với overlay và fixture, ghim theo tenant, nâng cấp độc lập với `erp.accounts`.
* **Agent trong back office:** "tạo bút toán cho dòng sao kê này", "giải thích chênh lệch này" chạy như `AgentContributor` với MCP tool theo role trên DocType, có ngân sách và audit.

**So với Frappe/ERPNext (công bằng, không chê):** Frappe dùng Python và MariaDB/Postgres, một bảng mỗi DocType, DDL khi thêm Custom Field, worker Gunicorn và hàng đợi RQ, mỗi site một DB. Đó là lựa chọn hợp lý cho SME và cộng đồng rộng. Astra nhắm quy mô cụm, đa tenant mật độ cao, zero-DDL, cô lập nhiều tầng, giao dịch xuyên kho, và AI surface. Hai triết lý, cùng một ý tưởng lõi.

---

## 6. Hiệu năng: nguyên tắc và mục tiêu

Không reflection trên đường nóng; đọc DocType từ cache L1/L2 theo phiên bản metadata; ghi qua cột chiếu để báo cáo không quét JSONB; bulkhead theo app; không tốn gì khi rảnh; cô lập trong tiến trình; structured concurrency cho fan-out.

| Chỉ số | Mục tiêu (kiểm chứng bằng benchmark công khai) |
| --- | --- |
| Lấy DocType theo id (L1 hit) p99 | < 2 ms |
| List DocType có lọc trên cột chiếu, 1 triệu dòng/tenant, p99 | < 20 ms |
| Ghi sổ hoá đơn bán (GL + kho + giữ hàng Redis, Seata) p99 | < 40 ms cùng region |
| Overhead dispatch tầng 1 | < 1 µs |
| Server script tầng 2 p99 | < 5 ms kể cả vào sandbox |
| Lan truyền hot-swap, 50 pod | < 500 ms |
| Pod kernel sẵn sàng | < 2 s |

**Java 25 và Quarkus (ngắn):** virtual thread không pin (JEP 491), Scoped Values final (JEP 506), Structured Concurrency preview 5 (JEP 505, bọc sau executor), Generational ZGC mặc định (JEP 490, `-XX:+UseZGC`). Quarkus build-time augmentation là cơ chế tầng 0; hỗ trợ đầy đủ JDK 25 từ 3.31, LTS 3.27. Java 21 chưa đủ; Spring Boot dựa reflection runtime, đi ngược tầng 0; cái giá là tự viết extension Seata.

---

## 7. AI-native: AI xây, AI dùng

**Cho agent xây app:** SPI nhỏ có kiểu và DocType YAML có JSON Schema là hợp đồng agent cài đặt đáng tin. Testkit cho vòng lặp pass/fail (contract test, assertion capability, ngân sách, **assertion overlay sống qua nâng cấp**). Code AI sinh mặc định tầng 2 Wasm; `provenance.generator` ghi lại; policy bắt buộc sandbox. Canary + auto-rollback bắt lỗi dưới traffic thật. Repo có `AGENTS.md`, `CLAUDE.md`, `llms.txt`; `astra mcp --dev` introspect kernel.

**Cho agent dùng ứng dụng:** mọi DocType (search, get, create, submit, cancel), event, job, `ToolContributor` chiếu thành MCP tool với JSON Schema của DocType, theo role và tenant, audit như mọi lời gọi. `AgentContributor` chạy agent có ngân sách với bộ nhớ phạm vi (DocType + pgvector). `ModelProvider` trừu tượng Claude, OpenAI, Gemini, model local. Prompt và eval là artifact có phiên bản đi cùng đường rollout.

**Câu để nói:** "AI viết, nền tảng kiểm chứng, cụm rollout, sai thì cụm tự lùi. Và kế toán viên AI làm việc trên cùng DocType, cùng quyền, cùng audit như kế toán viên người."

---

## 8. Bảng so sánh (slide một trang)

| Nền tảng | Metadata-driven | Extension point kiểu mạnh | Hot-swap không restart | Phiên bản song song, ghim tenant | Cô lập | Zero-DDL khi tùy biến | Giao dịch xuyên kho | Tool surface cho agent |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Frappe / ERPNext | **Có** | Hook Python | Một phần (bench restart) | Không | Không | Không (DDL) | Không | Gắn thêm |
| WordPress / Strapi | Không | Không | Có | Không | Không | Không | Không | Gắn thêm |
| Keycloak SPI | Không | Có | Không | Không | Không | n/a | Không | Không |
| OSGi / Liferay | Một phần | Có | Có | Một phần | ClassLoader | n/a | Không | Không |
| Shopify Functions | n/a | Có | Có | Theo app | Wasm | n/a | n/a | n/a |
| **Astra** | **Có** | **Có** | **Tầng 1 & 2** | **Có, theo tenant** | **Shared / JVM / Wasm** | **Có** | **Seata AT + TCC** | **MCP, theo capability** |

---

## 9. Rủi ro và đánh đổi (để trả lời Q&A)

| Rủi ro | Mức | Cách xử lý |
| --- | --- | --- |
| Phạm vi rất lớn (nền tảng + CMS + ERP) | Cao | Lộ trình 9 phase; kernel và DocType engine trước, ERP sau; mỗi module ERP là app độc lập |
| Độ phức tạp tổng thể | Cao | Mọi thứ tắt được; dự án nhỏ chạy một pod, Postgres + Redis đơn, không Seata, không MCP |
| Extension Seata cho Quarkus tự viết; Seata còn incubating | Cao | Ưu tiên Phase 4; benchmark công khai; Seata dùng rộng ở TMĐT Trung Quốc |
| JSONB cho ERP có đủ không | Trung bình | Cột chiếu cho lọc/báo cáo; ledger quan hệ append-only cho GL/SLE; benchmark 1 triệu dòng/tenant |
| Chicory thiếu fuel metering; Extism Chicory SDK thử nghiệm | Trung bình | Deadline + memory cap; tự cài ABI |
| Structured Concurrency preview | Trung bình | Bọc sau executor |
| Rò rỉ ClassLoader tầng 1 | Thấp–Trung bình | Theo dõi lifecycle, khuyến nghị tầng 2 |
| SPI và DocType model phải ổn định sớm | Trung bình | Mức trưởng thành, deprecation, shim, conformance suite có overlay |
| Mục tiêu hiệu năng chưa đo | Trung bình | Benchmark Phase 2, sửa theo số thật |
| Code AI sinh ra trong production | Trung bình | Sandbox mặc định, provenance, contract test, canary |
| Nhầm lẫn giấy phép với ERPNext | Trung bình | Clean-room ghi trong README và CONTRIBUTING; không nhận PR chép code GPL |
| Hứa "tuân thủ" quá tay | Trung bình | Nói rõ: nền tảng cung cấp control và bằng chứng; chứng nhận là của nhà vận hành; ma trận trách nhiệm chia sẻ công khai |
| Luật thay đổi (AI Act lùi mốc, PDPL mới có nghị định) | Trung bình | Profile theo khu vực pháp lý là dữ liệu, cập nhật như app; không hard-code luật vào kernel |
| Xoá dữ liệu cá nhân trên ledger bất biến | Trung bình | Ẩn danh tham chiếu + crypto-shredding; giữ bản ghi tài chính theo cơ sở nghĩa vụ pháp lý; ghi rõ trong DPIA |

---

## 10. Lộ trình

1. **Phase 1 (đang làm):** khung Maven, `astra-spi`, mô hình DocType và YAML schema **kèm thuộc tính tuân thủ**, manifest, mức trưởng thành, deprecation, `AGENTS.md`/`llms.txt`, `SECURITY.md`, khung catalogue OSCAL.
2. **Phase 2:** kernel, plugin host ba tầng, **lõi tenancy** (Tenant DocType, nhận diện, ngữ cảnh `ScopedValue`, RLS, profile `shared-rls`, bulkhead), DocType engine lõi (JSONB, chiếu, naming, link, child table, quyền, submittable, phiên bản), **audit ledger chống sửa từ lần ghi đầu tiên**, REST sinh ra, testkit, pipeline build SLSA Level 3, benchmark đầu tiên.
3. **Phase 3:** control plane và tương thích: registry, chữ ký, SBOM, phê duyệt bốn mắt, canary, shim, conformance suite có overlay, data patch, DORA.
4. **Phase 4:** extension Seata, ledger storage, cache bus, upcaster, API theo ngày.
5. **Phase 5: Gói tuân thủ và tenancy.** Danh tính (OIDC/SAML, MFA, SCIM theo tenant), rà soát truy cập, SoD, profile `database-per-tenant` và `cluster-per-tenant`, migrate tenant, tổ chức và policy chia sẻ, quota và đo đếm, `KeyProvider` với BYOK và mã hoá cấp field, `ResidencyPolicy` và profile region (eu, us, vn, sg), privacy engine (RoPA, DSAR, đồng ý, retention, crypto-shredding, workflow vi phạm), `AuditSink` ra SIEM và nền tảng tuân thủ, CLI xuất bằng chứng, công bố ánh xạ SOC 2 / ISO 27001 / GDPR / PDPL.
6. **Phase 6:** Desk theo WCAG 2.2 AA, overlay, workflow, report, print, notification.
7. **Phase 7:** AI surface và quản trị AI: MCP, LLM gateway có che PII và ghim region, agent runtime có ngân sách và cổng phê duyệt, prompt registry, kiểm kê AI.
8. **Phase 8:** Helm cứng hoá theo CIS, Operator, Seata Raft, OpenTelemetry, Prometheus, job bằng chứng backup/restore, kiểm thử failover, pentest bên thứ ba.
9. **Phase 9:** Astra CMS.
10. **Phase 10:** Astra ERP: core, accounts, stock, rồi selling, buying, manufacturing, hr, projects, crm, assets; bằng chứng SOX ITGC; bản địa hoá Việt Nam (hoá đơn điện tử, VAT, hồ sơ PDPL) là app tầng 1 tham chiếu; marketplace có ký.

---

## 11. Câu kết

> *"Astra là nền tảng ứng dụng metadata-driven kiểu Frappe, viết lại từ đầu bằng Java 25. Mọi thứ là plugin, kể cả CMS và ERP. DocType là dữ liệu, tùy biến là overlay sống qua nâng cấp, bút toán là ledger bất biến, và tuân thủ là thuộc tính của metadata. App có phiên bản chạy song song, ghim theo tenant, hot-swap toàn cụm với canary tự rollback. Mọi thao tác ghi xuyên PostgreSQL – Redis là một giao dịch thật. Mọi DocType đồng thời là tool cho AI agent, dưới cùng quyền và audit. Ship mỗi ngày, không bao giờ phá hôm qua, và sẵn sàng cho auditor ở bất kỳ nước nào."*

---

## Phụ lục: câu hỏi thường gặp dự kiến

**"Đây có phải port ERPNext sang Java không?"** — Không. Frappe là MIT, ERPNext là GPL-3.0, Astra là MIT. Không dùng code, schema, fixture hay asset nào. Chỉ mượn khái niệm DocType, app, overlay, submittable, và hình dạng module ERP. Mọi cài đặt là nguyên bản.

**"Sao không dùng luôn ERPNext?"** — ERPNext rất tốt cho SME. Astra nhắm quy mô cụm, đa tenant mật độ cao, zero-DDL khi tùy biến, cô lập nhiều tầng cho code bên thứ ba và AI, giao dịch xuyên Postgres–Redis, và AI surface. Nếu bạn không cần những thứ đó, ERPNext là lựa chọn hợp lý hơn.

**"JSONB có chịu được ERP không?"** — Field linh hoạt ở JSONB, field lọc/báo cáo ở cột chiếu có index, bút toán ở ledger quan hệ append-only. Không có truy vấn báo cáo nào quét JSONB. Sẽ benchmark 1 triệu dòng/tenant ở Phase 2.

**"Nâng app có phá tùy biến của tenant không?"** — Overlay là dữ liệu có phiên bản, áp lúc nạp metadata; conformance suite chạy overlay của mọi tenant trước rollout; phá thì không ship.

**"Zero downtime thật không?"** — Tầng 1/2, overlay, DocType, prompt: 0 và rollback trong giây. Tầng 0 và kernel: rolling dưới PDB. Failover hạ tầng: dưới giây cho ghi, nói thẳng.

**"Code AI sinh chạy production có an toàn không?"** — Sandbox Wasm mặc định với ngân sách; xuất xứ ghi lại; contract test; canary + auto-rollback. Nhiều lớp hơn phần lớn code do người viết.

**"Seata production-ready chưa?"** — Vào Apache Incubator 10/2023, bản 2.6 (2/2026), đang graduate, dùng rộng ở TMĐT Trung Quốc. Phần chưa chín là cầu nối Quarkus, và đó là việc Astra làm.

**"MCP có ổn định không?"** — Trao cho Agentic AI Foundation thuộc Linux Foundation 12/2025, đồng sáng lập Anthropic, Block, OpenAI, hậu thuẫn Google, Microsoft, AWS. Chuẩn mở đa nhà cung cấp.

**"Astra có được chứng nhận SOC 2 hay ISO 27001 không?"** — Không có mã nguồn mở nào được chứng nhận; chứng nhận là của nhà vận hành qua kỳ quan sát. Astra cung cấp control, sinh bằng chứng, và giữ mô hình dữ liệu tuân thủ từ cấu trúc, để nhà vận hành tới chứng nhận nhanh và rẻ. Ma trận trách nhiệm chia sẻ công khai trong README.

**"Dữ liệu khách hàng Việt Nam có phải lưu ở Việt Nam không?"** — Tenant ghim region `vn`; `ResidencyPolicy` cưỡng chế trên lưu trữ, cache, backup, search, và cả lời gọi LLM. Hồ sơ chuyển dữ liệu xuyên biên giới theo Luật 91/2025 và Nghị định 356/2025 sinh từ metadata.

**"Xoá dữ liệu cá nhân thế nào khi bút toán phải bất biến?"** — Ẩn danh tham chiếu chủ thể và huỷ khoá mã hoá (crypto-shredding); bản ghi tài chính giữ lại theo cơ sở nghĩa vụ pháp lý; ghi rõ trong DPIA. Đây là cách GDPR cho phép và kế toán yêu cầu.

**"AI agent ghi bút toán có cần người duyệt không?"** — Mặc định có. Cổng phê duyệt con người cấu hình theo tenant cho hành động tác động lớn; mọi hành động agent log kèm model và phiên bản prompt; PII được che trước khi gọi model ngoài.

**"Đa tenant có làm chậm hay rủi ro rò rỉ không?"** — Ranh giới tenant do kernel áp qua `ScopedValue` và RLS, không API nào nhận tenant từ người gọi. Bulkhead theo tenant nên tenant ồn chỉ làm chậm chính mình. Khách hàng cần tách biệt hơn chọn DB riêng hoặc cụm riêng mà không đổi code.

**"Code đâu? Số hiệu năng đâu?"** — Chưa có. Đây là blueprint; benchmark ở Phase 2. Minh bạch quan trọng hơn gây ấn tượng.
