# Phase 7 · Docs & review

## Docs (chỉ phần người dùng thấy thay đổi)
- `docs/demo-video-series.md`: mục Video 22 (kịch bản, cảnh, "Nói đúng"), bảng người dùng thêm `sophea`, `aibot`, tenant `angkor-express`,
  và một mục **runbook vận hành**: một lệnh `make demo-tiers` + cách xem "bản nào trên pod nào" (Desk `AppRelease`, `X-Astra-Pod`,
  `release_status`). `demo-video-series.en.md` chỉ thêm dòng mô tả và ghi video này có bản tiếng Việt.
- `docs/demo-code-snippets.md`: `FastShipAdapter`, `EInvoiceKh`, `astra-app.yaml`, `RolloutController.resolve`, module Rust do AI viết,
  và `PluginDispatchJob` (job chung ngoài transaction).
- `docs/overlay-wasm.md`: ABI `check(kind, a, b, c, d)` của slot `ops.anomaly` (kèm phiên bản ABI + module mẫu).
- Ghi chú kỹ thuật trong docs: `scripts/build-wasm.sh` đã tổng quát hoá (build workspace, copy mọi `.wasm`), và
  `astra-apps/express-integrations` là module Maven mới trong `pom.xml` gốc.
- `docs/articles/22-plugin-tiers.md` theo khuôn các bài 1–21 (bản tiếng Việt; bản tiếng Anh nếu series bài viết đòi đủ hai thứ tiếng).
- `README.md` / `README.vi.md`: thêm Video 22 vào bảng video; "Done" thêm Tier 1 thin slice, canary theo pod, plugin theo nước;
  "Next" bỏ "Tier 1 loader" và "canary rollout across pods", giữ Sigstore/SBOM, quyền theo dòng. **Cập nhật luôn các ví dụ canary
  theo % còn sót** (`rollout: { steps: [10%, 50%, 100%] }` và dòng "canary by pod percentage" → đổi thành canary theo pod) để README
  không tự mâu thuẫn.
- `SECURITY.md`: ranh giới Tier 1 (ClassLoader lọc SPI, capability `PartnerHttp`, bulkhead), role `release-bot` không duyệt được phát hành,
  và ghi rõ capability mạng Tier 1 là *quy ước* (plugin tin cậy, không phải sandbox; mã không tin cậy thuộc Tier 2).

## Review
- `ak:code-review` trên diff của branch, trọng tâm: rò ClassLoader, đồng bộ trạng thái giữa pod, quyền submit release, che PII trong
  MCP tool mới, gọi ra ngoài nằm ngoài transaction.
- `./mvnw -pl astra-server -am test` xanh; `make demo-tiers` chạy lại sạch.
- Commit theo conventional commits, tách theo phạm vi (spi, plugin-host, kernel rollout, integrations, anomaly + mcp, scripts, video, docs).
