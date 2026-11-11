# Phase 8 · Docs & review

## Docs (chỉ phần người dùng thấy thay đổi)
- `docs/demo-video-series.md`: mục Video 22 (kịch bản, cảnh, "Nói đúng"), bảng người dùng thêm `sophea`, `aibot`, tenant `angkor-express`,
  khách Shop ABC. `demo-video-series.en.md` chỉ thêm dòng mô tả và ghi video này có bản tiếng Việt.
- `docs/demo-code-snippets.md`: `standard-tariff.drl` + luật `peak-1111`, `FastShipAdapter`, `EInvoiceKh`, `astra-app.yaml`,
  `RolloutController.resolve`, module Rust Khai giá và module cảnh báo do AI viết.
- `docs/overlay-wasm.md`: ABI bộ nhớ `alloc`/`adjust` + PDK `astra-pricing-pdk` (thao tác kế thừa scale/set/remove/add, dòng `locked`, `floor`) của slot `pricing.customer` và `check(kind, a, b, c, d)` của slot `ops.anomaly`.
- `docs/pricing-rules.md` (mới): fact model `QuoteRequest`/`QuoteLines`, cách viết luật, danh sách cấm của validator, thứ tự ưu tiên
  Drools → `PricingRule` cũ → plugin của khách kế thừa, cách đánh dấu dòng `locked` và `floor` trong luật, khi nào dùng luật và khi nào dùng Wasm.
- `docs/demo-data/shop-abc-contract-v1.md`, `-v2.md`: phụ lục hợp đồng demo AI đọc.
- `docs/articles/22-plugin-tiers.md` theo khuôn các bài 1–21 (bản tiếng Việt; bản tiếng Anh nếu series bài viết đòi đủ hai thứ tiếng).
- `README.md` / `README.vi.md`: thêm Video 22 vào bảng video; "Done" thêm Tier 1 thin slice, canary theo pod, plugin theo nước,
  rule engine Drools; "Next" bỏ "Tier 1 loader" và "canary rollout across pods", giữ Sigstore/SBOM, quyền theo dòng.
- `SECURITY.md`: ranh giới Tier 1 (ClassLoader lọc SPI, capability `PartnerHttp`, bulkhead), validator luật Drools, role `release-bot`
  không duyệt được phát hành.

## Review
- `ak:code-review` trên diff của branch, trọng tâm: rò ClassLoader và `KieBase`, đồng bộ trạng thái giữa pod, quyền submit release,
  che PII trong MCP tool mới, gọi ra ngoài nằm ngoài transaction.
- `ak:security` riêng cho validator DRL: luật là dữ liệu chạy trong JVM lõi, thử các cách vượt danh sách cấm (FQN, `eval`, function, static import).
- `./mvnw -pl astra-server -am test` xanh; `make demo-tiers` chạy lại sạch.
- Commit theo conventional commits, tách theo phạm vi (spi, plugin-host, kernel rollout, rules engine, integrations, pricing, anomaly + mcp,
  scripts, video, docs).
