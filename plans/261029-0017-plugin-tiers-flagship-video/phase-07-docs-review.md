# Phase 7 · Docs & review

## Docs (chỉ phần người dùng thấy thay đổi)
- `docs/demo-video-series.md`: mục Video 22 (kịch bản, cảnh, "Nói đúng"), bảng người dùng thêm `sophea`, `aibot`, tenant `angkor-express`.
  `demo-video-series.en.md` chỉ thêm dòng mô tả và ghi video này có bản tiếng Việt.
- `docs/demo-code-snippets.md`: `FastShipAdapter`, `EInvoiceKh`, `astra-app.yaml`, `RolloutController.resolve`, module Rust do AI viết.
- `docs/overlay-wasm.md`: ABI `check(kind, a, b, c, d)` của slot `ops.anomaly`.
- `docs/articles/22-plugin-tiers.md` theo khuôn các bài 1–21 (bản tiếng Việt; bản tiếng Anh nếu series bài viết đòi đủ hai thứ tiếng).
- `README.md` / `README.vi.md`: thêm Video 22 vào bảng video; "Done" thêm Tier 1 thin slice, canary theo pod, plugin theo nước;
  "Next" bỏ "Tier 1 loader" và "canary rollout across pods", giữ Sigstore/SBOM, quyền theo dòng.
- `SECURITY.md`: ranh giới Tier 1 (ClassLoader lọc SPI, capability `PartnerHttp`, bulkhead), role `release-bot` không duyệt được phát hành.

## Review
- `ak:code-review` trên diff của branch, trọng tâm: rò ClassLoader, đồng bộ trạng thái giữa pod, quyền submit release, che PII trong
  MCP tool mới, gọi ra ngoài nằm ngoài transaction.
- `./mvnw -pl astra-server -am test` xanh; `make demo-tiers` chạy lại sạch.
- Commit theo conventional commits, tách theo phạm vi (spi, plugin-host, kernel rollout, integrations, anomaly + mcp, scripts, video, docs).
