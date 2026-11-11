# Phase 0 · Spike & chuẩn bị

## Mục tiêu
Xác nhận nền móng trước khi viết code thật, để không phát hiện chặn đường ở giữa phase 1–5.

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
6. Kiểm tra `deploy/k8s/base/astra-server.yaml`. Readiness `/q/health/ready`, `maxUnavailable: 0`, `maxSurge: 1` và PDB `minAvailable: 1` đã có. Ghi thiếu cho phase 2 và 6: `preStop` và downward API `POD_NAME`.
<!-- Updated: Validation Session 1 - k8s probes already present -->
7. Cài `asciinema` và `agg` bằng `cargo install --locked` (Homebrew không ghi được trên máy này); thử ghi 10 giây rồi chuyển sang mp4 bằng ffmpeg.
8. Spike Drools **10.1.0** (Apache KIE, đủ `drools-engine` + `drools-engine-classic` + `drools-mvel`) trên Java 25 + Quarkus: biên dịch một file DRL lúc chạy thành `KieBase`, fire trên fact `QuoteRequest`,
   đổi DRL rồi giải phóng `KieBase` cũ (kiểm tra Metaspace). Nếu compile lúc chạy vướng, thử executable model trong ClassLoader riêng.
9. Kiểm tra bộ nhớ Docker còn đủ cho thêm pod `partner-sandbox` và tenant thứ ba (giữ volume DB nhỏ như memory đã ghi).

## Validation
Spike test xanh và in "plugin classloader collected"; demo cảnh 12, 13 chạy; Claude gọi được `list_doctypes`;
<!-- Updated: Validation Session 1 - MCP tool is list_doctypes -->
spike Drools đổi DRL hai lần lúc chạy và `KieBase` cũ được giải phóng.

## Rollback
Spike nằm trong test, xoá được không ảnh hưởng code khác.
