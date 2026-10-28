# Phase 4 · Tier 2 + vòng tự tiến hoá: AI viết bộ phát hiện bất thường (AI thật qua MCP)

## Vòng 5 bước trên slide → cái chạy thật
| Bước slide | Cái chạy | Ai làm |
| --- | --- | --- |
| 1 · Phát hiện | MCP tool `ops_snapshot`: số liệu 30 ngày về cân lại ở hub, đối soát COD, giao thất bại theo bưu tá | Claude Code |
| 2 · Tự xây | Claude viết crate Rust `astra-apps/express/wasm/anomaly-<tên>/`, build bằng `scripts/build-wasm.sh`, gọi `anomaly_backtest` chạy module trong `WasmSandbox` trên 30 ngày sự kiện thật của tenant, nhận số cảnh báo theo loại và tỉ lệ trúng | Claude Code |
| 3 · Kiểm soát | `release_propose` tạo `AppRelease` nháp (`tier: wasm`, `slot: ops.anomaly`, `generator: claude-code`); Claude thử submit → **403** | Claude Code, rồi `mai` (admin) duyệt trên Desk |
| 4 · Phát hành | Canary theo pod (phase 2): pod 1 chấm sự kiện bằng bản mới, khoẻ thì lên cả cụm | kernel |
| 5 · Đo và tiến hoá | `quang` (OPS), `khoa` (kế toán), `ha` (CSKH) nhận cảnh báo của mình, xác nhận hoặc bác. `release_status` trả số cảnh báo, tỉ lệ bị bác theo loại; Claude đọc và đề xuất v2 (ví dụ bỏ qua lệch cân ở kiện dưới 0,5 kg) | người dùng + Claude Code |

## Ba loại bất thường (đều chấm theo sự kiện, nên canary theo pod áp dụng tự nhiên)
| Sự kiện | Đầu vào cho module | Giao cho |
| --- | --- | --- |
| Quét kiện ở hub (`WaybillScanHook`) | cân khai báo, cân đo lại ở hub (field mới `measured_weight_kg` trên lời quét), mã hub | OPS (`quang`) |
| Lưu `CodRemittance` | COD đã thu, COD nộp về, số ngày trễ | Kế toán (`khoa`) |
| Giao thất bại | số lần thất bại của kiện, của bưu tá trong 7 ngày | CSKH (`ha`) |

## ABI Wasm (giữ nguyên sandbox hiện tại: chỉ `i64`, không host function)
`check(kind: i64, a: i64, b: i64, c: i64, d: i64) -> i64`, `kind` 1/2/3 như bảng trên; trả `0` nếu bình thường, ngược lại
`severity * 100 + reason`. Ghi ABI vào `docs/overlay-wasm.md` (mục mới) để Claude đọc được khi viết module.

## Thay đổi code
- DocType `OpsAlert` (app Express): `kind`, `severity`, `reason`, `ref_doctype`, `ref_name`, `assigned_role`, `assigned_to`,
  `status` (`Open`, `Acknowledged`, `Dismissed`), `raised_by` (`<app>@<version> on <pod>`). Desk có list "Cảnh báo của tôi" lọc theo
  người được giao (lọc ở truy vấn, chưa phải quyền theo dòng; ghi trong thẻ "Nói đúng").
- `AnomalyHook` (Tier 0) trên ba sự kiện: `resolve("ops.anomaly", tenant)` → `WasmSandbox.callLong(...)` → tạo `OpsAlert`.
  Module lỗi hoặc quá deadline không chặn sự kiện, chỉ đếm lỗi cho controller.
- MCP tools (implement `McpTool`): `ops_snapshot` (read), `anomaly_backtest` (read; module base64, tối đa 2.000 sự kiện, mỗi lời gọi
  có deadline; trả số cảnh báo theo loại + so với nhãn seed), `release_propose` (`writes() = true`), `release_status` (read),
  `my_alerts` (read; cảnh báo giao cho người gọi). Kernel lo xác thực, tenant, quyền DocType, che PII.
- Token AI: user Keycloak mới `aibot` (role `release-bot` + đọc OPS/BMS); `scripts/mcp-claude.sh aibot saigon-express`.
- Dữ liệu: `demo-seed.sh` thêm 30 ngày sự kiện cho `saigon-express` với bất thường cài sẵn và có nhãn: ~15 kiện lệch cân > 30 %
  ở một hub, ~6 phiếu COD nộp thiếu, một bưu tá có tỉ lệ giao thất bại cao. Video nói rõ đây là dữ liệu seed.
- `scripts/demo-traffic.sh`: sinh đơn, lời quét, phiếu COD liên tục (tốc độ chỉnh được), có lẫn bất thường, để canary có sự kiện thật.

## Phiên Claude Code (Claude chạy, ghi bằng asciinema, tập trước 2–3 lần)
`scripts/mcp-claude.sh aibot saigon-express`, rồi đề bài cố định:
> "Xem dữ liệu vận hành 30 ngày của Saigon Express qua MCP. Có bất thường nào lặp lại ở cân kiện, COD hay giao hàng không? Viết bộ phát
> hiện bằng Rust theo ABI trong docs/overlay-wasm.md, build ra Wasm, backtest trên dữ liệu thật, rồi đề xuất phát hành. Không tự phát hành."

Sau khi người dùng xử lý cảnh báo, phiên thứ hai: "Đọc kết quả bản vừa phát hành và đề xuất cải tiến."
Lưu `.cast` + transcript vào `docs/video/v22-tiers/assets/` làm bằng chứng.

## Test
Module mẫu chấm đúng 3 loại trên dữ liệu có nhãn; backtest khớp tính tay trên vài sự kiện; `release_propose` ra nháp có
`generator: claude-code`; `aibot` submit → 403; cảnh báo giao đúng role; module quá deadline không chặn lời quét; `my_alerts` chỉ trả cảnh báo của người gọi.
