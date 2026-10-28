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
5. `scripts/mcp-claude.sh mai saigon-express` rồi hỏi thử Claude Code một câu, xác nhận gọi được tool MCP.
6. Kiểm tra `deploy/k8s/base/astra-server.yaml`: readinessProbe, `maxUnavailable`, PDB, downward API `POD_NAME` (ghi lại thiếu gì cho phase 2 và 5).
7. Cài `asciinema` và `agg` bằng `cargo install --locked` (Homebrew không ghi được trên máy này); thử ghi 10 giây rồi chuyển sang mp4 bằng ffmpeg.
8. Kiểm tra bộ nhớ Docker còn đủ cho thêm pod `partner-sandbox` và tenant thứ ba (giữ volume DB nhỏ như memory đã ghi).

## Validation
Spike test xanh và in "plugin classloader collected"; demo cảnh 12, 13 chạy; Claude gọi được `doctype_list`.

## Rollback
Spike nằm trong test, xoá được không ảnh hưởng code khác.
