# Phase 2 · Phát hành & rollout theo pod (dùng chung Tier 1 và Tier 2)

## Ý tưởng
Một đường phát hành duy nhất cho mọi thứ thay được khi đang chạy (plugin Tier 1, module Wasm Tier 2). Đây là phần
"Kiểm soát → Phát hành → Đo" của Lớp 3 và "Nâng cấp khi đang chạy · canary · tự rollback" của hàng "Không downtime" trên slide.
Canary đi **theo pod**: bản mới lên một pod trước, khoẻ thì lên cả cụm.

## DocType mới (kernel, dùng được cho mọi app)
`AppRelease` — `submittable: true`, `track-changes: true`
| Field | Ghi chú |
| --- | --- |
| `app_id`, `version`, `tier` (`isolated-jvm` \| `wasm`), `slot` | slot là điểm gắn: `carrier.booking`, `einvoice.issue`, `ops.anomaly` |
| `artifact` (Attach), `artifact_sha256`, `artifact_bytes` | hook tính SHA-256, nạp thử vào host/sandbox khi lưu (như `PricingRuleHook`) |
| `generator` (`human` \| `claude-code`), `signed_by`, `notes` | provenance, hiện trên Desk |
| `stage` (`Draft`, `Canary`, `Live`, `Rolled back`, `Retired`) | read-only, do controller ghi |
| `canary_pods`, `target_pods` | ví dụ `astra-server-7d9-abc` và `1/2` → `2/2` |
| `calls`, `errors`, `p99_ms`, `error_budget` | sức khoẻ trên các pod đang chạy bản này |

Quyền: chỉ `admin` được **submit** (= duyệt phát hành). Role mới `release-bot` cho token AI: create/read, không submit.
Submit chuyển stage sang `Canary` trên 1 pod; cancel = rút bản.

`AppPin` — mỗi tenant ghim `slot → app_id@version` (tách theo tenant nhờ `tenant_id` + RLS sẵn có). Dùng cho: hanoi-express ghim
FastShip 1.0, và mỗi tenant ghim plugin hoá đơn của nước mình.

## `RolloutController` (kernel)
- Danh tính pod: biến `POD_NAME` qua downward API trong `deploy/k8s/base/astra-server.yaml`; dev mode dùng hostname.
- `resolve(slot, tenant)` → bản dùng cho lời gọi: ghim của tenant thắng; nếu không, pod hiện tại nằm trong `canary_pods` thì dùng
  bản canary, ngược lại dùng bản `Live` trước đó.
- Mọi response có header `X-Astra-Pod`; chứng từ do plugin xử lý ghi `…_by = app@version on <pod>` để log demo thấy rõ.
- Đếm `calls/errors/latency` theo release và theo pod (Redis, `Counters` SPI).
- Job định kỳ (2 s trong demo), chỉ một pod chạy nhờ khoá Redis `SET NX`:
  - đủ `N` lời gọi khoẻ trên pod canary → thêm pod tiếp theo; đủ mọi pod → `Live`, bản cũ `Retired`, các pod drain + unload;
  - tỉ lệ lỗi vượt `error_budget` → `Rolled back`, xoá `canary_pods`, pod canary quay về bản trước ngay.
- Đồng bộ: trạng thái trong Postgres; mỗi lần đổi stage phát Redis Pub/Sub; pod nạp artifact lười theo SHA-256. Pod mới
  khởi động (HPA, rolling update) đọc trạng thái hiện tại lúc boot, không cần ai báo.
- Audit: ai submit, lúc nào, pod nào lên bản gì, bản nào bị rollback và vì sao (log có cấu trúc + lịch sử version của `AppRelease`).

## Desk
List `AppRelease` hiện stage, pod, generator, calls/errors; badge màu cho stage (CSS nhỏ). Nút Submit có sẵn từ lifecycle.

## Test
Pod trong `canary_pods` chọn bản mới, pod ngoài chọn bản cũ; ghim tenant thắng canary; promote 1/2 → 2/2 sau N; rollback khi vượt
ngân sách; token `release-bot` submit → 403; pod thứ hai thấy stage mới sau Pub/Sub; pod khởi động sau đọc đúng trạng thái.

## Rollback
Kernel chỉ hỏi controller ở các slot mới; slot không có release thì đi đường cũ.
