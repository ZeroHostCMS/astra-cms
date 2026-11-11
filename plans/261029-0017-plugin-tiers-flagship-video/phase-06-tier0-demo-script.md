# Phase 6 · Tier 0 và kịch bản demo `demo-tiers.sh`

## Tier 0 (Lớp 1 · Lõi)
- `deploy/k8s/base/astra-server.yaml` đã có readinessProbe (`/q/health/ready`), `maxUnavailable: 0`, `maxSurge: 1` và `PodDisruptionBudget minAvailable: 1`. Phase này chỉ thêm `preStop` ngắn để drain và downward API `POD_NAME`. Không viết lại ba mục đã có.
<!-- Updated: Validation Session 1 - only preStop and POD_NAME are new -->
- Bench thật cho bảng so sánh: test `TierDispatchBench` trong `astra-plugin-host` (vòng đo có warm-up) đo gọi trực tiếp (Tier 0),
  `MethodHandle` qua bulkhead (Tier 1), Wasm ấm (Tier 2), in p50/p99; số trong video lấy từ đây.

## `scripts/demo-tiers.sh` (cùng khuôn `demo-express.sh`, dùng lại `scripts/lib/demo-common.sh`)
`scripts/demo-traffic.sh` chạy nền trong T0, T0b, T1 và T2a, in mỗi dòng: thời điểm, pod (`X-Astra-Pod`), kết quả, bản plugin; cuối in `ok=… fail=…`.

| Cảnh | Nội dung | Bằng chứng trên màn hình |
| --- | --- | --- |
| **T0** | Chiếu `ExpressApp`, `OrderPostingHook` (biên dịch vào image); submit đơn → bút toán sổ cái; bật traffic, `kubectl rollout restart deploy/astra-server` | `kubectl get pods -w` thay 2 pod; traffic `fail=0` |
| **T0b** · Drools | Chiếu bảng `TARIFF` hard code cũ (git diff) cạnh `standard-tariff.drl`; `mai` (vai BA) thêm luật `peak-1111` trên Desk → duyệt → đơn Remote mới có dòng `rule:peak-1111`, không pod nào khởi động lại; lưu một luật sai cú pháp và một luật gọi `System.exit` → bị từ chối kèm lý do | JSON `price_breakdown`, Desk ruleset, traffic `fail=0` |
| **T1a** · hãng vận chuyển | `mai` upload `fastship-1.0.0` → duyệt → Live; upload 1.1.0 → canary trên 1 pod: log traffic thấy pod A `fastship@1.1.0`, pod B `fastship@1.0.0` → lên 2/2 → Live; `hanoi-express` ghim 1.0.0; upload 1.2.0-bad → lỗi trên pod canary vượt ngân sách → `Rolled back` trong vài giây; log "PartnerHttp denied https://example.com"; log "classloader fastship@1.0.0 collected". `booked_by` xuất hiện sau `CarrierBookingJob`, không nằm trong response của submit | JSON từ API, log kernel, `/desk/compare.html`, traffic `fail=0` |
| **T1b** · hoá đơn theo nước | saigon-express submit đơn → hoá đơn `einvoice-vn`; `angkor-express` (`sophea`) submit đơn → `Pending (no provider)`; upload + duyệt `einvoice-kh`, ghim cho angkor → đơn chờ có hoá đơn KH; hai tenant hai mô-đun thuế chạy cùng lúc | JSON, Desk hai tenant |
| **T2a** · Khai giá | Phát lại phần xác minh sau phiên AI Khai giá: release `khaigia-shopabc@1.0.0` (`generator: claude-code`) ghim cho Shop ABC, `aibot` submit → 403, `mai` duyệt; đơn của Shop ABC có `price_breakdown` gồm dòng gốc Rule + thao tác plugin, đơn của khách khác chỉ có dòng Rule; bật luật `peak-1111` → đơn Shop ABC tự có dòng 11.11, plugin không đổi; v1.1.0 (phụ lục v2) duyệt → hot-reload khi traffic chạy, đơn kế tiếp theo giá mới, `fail=0` | JSON, Desk đơn |
| **T2b** · cảnh báo | Script không chạy AI (phiên AI quay riêng ở phase 7); script phát lại phần xác minh sau phiên AI: release nháp `generator: claude-code`, `aibot` submit → 403, `mai` duyệt, canary theo pod, traffic sinh sự kiện, `quang`/`khoa`/`ha` mỗi người thấy cảnh báo của mình, bác một cảnh báo sai, `release_status` | JSON, Desk "Cảnh báo của tôi" |

`make demo-tiers SCENES="T0 T0b T1a T1b T2a T2b" PAUSE=0`; mỗi cảnh dọn về trạng thái đầu để quay lại được.

## Validation
Chạy liền hai lần sau `make demo-reset && make demo-seed`, kết quả giống nhau (trừ mã chứng từ, tên pod và độ trễ).
