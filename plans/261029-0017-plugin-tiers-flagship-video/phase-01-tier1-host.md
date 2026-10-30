# Phase 1 · Tier 1 isolated-JVM host

## Bối cảnh
README mô tả Tier 1 (ClassLoader riêng mỗi phiên bản app, `MethodHandle` dispatch, hot-swap, bulkhead) nhưng ghi là "Next".
Hiện `astra-plugin-host` chỉ có `WasmSandbox` (Tier 2). Tier 0 là các bean CDI được `HookRegistry` gom lúc khởi động.

## SPI mới (`astra-spi`)
- `io.astra.spi.integration.CarrierAdapter` — extension point Lớp 2 đầu tiên:
  `Booking book(Shipment s)`; `Shipment`/`Booking` là `record` thuần (không Jackson) để không rò class qua ranh giới.
- `io.astra.spi.integration.PartnerHttp` — cổng HTTP do host cấp; chỉ gọi được host nằm trong `capabilities.network`
  của manifest, ngoài danh sách thì ném `CapabilityDeniedException` (Java 25 không còn SecurityManager, nên quyền mạng
  là "capability qua đối tượng được cấp" đúng như README mô tả).
- `io.astra.spi.app.PluginContext` — những gì plugin nhận khi khởi tạo: `PartnerHttp`, cấu hình tenant, logger.

## Host (`astra-plugin-host`)
| Class | Việc |
| --- | --- |
| `PluginManifest` | Đọc `META-INF/astra-app.yaml` trong jar: `id`, `version`, `tier: isolated-jvm`, `entrypoint`, `spi`, `capabilities.network`, `runtime.timeout`, `runtime.max-concurrency` |
| `PluginClassLoader` | Child-first cho class của plugin; parent là loader lọc chỉ lộ JDK + `io.astra.spi.*`. Plugin không thấy kernel, Quarkus, Jackson |
| `IsolatedJvmHost` | `load(bytes) → LoadedPlugin` (khoá theo SHA-256), giữ nhiều phiên bản song song, `unload` sau khi drain, theo dõi `WeakReference` để báo "đã thu hồi"/"còn rò" |
| `LoadedPlugin` | Tạo entrypoint, bind `MethodHandle` một lần; gọi qua bulkhead: deadline trên virtual thread, `Semaphore` theo `max-concurrency`, circuit breaker đơn giản (tỉ lệ lỗi trong cửa sổ) |
| `ScopedPluginIdentity` | `ScopedValue` mang id@version của plugin đang chạy, để `PartnerHttp` và audit biết ai gọi |

## Test
- Plugin không nạp được `io.astra.kernel.*` (`ClassNotFoundException`).
- Hai phiên bản cùng id chạy song song, trả kết quả khác nhau.
- Hot-swap: trong lúc 200 lời gọi song song, đổi v1 → v2, không lời gọi nào lỗi.
- Sau unload + GC, `WeakReference` của loader v1 về `null`.
- Plugin chạy quá deadline bị dừng; vượt `max-concurrency` bị từ chối nhanh; gọi host ngoài danh sách bị từ chối.

## Ghi chú thiết kế
- Đóng băng SPI ngay từ đầu: `CarrierAdapter`, `PartnerHttp`, `PluginContext` và các `record` là hợp đồng công khai; mọi thay đổi
  phải tương thích ngược (thêm interface/field mới, không sửa cái cũ).
- `IsolatedJvmHost` là "lát mỏng": API nhỏ, không nhét logic rollout vào đây. Quyết định "chạy bản nào" thuộc về
  `RolloutController.resolve()` (phase 2), host chỉ nạp/gọi/unload. Seam này dùng chung cho Tier 1 và Tier 2.

## Rủi ro
Thread-local hoặc static cache trong JDK giữ class plugin → test rò sẽ bắt; plugin mẫu tránh `java.util.logging` tự đăng ký handler.

## Rollback
Module mới, chưa nối vào đường request cho tới phase 2.
