# Phase 2 · Phát hành & rollout theo pod (dùng chung Tier 1 và Tier 2)

## Ý tưởng
Một đường phát hành duy nhất cho mọi thứ thay được khi đang chạy (plugin Tier 1, module Wasm Tier 2). Đây là phần
"Kiểm soát → Phát hành → Đo" của Lớp 3 và "Nâng cấp khi đang chạy · canary · tự rollback" của hàng "Không downtime" trên slide.
Canary đi **theo pod**: bản mới lên một pod trước, khoẻ thì lên cả cụm.

## DocType mới (kernel, dùng được cho mọi app)
`AppRelease` — `submittable: true`, `track-changes: true`
| Field | Ghi chú |
| --- | --- |
| `app_id`, `version`, `tier` (`isolated-jvm` \| `wasm` \| `rules`), `slot` | slot là điểm gắn: `carrier.booking`, `einvoice.issue`, `pricing.base` (Drools), `pricing.customer` (Khai giá), `ops.anomaly` |
| `artifact` (Attach), `artifact_sha256`, `artifact_bytes` | hook tính SHA-256, nạp thử vào host/sandbox khi lưu (như `PricingRuleHook`) |
| `generator` (`human` \| `claude-code`), `signed_by`, `notes` | provenance, hiện trên Desk |
| `stage` (`Draft`, `Canary`, `Live`, `Rolled back`, `Retired`) | read-only, do controller ghi |
| `canary_pods`, `target_pods` | ví dụ `astra-server-7d9-abc` và `1/2` → `2/2` |
| `calls`, `errors`, `p99_ms`, `error_budget` | sức khoẻ trên các pod đang chạy bản này |

Quyền: chỉ `admin` được **submit** (= duyệt phát hành). Role mới `release-bot` cho token AI: create/read, không submit.
Submit chuyển stage sang `Canary` trên 1 pod; cancel = rút bản.

`AppPin` — ghim `slot → app_id@version` theo tenant (tách nhờ `tenant_id` + RLS sẵn có), tuỳ chọn thu hẹp theo **khách**
(`customer`). Dùng cho: hanoi-express ghim FastShip 1.0, mỗi tenant ghim plugin hoá đơn của nước mình, Shop ABC ghim plugin Khai giá riêng.
`resolve(slot, tenant, customer?)`: ghim theo khách > ghim theo tenant > canary/Live.

## MCP tools chung của kernel
`release_propose` (`writes() = true`: tạo `AppRelease` nháp với `generator` lấy từ token, nạp thử artifact) và `release_status`
(read: stage, pod, calls, errors, p99). Dùng cho cả Khai giá (phase 4) và cảnh báo bất thường (phase 5).

## `RolloutController` (kernel)
- Danh tính pod: biến `POD_NAME` qua downward API trong `deploy/k8s/base/astra-server.yaml`; dev mode dùng hostname.
- `resolve(slot, tenant, customer?)` → bản dùng cho lời gọi: ghim theo khách thắng ghim theo tenant; nếu không có ghim, pod hiện tại nằm trong `canary_pods` thì dùng
  bản canary, ngược lại dùng bản `Live` trước đó.
- Mọi response có header `X-Astra-Pod`; chứng từ do plugin xử lý ghi `…_by = app@version on <pod>` để log demo thấy rõ.
- `calls`, `errors`, `p99_ms` nằm trên dòng `AppRelease` trong Postgres (nguồn sự thật). Pod cộng dồn vào dòng đó. Không dùng `Counters` cho rollout: SPI này luôn gắn prefix tenant.
- Job định kỳ (2 s trong demo), chỉ một pod chạy nhờ **advisory lock Postgres** (khoá cụm, không phải `Counters.markOnce`):
  - đủ `N` lời gọi khoẻ trên pod canary → thêm pod tiếp theo; đủ mọi pod → `Live`, bản cũ `Retired`, các pod drain + unload;
  - tỉ lệ lỗi vượt `error_budget` → `Rolled back`, xoá `canary_pods`, pod canary quay về bản trước ngay.
- Đồng bộ: không Redis pub/sub. Mỗi pod đọc lại stage từ Postgres ở job 2 s và lúc boot, nạp artifact lười theo SHA-256.
<!-- Updated: Validation Session 1 - postgres poll, advisory lock, resolve includes customer -->
- Audit: ai submit, lúc nào, pod nào lên bản gì, bản nào bị rollback và vì sao (log có cấu trúc + lịch sử version của `AppRelease`).

## Desk
List `AppRelease` hiện stage, pod, generator, calls/errors; badge màu cho stage (CSS nhỏ). Nút Submit có sẵn từ lifecycle.

## Test
Pod trong `canary_pods` chọn bản mới, pod ngoài chọn bản cũ; ghim tenant thắng canary; promote 1/2 → 2/2 sau N; rollback khi vượt
ngân sách; token `release-bot` submit → 403; pod thứ hai thấy stage mới ở vòng đọc Postgres kế tiếp (không pub/sub); ghim theo khách thắng ghim tenant và canary; pod khởi động sau đọc đúng trạng thái.

## Rollback
Kernel chỉ hỏi controller ở các slot mới; slot không có release thì đi đường cũ.
