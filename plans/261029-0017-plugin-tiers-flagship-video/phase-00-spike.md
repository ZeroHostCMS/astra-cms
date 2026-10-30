# Phase 0 · Spike & chuẩn bị

## Mục tiêu
Xác nhận nền móng trước khi viết code thật, để không phát hiện chặn đường ở giữa phase 1–4.

## Bước
1. `git switch main && git pull && git switch -c feat/plugin-tiers-demo`.
2. Bật Docker Desktop, `make k3d-up`, `make demo-reset && make demo-seed`, chạy `make demo-express SCENES="12 13" PAUSE=0`
   để chắc Tier 2 và MCP còn xanh (xem memory: k3d ở `~/.local/bin`, giữ volume DB nhỏ).
3. Spike ClassLoader (test JUnit trong `astra-plugin-host`, không nối kernel):
   - nạp một jar nhỏ bằng `PluginClassLoader` có parent lọc (chỉ cho `java.*`, `javax.*` chuẩn JDK, `io.astra.spi.*`);
   - gọi qua `MethodHandle`; đóng loader, bỏ tham chiếu, `System.gc()` và chờ `WeakReference` bị dọn;
   - chạy trên JDK 25 trong image k3d (`eclipse-temurin:25` hay base hiện tại).
4. Kiểm tra `rustup target list --installed` có `wasm32-unknown-unknown`; `scripts/build-wasm.sh` build được `tet-promo`.
5. `scripts/mcp-claude.sh mai saigon-express` rồi hỏi thử Claude Code một câu, xác nhận gọi được tool MCP `list_doctypes` (tên thật trong `astra-ai` là `list_doctypes`, không phải `doctype_list`).
6. Kiểm tra `deploy/k8s/base/astra-server.yaml`: readinessProbe, `maxUnavailable`, PDB **đã có sẵn**; chỉ còn thiếu downward API `POD_NAME` và `preStop` (ghi lại để phase 2 và 5 chỉ thêm hai thứ này).
7. Cài `asciinema` và `agg` bằng `cargo install --locked` (Homebrew không ghi được trên máy này); thử ghi 10 giây rồi chuyển sang mp4 bằng ffmpeg.
8. Kiểm tra bộ nhớ Docker còn đủ cho thêm pod `partner-sandbox` và tenant thứ ba (giữ volume DB nhỏ như memory đã ghi).
9. Chốt vị trí chạy của `afterSave` (trước hay sau commit) bằng một test tối thiểu trong kernel — quyết định transaction boundary cho phase 3 (gọi ra ngoài phải nằm ngoài transaction).
10. Xác nhận `Counters` (`astra-spi/kv/Counters`) có bị gắn theo tenant không; nếu có, chốt cơ chế đếm calls/errors/latency của release ở phạm vi cụm (Redis key ngoài tenant hoặc đếm in-memory per pod).

## Validation
Spike test xanh và in "plugin classloader collected"; demo cảnh 12, 13 chạy; Claude gọi được `list_doctypes`.

## Rollback
Spike nằm trong test, xoá được không ảnh hưởng code khác.
