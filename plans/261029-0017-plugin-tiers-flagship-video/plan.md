# Video flagship "Phần mềm tự tiến hoá": Plugin Tier 0 · 1 · 2

Status: plan (sửa lần 1) · Branch đề xuất: `feat/plugin-tiers-demo` cắt từ `main`
Nguồn ý tưởng: slide `Global_Future.pdf` trang 1 (ba lớp: Lõi · Tích hợp thị trường · Tự tiến hoá)
Dựa trên: [Platform feature demos](../261021-1723-astra-platform-feature-demos/plan.md), quy trình video v01–v21 trong `docs/video/`

## Outcome
Một video flagship ~5 phút và 3 clip ngắn (một clip mỗi tier) minh hoạ ba lớp của slide bằng **code chạy thật** trên cụm k3d,
câu chuyện Astra Express trung tính (không logo Viettel), lời đọc tiếng Việt, upload R2:

| Lớp trên slide | Tier | Điều video chứng minh |
| --- | --- | --- |
| Lớp 1 · Lõi Astra | **Tier 0** (build-time, gọi trực tiếp) | Lõi biên dịch vào image; chỉ Tier 0 ghi sổ cái; đổi lõi = image mới + rolling update, traffic không lỗi |
| Lớp 2 · Tích hợp thị trường | **Tier 1** (isolated JVM, hot-swap) | **Hãng vận chuyển FastShip**: v1.0 → v1.1 thay khi đang chạy, **canary theo pod** (1 pod → cả cụm), bản lỗi tự rollback, plugin không gọi được host ngoài quyền. **Hoá đơn điện tử theo nước**: tenant Việt Nam dùng `einvoice-vn`, tenant Campuchia mới dùng `einvoice-kh`, "thêm nước = thêm mô-đun", không khởi động lại |
| Lớp 3 · Tự tiến hoá có phê duyệt | **Tier 2** (Wasm sandbox) + AI qua MCP | Claude Code đọc dữ liệu vận hành qua MCP, viết **bộ phát hiện bất thường** bằng Rust → Wasm, backtest trên 30 ngày dữ liệu, nộp bản nháp; AI không tự phát hành được; quản lý duyệt; canary theo pod; cảnh báo đến **đúng người** (OPS, kế toán, CSKH); người dùng bác cảnh báo sai → AI đọc phản hồi, đề xuất bản v2 |

## Quyết định (user, 2026-10-29)
- Tier 1: **xây lát mỏng thật** (loader, hot-swap, ghim theo tenant, canary + tự rollback).
- Canary: **theo pod**, không chia % request. Cụm demo 2 pod nên các bước là 1/2 pod → 2/2 pod; cấu hình 10→50→100 % của slide là
  cho cụm production nhiều pod, video nói rõ.
- Plugin Lớp 2: **hãng vận chuyển** + **hoá đơn điện tử theo nước**.
- Lớp 3: **AI thật qua MCP + duyệt**, bài toán **cảnh báo bất thường**.
- Đầu ra: **1 video flagship + 3 clip**, **chỉ tiếng Việt**, **upload R2** như các video trước.
- Thương hiệu: **Astra Express trung tính**; sơ đồ ba lớp vẽ lại theo phong cách Astra.
- Phiên Claude Code: **Claude chạy và ghi bằng asciinema**, dựng thành mp4, giữ transcript làm bằng chứng.

## Non-goals
- Chữ ký Sigstore/SBOM, OCI registry (chỉ ghim SHA-256 + ghi `signed_by`/`generator`).
- Canary theo % request (đã chọn theo pod).
- OTA app mobile, "nới quyền AI theo mức tin cậy" (chỉ nói trong lời dẫn).
- Quyền theo dòng cho cảnh báo (row-level permission vẫn là "Next"); "đúng người" là giao việc + lọc theo người được giao.
- Định dạng hoá đơn điện tử đúng chuẩn pháp lý từng nước; plugin gọi sandbox thuế giả lập với trường dữ liệu tiêu biểu.
- GraalVM native cho Tier 1.
- Không phá video 14: `PricingRule.active` và cảnh 12 giữ nguyên.

## Phases
| # | Phase | Kết quả chính | File chi tiết |
|---|-------|---------------|---------------|
| 0 | Spike & chuẩn bị | branch, k3d + seed, spike ClassLoader, `claude` + MCP, asciinema | [phase-00](phase-00-spike.md) |
| 1 | Tier 1 host | `IsolatedJvmHost`, `PluginClassLoader` lọc SPI, `MethodHandle` dispatch, bulkhead, capability `PartnerHttp`, kiểm tra rò ClassLoader | [phase-01](phase-01-tier1-host.md) |
| 2 | Phát hành & rollout theo pod | `AppRelease` (submit = duyệt), `AppPin`, `RolloutController` canary theo pod + tự rollback, header `X-Astra-Pod` | [phase-02](phase-02-release-rollout.md) |
| 3 | Plugin mẫu Lớp 2 | FastShip v1.0/v1.1/v1.2-bad; `einvoice-vn`, `einvoice-kh`; tenant thứ ba `angkor-express` (KH); partner sandbox | [phase-03](phase-03-integration-plugins.md) |
| 4 | Tier 2 + vòng AI | slot `ops.anomaly`, DocType `OpsAlert`, MCP tools `ops_snapshot`, `anomaly_backtest`, `release_propose`, `release_status`, `my_alerts`; dữ liệu seed có bất thường cài sẵn | [phase-04](phase-04-ai-anomaly-loop.md) |
| 5 | Tier 0 + kịch bản demo | readiness/PDB, bench dispatch 3 tier, `scripts/demo-tiers.sh` cảnh T0/T1/T2 | [phase-05](phase-05-tier0-demo-script.md) |
| 6 | Quay & dựng video | HyperFrames `docs/video/v22-tiers` + 3 clip, lời tiếng Việt, R2 | [phase-06](phase-06-video.md) |
| 7 | Docs & review | series doc, code snippets, bài viết, README, code review | [phase-07](phase-07-docs-review.md) |

Thứ tự: 0 → 1 → 2 → (3 ∥ 4) → 5 → 6 → 7.

## Acceptance
- `./mvnw -pl astra-server -am test` xanh, có test mới cho: cô lập ClassLoader, hot-swap, rò ClassLoader, bulkhead, từ chối capability,
  chọn bản theo pod, promote/rollback, ghim tenant, plugin theo nước, AI token không submit được release, giao cảnh báo đúng vai trò.
- `make demo-reset && make demo-seed && make demo-tiers PAUSE=0` chạy hết T0, T1, T2 trên k3d 2 pod, không bước nào giả.
- Khi rolling update và hot-swap, vòng traffic ghi **0 request lỗi**; log traffic hiện pod nào phục vụ và bản plugin nào.
- Bảng so sánh 3 tier dùng số đo thật từ bench.
- Thẻ "Nói đúng" liệt kê giới hạn: partner/thuế là sandbox, dữ liệu seed, cụm 2 pod, lọc cảnh báo theo người được giao.
- Flagship + 3 clip tiếng Việt, 1920×1080, H.264 + AAC, upload R2, thumbnail trong `docs/video-thumbs`.

## Risks
- Rò Metaspace khi hot-swap: plugin chỉ phụ thuộc `astra-spi` + JDK, dữ liệu qua `record` của SPI; test rò bắt buộc.
- Quarkus dev mode có ClassLoader riêng: demo Tier 1 chạy trên image JVM (k3d).
- Canary theo pod với 2 pod: load balancer của k3d phải chia đều để cả hai bản cùng xuất hiện trong log; script gọi thẳng từng pod khi cần.
- Phiên AI không xác định: đề bài cố định, tập 2–3 lần, quay lại thay vì cắt ghép kết quả.
- Homebrew không ghi được trên máy: cài asciinema/agg bằng `cargo install`.
- Khối lượng tăng (thêm tenant, plugin hoá đơn, pipeline cảnh báo): ước lượng phase 1–5 khoảng 6–8 ngày công, video 1–2 ngày.

## Câu hỏi còn mở
Không còn; mọi quyết định ở trên đã chốt ngày 2026-10-29.
