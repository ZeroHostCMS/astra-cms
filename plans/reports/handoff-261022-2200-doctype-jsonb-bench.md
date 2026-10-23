# Bàn giao: benchmark DocType JSONB, tiếp tục trên máy mạnh hơn

> **Trạng thái 2026-10-23: đã xong.** Ba phép đo còn thiếu đã chạy trên máy thứ hai (toàn bộ chuỗi, cùng một máy), cộng một giai đoạn đo giới hạn mới. Kết quả và kết luận: [docs/benchmark-doctype-jsonb-the-right-way.md](../../docs/benchmark-doctype-jsonb-the-right-way.md). Kết quả thô mỗi lần chạy: `scripts/bench/doctype-jsonb/results/<label>/`. Bộ đo chạy lại trên mọi server Linux: `scripts/bench/doctype-jsonb/bench.sh` (xem README cạnh nó). Các việc kỹ thuật ở mục 4 dưới đây đã làm, trừ materialised projection cho Currency; thêm một việc mục này chưa thấy: bộ đếm series gapless là trần của việc tạo đơn, nay có `gapless: false`. Phần dưới giữ nguyên như lúc bàn giao.

Ngày 2026-10-22. Tài liệu kết quả: [docs/benchmark-doctype-jsonb-the-right-way.md](../../docs/benchmark-doctype-jsonb-the-right-way.md). Script: `scripts/bench/doctype-jsonb/`. Kết quả thô lần chạy này: `scripts/bench/doctype-jsonb/results/` (mọi plan `EXPLAIN (ANALYZE, BUFFERS)`, pgbench, kích thước).

## 1. Đã xong và đã kết luận

- Baseline 12 truy vấn trên ba hình dạng (`astra_doc` JSONB đúng schema kernel, `orders_rel` bảng cột, `astra_doc_part` partition theo ngày), 5 triệu đơn, RLS bật, role `astra_app`.
- Phát hiện chính, đã xác nhận bằng plan và bằng catalog: dưới row-level security, planner không dùng expression index với `->>` và không dùng GIN với `@>` vì hai toán tử này không `LEAKPROOF`; `numeric` cũng không leakproof nên index trên cột numeric không được dùng cho `>` ở cả bảng truyền thống. Sửa bằng alias `astra_field(jsonb, text) LANGUAGE internal LEAKPROOF`: Q2 470 ms xuống 0,13 ms.
- Ghi (pgbench 16 client): insert JSONB 2.645 so với 3.381 tx/s; update 1.999 so với 2.909 tx/s; HOT 0 % ở cả hai vì `modified_at` nằm trong index list.
- Backfill cột materialised 5 triệu dòng: 12 phút 26 giây, bảng phình 44 %.
- Partition: không tăng tốc OLTP có index đúng; mất unique trên `name`; ranh giới ngày phải theo múi giờ nghiệp vụ.

## 2. Chưa đo (ba việc), lệnh chạy

Máy đề xuất: 32 GB RAM trở lên, NVMe, **ít nhất 40 GB đĩa trống cho Docker** (bộ đầy đủ ~30 GB gồm ba bảng, GIN, WAL 8 GB, bản rewrite khi `ALTER TYPE`). Nếu muốn số "VPS 16 GB", giữ `shared_buffers=4GB`, `effective_cache_size=12GB`.

```bash
docker run -d --name astra-bench-pg -e POSTGRES_PASSWORD=bench -e POSTGRES_USER=postgres -e POSTGRES_DB=bench -p 127.0.0.1:5433:5432 -v astra-bench-pg:/var/lib/postgresql/data --shm-size=4g pgvector/pgvector:pg16 postgres -c shared_buffers=4GB -c work_mem=64MB -c maintenance_work_mem=2GB -c effective_cache_size=12GB -c max_wal_size=4GB -c checkpoint_completion_target=0.9 -c random_page_cost=1.1 -c max_parallel_workers_per_gather=4 -c max_parallel_maintenance_workers=4 -c track_io_timing=on -c wal_compression=on -c shared_preload_libraries=pg_stat_statements
```

Chạy từ `scripts/bench/doctype-jsonb/`, `export PGPASSWORD=bench; P="psql -h 127.0.0.1 -p 5433 -U postgres -d bench"`:

Toàn bộ chuỗi (để có một bộ số nhất quán trên cùng máy, khuyến nghị):

```bash
$P -f 01-schema.sql && $P -v rows=5000000 -f 02-load.sql && $P -f 03-indexes.sql && OUT=./results ./run-queries.sh && CLIENTS=16 SECS=30 OUT=./results ./run-writes.sh all && $P -f 04-fixes.sql && OUT=./results ./run-fixed-queries.sh && $P -f 05-bigint-amounts.sql && OUT=./results ./run-bigint-queries.sh && CLIENTS=16 SECS=30 OUT=./results ./run-writes.sh reads-fixed
```

Chỉ ba việc còn thiếu (nhanh hơn, dùng bộ gọn không GIN, không partition):

```bash
$P -f 01-schema.sql && $P -v rows=5000000 -f 06-lean-reload.sql && OUT=./results ./run-bigint-queries.sh && CLIENTS=16 SECS=30 OUT=./results ./run-writes.sh reads-fixed
```

Việc thứ ba, kiểm chứng wrapper SQL `LEAKPROOF` có bị inline làm mất nhãn không (chạy bằng superuser sau khi có `astra_field`):

```sql
CREATE FUNCTION lp_sql(jsonb, text) RETURNS text LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE LEAKPROOF AS 'select $1->>$2';
GRANT EXECUTE ON FUNCTION lp_sql(jsonb,text) TO astra_app;
SET ROLE astra_app;
CREATE TABLE lp_test (tenant_id text NOT NULL, data jsonb NOT NULL);
INSERT INTO lp_test SELECT 'hanoi-express', jsonb_build_object('k', 'v'||n) FROM generate_series(1,200000) n;
ALTER TABLE lp_test ENABLE ROW LEVEL SECURITY; ALTER TABLE lp_test FORCE ROW LEVEL SECURITY;
CREATE POLICY p ON lp_test USING (tenant_id = current_setting('astra.tenant', true));
CREATE INDEX lp_test_sql ON lp_test (tenant_id, lp_sql(data,'k'));
CREATE INDEX lp_test_int ON lp_test (tenant_id, astra_field(data,'k'));
ANALYZE lp_test;
SELECT set_config('astra.tenant','hanoi-express',false);
EXPLAIN (COSTS OFF) SELECT * FROM lp_test WHERE lp_sql(data,'k') = 'v5';      -- Index Scan hay Seq Scan + Filter?
EXPLAIN (COSTS OFF) SELECT * FROM lp_test WHERE astra_field(data,'k') = 'v5'; -- kỳ vọng Index Scan
```

Ghi kết quả vào `docs/benchmark-doctype-jsonb-the-right-way.md`: mục 5.3 (Q5 bigint, hai bảng), 5.4 (Q7 sau repack + bigint), mục 6 (dòng "sau sửa"), mục 4.2 (wrapper SQL), rồi xoá mục 12.

## 3. Bẫy đã gặp, đã sửa trong script

- `pgbench` chạy từng câu autocommit, nên `set_config(..., true)` mất ngay; mọi script pgbench đã bọc `BEGIN; ... END;` như kernel. Thiếu bọc thì RLS trả 0 dòng và số đọc "đẹp" giả.
- Bash 3.2 của macOS không có `;;&` và không cho dùng biến trong cùng câu `local`; đã sửa.
- Server PostgreSQL chạy UTC, dữ liệu sinh theo +07: tên đơn `ORD-YYYYMMDD-` lấy ngày UTC, và partition theo ngày UTC lệch với "một ngày" nghiệp vụ. Để số sạch hơn, có thể thêm `-c timezone=Asia/Ho_Chi_Minh` vào `docker run`.
- `(data->>'cod_amount')::bigint` lỗi vì JSON chứa `"0.00"`; phải `::numeric::bigint` (đã sửa ở `06-lean-reload.sql`).
- `DROP TABLE gen` cần `CASCADE` vì hàm `gen_doc(gen)`.
- Đĩa: volume phình hơn 30 GB và làm đầy đĩa máy, PostgreSQL PANIC, Docker Desktop sập. Kiểm `df -h /` và `docker run --rm alpine df -h /` trước khi chạy; xoá volume khi xong: `docker rm -f astra-bench-pg; docker volume rm astra-bench-pg`.

## 4. Việc kỹ thuật cho kernel rút ra từ đo (chưa làm, ngoài phạm vi bench)

1. Hàm `astra_field` leakproof trong bootstrap superuser + kiểm tra fail-closed ở `RowLevelSecurityGuard`.
2. `ProjectionIndexer.expression` và `DocumentStore.list` dùng `astra_field`; index projection cho Select/Link kèm `modified_at DESC`.
3. Bỏ `astra_doc_data_gin` khỏi V1.
4. Index `(tenant_id, doctype, created_at DESC)` mặc định cho DocType giao dịch.
5. `projected: materialised` cho Currency sinh `bigint` đơn vị nhỏ nhất.
6. Keyset pagination trong `list`.
7. Gộp `validateLinks` thành một câu lệnh.

## Câu hỏi còn mở

- Có chạy lại toàn bộ chuỗi trên máy mới để mọi số cùng một máy, hay chỉ bù ba phép đo thiếu? Bản ghi hiện tại ghi rõ máy đo nên trộn hai máy vẫn đọc được, nhưng so sánh tương đối sạch hơn nếu chạy lại hết (khoảng 45 phút trên máy nhanh).
- `CustomerOrder` có nên là DocType dùng bảng cột riêng (mục 10 tài liệu) hay giữ JSONB và áp 8 sửa đổi? Cần quyết định sản phẩm, không phải bench.
