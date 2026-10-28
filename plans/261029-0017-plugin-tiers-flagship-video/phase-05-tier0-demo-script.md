# Phase 5 · Tier 0 và kịch bản demo `demo-tiers.sh`

## Tier 0 (Lớp 1 · Lõi)
- `deploy/k8s/base/astra-server.yaml`: readinessProbe (`/q/health/ready`), `strategy.rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }`,
  `PodDisruptionBudget minAvailable: 1`, `preStop` ngắn để drain. Không có thì rolling update có thể rớt request.
- Bench thật cho bảng so sánh: test `TierDispatchBench` trong `astra-plugin-host` (vòng đo có warm-up) đo gọi trực tiếp (Tier 0),
  `MethodHandle` qua bulkhead (Tier 1), Wasm ấm (Tier 2), in p50/p99; số trong video lấy từ đây.

## `scripts/demo-tiers.sh` (cùng khuôn `demo-express.sh`, dùng lại `scripts/lib/demo-common.sh`)
`scripts/demo-traffic.sh` chạy nền trong T0 và T1, in mỗi dòng: thời điểm, pod (`X-Astra-Pod`), kết quả, bản plugin; cuối in `ok=… fail=…`.

| Cảnh | Nội dung | Bằng chứng trên màn hình |
| --- | --- | --- |
| **T0** | Chiếu `ExpressApp`, `OrderPostingHook` (biên dịch vào image); submit đơn → bút toán sổ cái; bật traffic, `kubectl rollout restart deploy/astra-server` | `kubectl get pods -w` thay 2 pod; traffic `fail=0` |
| **T1a** · hãng vận chuyển | `mai` upload `fastship-1.0.0` → duyệt → Live; upload 1.1.0 → canary trên 1 pod: log traffic thấy pod A `fastship@1.1.0`, pod B `fastship@1.0.0` → lên 2/2 → Live; `hanoi-express` ghim 1.0.0; upload 1.2.0-bad → lỗi trên pod canary vượt ngân sách → `Rolled back` trong vài giây; log "PartnerHttp denied https://example.com"; log "classloader fastship@1.0.0 collected" | JSON từ API, log kernel, `/desk/compare.html`, traffic `fail=0` |
| **T1b** · hoá đơn theo nước | saigon-express submit đơn → hoá đơn `einvoice-vn`; `angkor-express` (`sophea`) submit đơn → `Pending (no provider)`; upload + duyệt `einvoice-kh`, ghim cho angkor → đơn chờ có hoá đơn KH; hai tenant hai mô-đun thuế chạy cùng lúc | JSON, Desk hai tenant |
| **T2** | Script không chạy AI (phiên AI quay riêng ở phase 6); script phát lại phần xác minh sau phiên AI: release nháp `generator: claude-code`, `aibot` submit → 403, `mai` duyệt, canary theo pod, traffic sinh sự kiện, `quang`/`khoa`/`ha` mỗi người thấy cảnh báo của mình, bác một cảnh báo sai, `release_status` | JSON, Desk "Cảnh báo của tôi" |

`make demo-tiers SCENES="T0 T1a T1b T2" PAUSE=0`; mỗi cảnh dọn về trạng thái đầu để quay lại được.

## Validation
Chạy liền hai lần sau `make demo-reset && make demo-seed`, kết quả giống nhau (trừ mã chứng từ, tên pod và độ trễ).
