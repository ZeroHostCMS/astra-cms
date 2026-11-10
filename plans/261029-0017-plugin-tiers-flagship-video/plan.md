# Video flagship "Phần mềm tự tiến hoá": Plugin Tier 0 · 1 · 2

Status: plan (sửa lần 2, 2026-11-10) · Branch đề xuất: `feat/plugin-tiers-demo` cắt từ `main`
Nguồn ý tưởng: slide `Global_Future.pdf` trang 1 (ba lớp: Lõi · Tích hợp thị trường · Tự tiến hoá)
Dựa trên: [Platform feature demos](../261021-1723-astra-platform-feature-demos/plan.md), quy trình video v01–v21 trong `docs/video/`

## Outcome
Một video flagship ~7 phút và 3 clip ngắn (một clip mỗi tier) minh hoạ ba lớp của slide bằng **code chạy thật** trên cụm k3d,
câu chuyện Astra Express trung tính (không logo Viettel), lời đọc tiếng Việt, upload R2:

| Lớp trên slide | Tier | Điều video chứng minh |
| --- | --- | --- |
| Lớp 1 · Lõi Astra | **Tier 0** (build-time, gọi trực tiếp) | Lõi biên dịch vào image; chỉ Tier 0 ghi sổ cái; đổi lõi = image mới + rolling update, traffic không lỗi. **Rule Engine Drools** trong lõi, luật là dữ liệu: bảng cước từng hard code chuyển thành luật, BA thêm phụ phí trên Desk, duyệt là có hiệu lực, luật sai/nguy hiểm bị chặn khi lưu |
| Lớp 2 · Tích hợp thị trường | **Tier 1** (isolated JVM, hot-swap) | **Hãng vận chuyển FastShip**: v1.0 → v1.1 thay khi đang chạy, **canary theo pod** (1 pod → cả cụm), bản lỗi tự rollback, plugin không gọi được host ngoài quyền. **Hoá đơn điện tử theo nước**: tenant Việt Nam dùng `einvoice-vn`, tenant Campuchia mới dùng `einvoice-kh`, "thêm nước = thêm mô-đun", không khởi động lại |
| Lớp 3 · Tự tiến hoá có phê duyệt | **Tier 2** (Wasm sandbox) + AI qua MCP | **Plugin "Khai giá Astra"**: BA/PO dùng AI biến phụ lục hợp đồng của một khách thành plugin **kế thừa giá tầng Rule**: chỉ khai báo phần khác biệt (giảm theo bậc sản lượng, bỏ phí COD, thêm phụ phí cồng kềnh), không tính lại từ đầu; backtest trên đơn tháng trước; duyệt; đơn hiện từng dòng gốc Rule và thao tác của plugin; BA đổi luật chung thì khách có plugin tự kế thừa; đổi phụ lục → sửa vài dòng, hot-reload trong vài giây. **Cảnh báo bất thường**: Claude Code đọc dữ liệu vận hành qua MCP, viết **bộ phát hiện bất thường** bằng Rust → Wasm, backtest trên 30 ngày dữ liệu, nộp bản nháp; AI không tự phát hành được; quản lý duyệt; canary theo pod; cảnh báo đến **đúng người** (OPS, kế toán, CSKH); người dùng bác cảnh báo sai → AI đọc phản hồi, đề xuất bản v2 |

## Quyết định (user, 2026-10-29)
- Tier 1: **xây lát mỏng thật** (loader, hot-swap, ghim theo tenant, canary + tự rollback).
- Canary: **theo pod**, không chia % request. Cụm demo 2 pod nên các bước là 1/2 pod → 2/2 pod; cấu hình 10→50→100 % của slide là
  cho cụm production nhiều pod, video nói rõ.
- Plugin Lớp 2: **hãng vận chuyển** + **hoá đơn điện tử theo nước**.
- Lớp 3: **AI thật qua MCP + duyệt**, bài toán **cảnh báo bất thường**.
- Đầu ra: **1 video flagship + 3 clip**, **chỉ tiếng Việt**, **upload R2** như các video trước.
- Thương hiệu: **Astra Express trung tính**; sơ đồ ba lớp vẽ lại theo phong cách Astra.
- Phiên Claude Code: **Claude chạy và ghi bằng asciinema**, dựng thành mp4, giữ transcript làm bằng chứng.

## Quyết định bổ sung (user, 2026-11-10)
- Thêm 2 ví dụ vào cùng video: **Rule Engine Drools ở Tier 0** và **plugin Khai giá Astra (Wasm hot-reload, AI cấu hình)**.
- "Khai giá" = **báo giá cước cho từng khách** nói chung (không riêng bảo hiểm giá trị hàng).
- Drools: **engine ở Tier 0, luật là dữ liệu** (BA sửa trên Desk, biên dịch lại khi đang chạy), đi qua cùng đường duyệt + canary của phase 2.
- Định giá **xếp tầng có kế thừa**: tầng Rule (Drools) tính trước ra các dòng giá; plugin riêng (Wasm, BA/PO viết bằng AI) nhận các dòng đó và chỉ
  trả thao tác trên dòng (scale/set/remove/add), không tính lại từ đầu; dòng không đụng giữ nguyên; đơn lưu `price_breakdown` có nguồn gốc từng dòng.
  Cần ABI bộ nhớ mới cho sandbox (`callBytes`) và PDK Rust.
- Drools: **ghim phiên bản 10.1.0**, chưa nâng lên khi KIE chưa hỗ trợ chính thức Java 25.
- **Giữ 3 clip**: Drools vào clip Tier 0, Khai giá vào clip Tier 2.

## Non-goals
- Chữ ký Sigstore/SBOM, OCI registry (chỉ ghim SHA-256 + ghi `signed_by`/`generator`).
- Canary theo % request (đã chọn theo pod).
- OTA app mobile, "nới quyền AI theo mức tin cậy" (chỉ nói trong lời dẫn).
- Quyền theo dòng cho cảnh báo (row-level permission vẫn là "Next"); "đúng người" là giao việc + lọc theo người được giao.
- Định dạng hoá đơn điện tử đúng chuẩn pháp lý từng nước; plugin gọi sandbox thuế giả lập với trường dữ liệu tiêu biểu.
- GraalVM native cho Tier 1.
- Trình soạn luật trực quan (decision table/DMN); BA sửa DRL dạng chữ trên Desk.
- Không đổi hợp đồng sandbox của `callLong` (trần bộ nhớ, không host import, deadline). Cách demo dùng kết quả video 14 thì đổi: `fee` chỉ thay dòng `base`, `shipping_fee` là tổng `price_breakdown`. Cảnh 12 của `demo-express` chạy theo tổng mới. File mp4 video 14 đã xuất bản không làm lại.

## Phases
| # | Phase | Kết quả chính | File chi tiết |
|---|-------|---------------|---------------|
| 0 | Spike & chuẩn bị | branch, k3d + seed, spike ClassLoader, `claude` + MCP, asciinema | [phase-00](phase-00-spike.md) |
| 1 | Tier 1 host | `IsolatedJvmHost`, `PluginClassLoader` lọc SPI, `MethodHandle` dispatch, bulkhead, capability `PartnerHttp`, kiểm tra rò ClassLoader | [phase-01](phase-01-tier1-host.md) |
| 2 | Phát hành & rollout theo pod | `AppRelease` (submit = duyệt), `AppPin`, `RolloutController` canary theo pod + tự rollback, header `X-Astra-Pod` | [phase-02](phase-02-release-rollout.md) |
| 3 | Plugin mẫu Lớp 2 | FastShip v1.0/v1.1/v1.2-bad; `einvoice-vn`, `einvoice-kh`; tenant thứ ba `angkor-express` (KH); partner sandbox | [phase-03](phase-03-integration-plugins.md) |
| 4 | Định giá xếp tầng | `RuleEngine` Drools (luật là dữ liệu, validator, `standard-tariff.drl` tái tạo bảng cũ); slot `pricing.customer` + plugin Khai giá Shop ABC; MCP `customer_pricing_profile`, `quote_backtest`; `price_breakdown` | [phase-04](phase-04-layered-pricing.md) |
| 5 | Tier 2 + vòng AI cảnh báo | slot `ops.anomaly`, DocType `OpsAlert`, MCP `ops_snapshot`, `anomaly_backtest`, `my_alerts`; dữ liệu seed có bất thường cài sẵn | [phase-05](phase-05-ai-anomaly-loop.md) |
| 6 | Tier 0 + kịch bản demo | readiness/PDB, bench dispatch, `scripts/demo-tiers.sh` cảnh T0/T0b/T1a/T1b/T2a/T2b | [phase-06](phase-06-tier0-demo-script.md) |
| 7 | Quay & dựng video | HyperFrames `docs/video/v22-tiers` + 3 clip, lời tiếng Việt, R2 | [phase-07](phase-07-video.md) |
| 8 | Docs & review | series doc, code snippets, `docs/pricing-rules.md`, bài viết, README, code + security review | [phase-08](phase-08-docs-review.md) |

Thứ tự: 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Phase 3, 4 và 5 đều sửa `demo-seed.sh` (3 còn sửa `demo-reset.sh`; 4 và 5 còn sửa `docs/overlay-wasm.md`), nên ba phase này lần lượt, không song song.

## Acceptance
- `./mvnw -pl astra-server -am test` xanh, có test mới cho: cô lập ClassLoader, hot-swap, rò ClassLoader, bulkhead, từ chối capability,
  chọn bản theo pod, promote/rollback, ghim tenant/khách, plugin theo nước, Drools tái tạo đúng bảng cước cũ, validator chặn DRL nguy hiểm, Khai giá đúng điều khoản v1/v2, plugin kế thừa thay đổi
  của tầng Rule, rào chắn dòng `locked`/`floor`, ABI `callBytes`,
  AI token không submit được release, giao cảnh báo đúng vai trò.
- `make demo-reset && make demo-seed && make demo-tiers PAUSE=0` chạy hết T0, T0b, T1a, T1b, T2a, T2b trên k3d 2 pod, không bước nào giả.
- Khi rolling update và hot-swap, vòng traffic ghi **0 request lỗi**; log traffic hiện pod nào phục vụ và bản plugin nào.
- Bảng so sánh (hard code · Drools · Tier 1 · Wasm) dùng số đo thật từ bench và số luật/dòng code đếm thật.
- Không có `PricingRule` và không có plugin khách: tổng Drools bằng `TARIFF` cũ. Có `PricingRule.active`: module video 14 chỉ thay dòng `base`, các dòng Drools khác còn lại, `shipping_fee` là tổng. Cảnh 12 quay lại theo tổng đó.
- Thẻ "Nói đúng" liệt kê giới hạn: partner/thuế là sandbox, dữ liệu seed và phụ lục hợp đồng demo, cụm 2 pod, lọc cảnh báo theo người được giao.
- Flagship + 3 clip tiếng Việt, 1920×1080, H.264 + AAC, upload R2, thumbnail trong `docs/video-thumbs`.

## Risks
- Rò Metaspace khi hot-swap: plugin chỉ phụ thuộc `astra-spi` + JDK, dữ liệu qua `record` của SPI; test rò bắt buộc.
- Quarkus dev mode có ClassLoader riêng: demo Tier 1 chạy trên image JVM (k3d).
- Canary theo pod với 2 pod: load balancer của k3d phải chia đều để cả hai bản cùng xuất hiện trong log; script gọi thẳng từng pod khi cần.
- Phiên AI không xác định: đề bài cố định, tập 2–3 lần, quay lại thay vì cắt ghép kết quả.
- Homebrew không ghi được trên máy: cài asciinema/agg bằng `cargo install`.
- Drools ghim **10.1.0**: KIE chưa hỗ trợ chính thức Java 25 (khuyến nghị 17/21) nhưng 10.1.0 chạy được ở mức cơ bản; spike ở phase 0 trên đúng
  image k3d; khai báo đủ `drools-engine-classic` + `drools-mvel`; dự phòng là executable model trong ClassLoader riêng.
- Luật là dữ liệu chạy trong JVM lõi: validator là ranh giới an toàn chính, review bảo mật riêng ở phase 8.
- Khối lượng tăng (tenant, hoá đơn, Drools, Khai giá, cảnh báo): ước lượng phase 1–6 khoảng 9–12 ngày công, video 1,5–2 ngày.

## Review (2026-11-10, sau khi hiện thực)
`ak:code-review` và `ak:security` chạy trên nhánh. Báo cáo: `plans/reports/code-review-261110-1500-plugin-tiers.md`, `plans/reports/security-261110-1500-drl-validator.md`.
- **Đã sửa, có test:** lỗ hổng RCE của validator luật (ký tự nháy đơn `'"'` làm lệch bộ xoá chuỗi → chạy Java tuỳ ý trong JVM lõi khi lưu/warm-up); jar Tier 1 **chỉ nạp sau khi duyệt** (lúc lưu chỉ đọc manifest, không chạy code của plugin); canary bị huỷ không để slot trống; canary cũ không quay lại lật canary mới; `release-bot` mất quyền `write` (không tráo artifact sau khi admin xem); bulkhead nhả permit theo tác vụ (không theo người gọi); giá luôn lùi về bảng cước khi tầng luật lỗi; plugin giá không tràn `long` qua sàn; TCCL đặt theo loader plugin; cache Wasm có trần; `EInvoiceJob` cứu đơn kẹt `Issuing`.
- **Giữ lại (giới hạn đã biết, không chặn demo — mỗi slot trong demo phục vụ một tenant):** bulkhead/circuit breaker Tier 1 theo SHA jar nên dùng chung giữa các tenant (H4); sandbox partner trả mã mới mỗi lần nên re-issue vô hại, tích hợp thật cần idempotency key (M2); `CustomerProfileJob` truy vấn mỗi khách (M7); các mục Low. Phase 8 review bảo mật riêng cho validator đã xong ở đây.

## Câu hỏi còn mở
Không còn. Bốn câu của phiên validate 2026-11-10 đã chốt và đã ghi vào các phase; review 2026-11-10 đã sửa các lỗi chặn và ghi giới hạn còn lại ở trên.

## Validation Log

### Session 1 — 2026-11-10
**Trigger:** validate audit review of `plans/261029-0017-plugin-tiers-flagship-video`
**Questions asked:** 4

#### Questions & Answers

1. **[Architecture]** Phase 4 stacks Drools, then an active PricingRule (replace the base line), then the Shop ABC plugin. Today an active PricingRule replaces the whole shipping_fee and returns, which is scene 12 / video 14. The non-goal says not to change that. Which pricing path should the plan keep?
   - Options: Short-circuit stays | Always layer the three | Drools and plugin only
   - **Answer:** Always layer the three
   - **Rationale:** `shipping_fee` becomes the sum of lines. The video 14 module replaces only `base`. Scene 12 is reshot. The sandbox contract of `callLong` stays.

2. **[Flow]** A FastShip booking on Waybill afterSave runs inside the order submit transaction. Where should the carrier call run?
   - Options: Job outside the transaction | Stay inside afterSave
   - **Answer:** Job outside the transaction
   - **Rationale:** `CarrierBookingHook` only marks Pending. `CarrierBookingJob` calls the partner, same shape as `EInvoiceJob`.

3. **[Architecture]** Phase 2 takes the promoter lock and health counters through Counters, and announces stage changes on Redis pub/sub. How should pods stay in sync?
   - Options: Postgres plus poll | Add pub/sub now | Use Counters as written
   - **Answer:** Postgres plus poll
   - **Rationale:** `calls` / `errors` / `p99_ms` live on `AppRelease`. The promoter uses a Postgres advisory lock. Pods re-read stage every 2 s and at boot. No pub/sub. `Counters.markOnce` stays for per-tenant job claims such as one waybill.

4. **[Assumptions]** Apply these four fact corrections to the plan text?
   - Options: Apply all four | Names only | Hold the text
   - **Answer:** Apply all four
   - **Rationale:** Spike calls `list_doctypes`. Anomaly uses `CODRemittance`. Phase 6 only adds `preStop` and `POD_NAME`. `resolve(slot, tenant, customer?)` everywhere. Phases 3 → 4 → 5 because they share `demo-seed.sh`.

#### Confirmed Decisions
- Pricing: Drools, then `PricingRule.active` replaces `base`, then the customer plugin. Scene 12 amounts are not frozen.
- Carrier HTTP: outside the order submit transaction.
- Rollout sync: Postgres poll and a Postgres advisory lock. No Redis pub/sub.
- Fact corrections: `list_doctypes`, `CODRemittance`, phase 6 delta only, customer-aware resolve, serial phases 3–5.

#### Action Items
- [x] Propagate the four answers into `plan.md` and the affected phase files.

#### Impact on Phases
- Phase 0: spike checks `list_doctypes`; k8s note lists only the missing `preStop` and `POD_NAME`.
- Phase 2: resolve signature, Postgres health columns, advisory lock, no pub/sub.
- Phase 3: `CarrierBookingJob` performs the partner call.
- Phase 4: three layers always; `shipping_fee` is the sum.
- Phase 5: `CODRemittance`; resolve takes customer.
- Phase 6: do not restate existing probes; `booked_by` appears after the job.
- `plan.md`: non-goal, phase order, acceptance.

### Whole-Plan Consistency Sweep
- Files reread: plan.md, phase-00-spike.md, phase-01-tier1-host.md, phase-02-release-rollout.md, phase-03-integration-plugins.md, phase-04-layered-pricing.md, phase-05-ai-anomaly-loop.md, phase-06-tier0-demo-script.md, phase-07-video.md, phase-08-docs-review.md, visuals/astra-plugin-tiers-layered-pricing.html
- Decision deltas checked: 6 (list_doctypes, CODRemittance, k8s delta, resolve customer, serial 3→5, pricing layers, carrier job, postgres poll)
- Reconciled stale references: Pub/Sub instruction, `resolve(slot, tenant)` without customer, parallel 3∥4∥5, frozen scene-12 amounts, `doctype_list`, `CodRemittance`, phase 6 re-adding probes
- Unresolved contradictions: 0
- The failure list and the internal-contradiction list above are the pre-propagation findings. The phase text now matches the answers. Published video 14 is not re-rendered; demo scene 12 shows the new sum.

### Verification Results
- Claims checked: 36
- Verified: 29 | Failed: 5 | Unverified: 2
- Tier: Full (9 phases, phases 0–8). Sample is the material contracts, not 15 claims on every phase.

#### Verified
- Make targets `k3d-up`, `demo-reset`, `demo-seed`, `demo-express` exist (`Makefile`). `scripts/demo-reset.sh` already targets k3d when `postgres-0` exists, and its tenant list is only `saigon-express`, `hanoi-express`.
- `scripts/demo-express.sh` scene 12 is Tier 2 Wasm pricing and scene 13 is MCP (`scene12` / `scene13`).
- `astra-plugin-host` currently contains only `WasmSandbox`. `HookRegistry` dispatches CDI `DocLifecycleHook`s at startup. `ExpressApp`, `OrderPostingHook`, `OrderWaybillHook`, `WaybillScanHook`, `ShippingFeeHook`, `PricingRuleHook` exist.
- `ShippingFeeHook.TARIFF` is Same city / Domestic / Remote, Express ×1.5, COD 1% minimum 5,000 (`ShippingFeeHook.java`). An active `PricingRule` replaces the whole `shipping_fee` and returns.
- `ScriptRuntime` exposes only `callLong`. `ScriptException` exists. `PricingRuleHook.beforeSave` stores SHA-256 and self-tests the module.
- Kernel MCP tools are `list_doctypes`, `search_documents`, `get_document`, `create_document`, `ledger_trial_balance` (`McpServer.java`). `McpTool.writes()` defaults to false.
- `Counters` / `RedisCounters.markOnce` is Redis `SET NX` prefixed `astra:<tenant>:<key>`. No Redis pub/sub client in the tree.
- `docs.insert` runs `afterSave` inside the same transaction (`DocumentStore.persistNew`). `OrderWaybillHook.onSubmit` inserts the Waybill inside the order submit transaction.
- DocType name is `CODRemittance` (`cod-remittance.doctype.yaml`). Users `mai`, `quang`, `khoa`, `ha` and tenants `saigon-express`, `hanoi-express` are already in the demo series. `deploy/keycloak/realm/astra-realm.json` exists.
- `deploy/k8s/base/astra-server.yaml` already has readiness `/q/health/ready`, `maxUnavailable: 0`, `maxSurge: 1`, and `PodDisruptionBudget` `minAvailable: 1`. It has no `preStop` and no `POD_NAME`. Image base is `eclipse-temurin:25-jre`.
- `scripts/build-wasm.sh` builds `tet-promo`. `scripts/mcp-claude.sh` defaults to `mai` / `saigon-express`. `scripts/video-voiceover.py` uses VieNeu "Xuân Vĩnh". `docs/video/v14-wasm/`, `docs/overlay-wasm.md`, `docs/demo-video-series.md`, `docs/demo-code-snippets.md`, `SECURITY.md`, and Desk `compare.html` exist. README "Next" still lists the Tier 1 loader and canary across pods.
- `GeneralLedger` and `DocumentService` exist, so withholding them from `PluginContext` is a real boundary.

#### Failures
1. [Fact Checker] Phase 0 tells the spike to call `doctype_list`. That tool does not exist. The kernel tool is `list_doctypes` (`astra-ai/src/main/java/io/astra/ai/McpServer.java`).
2. [Fact Checker] Phase 5 names `CodRemittance`. The DocType is `CODRemittance`.
3. [Fact Checker] Phase 6 says to add readiness, `maxUnavailable: 0`, `maxSurge: 1`, and the PDB. Those four are already in `deploy/k8s/base/astra-server.yaml`. Still missing: `preStop` and downward API `POD_NAME`.
4. [Contract Verifier] Phase 4 applies Drools, then `PricingRule.active` (replace the base line), then the customer plugin. Today `ShippingFeeHook.beforeSave` returns as soon as an active `PricingRule` produces one `shipping_fee`. That short-circuit is what scene 12 / video 14 shows. The new order changes that contract. The non-goal says not to change `PricingRule.active` or scene 12 amounts.
5. [Contract Verifier] Phase 2 counts health and takes the one-pod job lock through `Counters`, and publishes stage changes on Redis pub/sub. `Counters` is tenant-scoped (`RedisCounters`), and the repo has no pub/sub API. A cluster-wide promoter lock cannot be `Counters.markOnce`.

#### Flow finding (not counted as a failed citation)
`CarrierBookingHook` on Waybill `afterSave` runs inside the order submit transaction (`OrderWaybillHook.onSubmit` → `DocumentStore.persistNew`). Catching the partner error keeps the Waybill, and it also holds that transaction for the plugin timeout (300 ms in the manifest). `EInvoiceHook` already avoids this by setting Pending inside the transaction and calling out from a job.

#### Unverified
- Java 25 has no `SecurityManager` (phase 1). Not asserted from this repo.
- No `X-Astra-Pod` header today. The plan adds it; absence was not a dedicated search beyond the `X-Astra-Tenant` filter.

#### Internal contradictions (plan text, not code)
- Phase 2 defines `resolve(slot, tenant, customer?)` on `AppPin`, then the controller section calls `resolve(slot, tenant)` with no customer. Phase 4 needs the customer pin for Shop ABC.
- `plan.md` says phases 3 ∥ 4 ∥ 5, and that only 4 and 5 share `demo-seed.sh` and `docs/overlay-wasm.md`. Phase 3 also edits `demo-seed.sh` and `demo-reset.sh`.
