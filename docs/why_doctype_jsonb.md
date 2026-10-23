# Vì sao DocType Engine lưu JSONB, và đo thật thì nó chạy ra sao

> Tài liệu kỹ thuật kèm số đo thực tế trên PostgreSQL 16, 5 triệu đơn hàng (2,5 ngày ở mức 2 triệu đơn/ngày), chạy đúng schema của kernel Astra với row-level security bật. Mọi số ở đây là số đo, không phải mục tiêu thiết kế. Bộ đo tái lập được trên máy chủ Linux bất kỳ: [scripts/bench/doctype-jsonb/](../scripts/bench/doctype-jsonb/README.md).

## 0. Kết luận trước

1. **JSONB không chậm. Cách kernel truy vấn JSONB dưới RLS mới chậm, và đã được sửa.** Với `(data->>'field') = ?`, PostgreSQL **không dùng expression index** khi row-level security bật, vì `->>` không phải hàm *leakproof*. Truy vấn "đơn của khách X" mất 470–867 ms và quét 800 nghìn dòng mỗi worker. Kernel nay lọc và đánh index bằng `astra_field(data, 'f')`, một alias `LEAKPROOF` của cùng hàm C: cùng truy vấn còn **0,13–0,29 ms**, ngang bảng cột truyền thống.
2. **Sau khi sửa, đọc trên JSONB ngang bảng truyền thống.** Đơn lẻ: chênh dưới 0,1 ms. Đồng thời: trần đọc 32.700 giao dịch/giây so với 34.500 của bảng cột trên cùng máy (thấp hơn 5 %).
3. **Giới hạn thật của hệ thống không nằm ở JSONB mà ở bộ đếm đặt tên.** Series gapless (`ORD-.YYYYMMDD.-.#####`) khoá một dòng đếm mỗi tenant mỗi ngày cho đến khi transaction tạo đơn commit, nên mọi lần tạo đơn trong một tenant chạy **nối tiếp**: 291–425 đơn/giây bất kể 4 hay 64 client, p99 tới 0,76 giây. Bảng cột với cùng bộ đếm bị y như vậy. Bỏ khoá (`gapless: false`, nay có trong kernel): **8.868 đơn/giây**, gấp 20 lần, và tải hỗn hợp giờ cao điểm từ 7.145 lên **14.522 giao dịch/giây**.
4. **JSONB thua thật ở ba chỗ, đã có cách thiết kế vòng qua:** tổng hợp quét nhiều dòng (SUM theo ngày: 1,2 giây so với 0,4–0,7 giây, đưa sang replica/ledger); dung lượng (505 so với 310 byte/dòng); tạo đơn chậm hơn tới 17 %.
5. **GIN `jsonb_path_ops` là gánh nặng thuần.** Không bao giờ được dùng dưới RLS (`@>` không leakproof), tốn 1 GB cho 6 triệu doc, và kéo tải hỗn hợp xuống 28 %. Kernel nay bỏ nó (migration V6).
6. **Cả hệ thống qua HTTP** (kernel, Keycloak, PostgreSQL trên cùng một máy, token thật): **400 đơn/giây sạch** (4.360 request/giây, p99 dưới 47 ms), gần 2 lần mức đỉnh mục tiêu. Bài đo này tìm ra một lỗi thật: `X-Tx-LSN` mượn kết nối thứ hai cho mỗi lần ghi, làm hệ thống sụp ngay ở 400 đơn/giây; đã sửa.
7. **Partition theo tháng làm OLTP chậm đi một nửa** (18 partition: tải hỗn hợp 14.306 xuống 6.451, có thu hẹp theo ngày trong tên 8.294), vì tra cứu theo tên hay theo khách không có `created_at` nên phải dò mọi partition. Nó có trong kernel nhưng là tuỳ chọn, tắt cho các DocType của app Express; chỉ bật khi vòng đời dữ liệu (vacuum, archive) đáng hơn thông lượng.
8. **Cột materialised cho tiền** (`projected: materialised`, bigint phần trăm) đã có trong kernel: lọc khoảng nhanh hơn đọc từ JSON 8 lần, và nhanh hơn thêm 3 lần khi kernel lập plan theo giá trị thay vì plan dùng chung.
9. **Với 2 triệu đơn/ngày và 10 triệu user: khả thi trên một primary PostgreSQL cỡ vừa cộng replica đọc**, sau các thay đổi đã làm (mục 8). Trên một máy Xeon 2014 12 lõi trong Docker Desktop, kernel đã sửa đạt 14.500 giao dịch/giây khi chạy hết sức (gấp 2 lần giờ cao điểm mục tiêu, 20 lần mức trung bình) và giữ đúng nhịp giờ cao điểm với độ trễ trung vị 3 ms, nhưng tụt lại ở 1,5 lần nhịp đỉnh. Kernel trước khi sửa không theo kịp từ 1,5 lần nhịp đỉnh, và khi chạy hết sức chỉ đạt đúng mức đỉnh với p99 tạo đơn gần 2 giây. Dư địa thật cần đo lại trên server Linux (mục 2).

## 1. DocType Engine lưu trữ thế nào

Mọi DocType có `storage: jsonb` (mặc định) nằm trong **một bảng** `astra_doc` ([V1__astra_doc.sql](../astra-doctype/src/main/resources/db/migration/V1__astra_doc.sql)):

| Cột | Vai trò |
| --- | --- |
| `id uuid`, `tenant_id`, `doctype`, `name` | khoá; `UNIQUE (tenant_id, doctype, name)` là index tìm theo khoá |
| `docstatus`, `owner`, `created_at`, `modified_at`, `version`, `amended_from` | metadata vòng đời, cột quan hệ thật, index được |
| `data jsonb` | toàn bộ field nghiệp vụ của DocType |

Ba cơ chế quyết định hiệu năng:

- **Row-level security theo tenant.** Mỗi thao tác chạy trong một transaction, `TenantTransactions` gọi `set_config('astra.tenant', ?, true)` rồi policy `tenant_id = current_setting('astra.tenant')` được PostgreSQL ghép vào mọi câu lệnh. Kernel kết nối bằng role `astra_app` không phải superuser; `RowLevelSecurityGuard` từ chối khởi động nếu không phải vậy. Hệ quả cho planner ở mục 4.
- **Projection = expression index.** Field khai báo `projected: true` được `ProjectionIndexer` tạo index `CREATE INDEX CONCURRENTLY` lúc khởi động, không `ALTER TABLE`, không khoá bảng. Sau thay đổi này index là `(tenant_id, doctype, astra_ext.astra_field(data, 'f'), modified_at DESC) WHERE astra_ext.astra_field(data, 'f') IS NOT NULL`, và `DocumentStore.list` lọc bằng đúng biểu thức đó.
- **Hai lối thoát ngoài JSONB.** `storage: ledger` là bảng cột cố định, append-only, partition theo tháng ([V3__ledger.sql](../astra-doctype/src/main/resources/db/migration/V3__ledger.sql)); CDC worker Rust đổ WAL của `astra_doc` sang bảng đọc `replica_order`, `replica_waybill` có cột thật ([V5__cdc_replicas.sql](../astra-doctype/src/main/resources/db/migration/V5__cdc_replicas.sql)). Tổng hợp và báo cáo chạy ở đó, không trên JSONB.

So với bảng khai báo thường, mô hình này đổi **DDL lúc đổi form** lấy **một tầng gián tiếp khi đọc field**. Câu hỏi của tài liệu là tầng gián tiếp đó tốn bao nhiêu, PostgreSQL có vẫn dùng được index, partition như bảng thường không, và cả hệ thống chịu được tải nào.

## 2. Phương pháp đo

| Hạng mục | Lần 1 (2026-10-22) | Lần 2 (2026-10-23) |
| --- | --- | --- |
| Máy | Apple M1 Pro 10 nhân, Docker Desktop 16 GB RAM, 10 CPU | Intel Xeon E5-2678 v3 (2014, 12 lõi / 24 luồng, 2,5 GHz), 32 GB RAM, NVMe WD SN570; Docker Desktop trên Windows (WSL2), VM 16 GB / 24 CPU dùng chung với vài container khác (khoảng 3 GB) |
| PostgreSQL | 16.15, image `pgvector/pgvector:pg16` | cùng image |
| Cấu hình | `shared_buffers=4GB`, `work_mem=64MB`, `effective_cache_size=12GB`, `max_wal_size=8GB` | Chuỗi gốc: như lần 1 (`max_wal_size=4GB`). Giai đoạn giới hạn: chỉnh theo máy, `max_connections=400`, `max_wal_size=16GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, autovacuum mạnh hơn |
| pgbench | 18.3 trên macOS, ngoài container | 16.15 trong container, dùng chung CPU với server |
| Kết quả thô | [results/m1pro-10c-16g-docker-20261022](../scripts/bench/doctype-jsonb/results/m1pro-10c-16g-docker-20261022/) | [results/xeon-e5-2678v3-24t-docker-wsl2-20261023](../scripts/bench/doctype-jsonb/results/xeon-e5-2678v3-24t-docker-wsl2-20261023/) |

Dữ liệu, như nhau ở hai lần: 5.000.000 CustomerOrder đúng field của [customer-order.doctype.yaml](../astra-apps/express/src/main/resources/META-INF/astra/doctypes/customer-order.doctype.yaml) cộng `ops_status` (8 trạng thái, phân bố lệch); 1.000.000 Customer dùng chung bảng; 3 tenant (80/18/2 %); `created_at` trải đều 60 giờ từ 2026-10-20 00:00+07. Giai đoạn giới hạn thêm 3.800.890 Waybill (một cho mỗi đơn của tenant lớn, `scan_log` 3 mục).

Ba hình dạng, cùng dữ liệu: `astra_doc` (schema kernel); `orders_rel` (bảng cột truyền thống, B-tree tương đương); `astra_doc_part` (bảng kernel partition RANGE theo ngày). Mọi truy vấn chạy bằng `astra_app`, tenant gán qua `set_config` trong transaction, RLS bật và FORCE, như production.

Cách đo:

- **Truy vấn đơn lẻ**: `EXPLAIN (ANALYZE, BUFFERS)` 4 lần; báo lần đầu ("lạnh") và tốt nhất trong 3 lần sau ("ấm").
- **Giới hạn** (mục 6): mỗi workload tái hiện đúng các câu lệnh kernel gửi cho một thao tác (gán tenant, kiểm tra Link, bộ đếm series, ghi kèm `RETURNING *`), chạy bằng `pgbench -M prepared` 30 giây mỗi mức client, có `CHECKPOINT` trước mỗi lần chạy. Ghi lại giao dịch/giây, p50/p95/p99 theo từng loại giao dịch từ log từng giao dịch, byte WAL mỗi giao dịch, % CPU, % trúng cache.

Hạn chế của phép đo, đọc trước khi dùng số:

- Cả hai máy đều chạy PostgreSQL trong Docker Desktop: đĩa là file ảnh đĩa (VHDX trên Windows) nên fsync chậm và thỉnh thoảng khựng nhiều giây (log checkpoint ghi một lệnh fsync 6 giây). `pg_test_fsync` trên đĩa của lần 2 đo khoảng 970 lần `fdatasync`/giây, gần 1 ms mỗi commit; NVMe thật trên Linux thường nhanh hơn hàng chục lần. Server Linux thật cho số ghi tốt hơn và ổn định hơn; bộ đo đóng gói để chạy lại trên đó.
- pgbench chạy cùng máy và tranh CPU với PostgreSQL ở lần 2, nên trần đo được thấp hơn trần thật của server.
- 5 triệu đơn (khoảng 10 GB dữ liệu và index) vẫn gần như vừa cache của máy 16 GB. Hệ thống thật sau một năm có khoảng 730 triệu đơn; phần ngoại suy ở mục 10.
- **So sánh tương đối giữa các phương án trên cùng máy** mới là thứ có giá trị; số tuyệt đối thuộc về phần cứng.
- `run-writes.sh` của chuỗi gốc truyền `-d bench` cho pgbench. Với pgbench 17 trở lên (lần 1, pgbench 18 trên macOS) `-d` là tên database; với pgbench 16 (lần 2, trong container) `-d` là **bật debug**, in vết từng lệnh, làm giảm thông lượng. Các số `run-writes.sh` của lần 2 vì vậy chỉ dùng để so tương đối trong cùng lần chạy; giai đoạn giới hạn (đã sửa lỗi này) thay thế chúng.

## 3. Dung lượng và thời gian nạp, tạo index

| | `astra_doc` (6,0 triệu doc) | `orders_rel` (5,0 triệu) | `astra_doc_part` (5,0 triệu) |
| --- | --- | --- | --- |
| Heap | 3.004 MB | 1.503 MB | 2.775 MB |
| Index | 2.328 MB (GIN 1.047 MB) | 854 MB | 1.282 MB (không GIN) |
| Tổng | 5.332 MB | 2.357 MB | 4.056 MB |
| Byte/dòng trung bình | 505–549 (JSON 417) | 300–310 | ~550 |
| TOAST | 0 (doc dưới 2 KB nằm trong heap) | 0 | 0 |

Thời gian nạp 5 triệu dòng (lần 2): bảng cột 82 giây; JSONB 2 bảng 7 phút 31 giây (gồm `jsonb_build_object` cho mỗi dòng). Tạo index: B-tree expression trên `customer` 26 giây, cùng cột trên bảng thường 20 giây; **GIN `jsonb_path_ops` 156 giây**.

JSONB lưu **tên field trong mỗi dòng**, nên 13 field tốn thêm khoảng 120 byte tên key và 130 byte cấu trúc. Với 730 triệu đơn/năm, chênh lệch heap khoảng 180 GB/năm. Đây là chi phí thật, chỉ giảm được bằng tên field ngắn, archive sớm, hoặc bảng cột cho DocType khối lượng lớn nhất (mục 10).

Index projection dạng partial (`WHERE field IS NOT NULL`, kernel sau sửa) nhỏ hơn rõ khi nhiều DocType dùng chung bảng: trên 10 triệu doc (đơn, Waybill, khách), tổng index của `astra_doc` từ 4.557 MB xuống 3.726 MB; index `customer` từ 716 xuống 383 MB, `zone` từ 670 xuống 336 MB.

## 4. Đọc: baseline với truy vấn kernel sinh ra trước khi sửa

Thời gian "ấm" (ms), RLS bật. Mỗi ô là lần 1 / lần 2:

| # | Câu hỏi | JSONB | Bảng cột | JSONB partition |
| --- | --- | --- | --- | --- |
| Q1 | Lấy 1 đơn theo `name` | 0,07 / 0,12 | 0,08 / 0,23 | 0,21 / 0,61 (dò 5 partition) |
| Q2 | 20 đơn mới nhất của khách `CUST-0123456` | **470 / 867** | 0,07 / 0,19 | 558 / 1.018 |
| Q3a | 20 đơn mới nhất `zone = Remote` (10 %) | 0,53 / 2,3 | 0,35 / 0,57 | — |
| Q3b | 20 đơn mới nhất `ops_status = Cancelled` (0,5 %) | 5,0 / 9,6 | 2,9 / 5,9 | 6,5 / 11,5 |
| Q4 | Q2 thêm `docstatus = 1` | **608 / 864** | 0,08 / 0,18 | — |
| Q5 | `zone = Remote AND cod_amount > 4.900.000`, 20 mới nhất | 25 / 28 | 12 / 23 | — |
| Q6 | Một ngày, `ops_status = Failed Delivery`, 50 mới nhất | 534 / 766 | 136 / 230 | 322 / 545 |
| Q7 | SUM(cod_amount), COUNT theo zone của một ngày (2 triệu dòng) | 863 / 1.182 | 353 / 656 | 1.944 / 3.866 |
| Q8a | `data @> '{"customer": ...}'` (GIN) | **6.537 / 7.404** | — | — |
| Q8b | `data @> '{"zone":"Remote","ops_status":"Returned"}'` COUNT | 738 / 925 | 57 / 78 | — |
| Q9 | COUNT đơn của một khách | **545 / 876** | 0,09 / 0,12 | — |
| Q10 | Danh sách `OFFSET 200000` | 200 / 248 | 218 / 248 | — |

Máy lần 2 chậm hơn khoảng 1,5–2 lần ở truy vấn đơn lẻ (CPU 2,5 GHz đời 2014 so với M1 Pro); hình dạng kết quả giống hệt.

### 4.1 Vì sao Q2, Q4, Q9 chậm hàng nghìn lần: leakproof và row-level security

Plan của Q2 dưới RLS (role `astra_app`, lần 2):

```
Parallel Index Scan using astra_doc_p_zone on astra_doc
  Index Cond: ((tenant_id = current_setting('astra.tenant', true)) AND (doctype = 'CustomerOrder'))
  Filter: ((data ->> 'customer') = 'CUST-0123456')
  Rows Removed by Filter: 800168        (mỗi worker, 5 worker)
  Buffers: shared hit=792145
```

Cùng câu lệnh bằng superuser (RLS bị bỏ qua): `Index Scan using astra_doc_p_customer`, 0,13 ms.

Index tồn tại, hợp lệ, và planner **biết** nó khớp. Nó vẫn từ chối vì một quy tắc an toàn của RLS: điều kiện do người dùng viết chỉ được đánh giá *trước* policy (tức là được đưa vào index condition) nếu mọi hàm trong điều kiện là `LEAKPROOF`, nghĩa là không thể rò rỉ giá trị của dòng bị policy che qua thông báo lỗi. `texteq` leakproof, `jsonb_object_field_text` (`->>`) thì không, nên cả biểu thức thành `Filter` sau khi policy chạy, và index chỉ còn dùng được phần `(tenant_id, doctype)`.

Trong catalog:

```
jsonb_object_field_text | f      (->>)
jsonb_contains          | f      (@>)
numeric_gt, numeric_eq  | f
texteq, text_lt, int8gt, int8eq, float8gt, timestamptz_lt, date_ge, uuid_eq, booleq | t
textlike, texticlike    | f      (LIKE, ILIKE)
```

Hai hệ quả cùng gốc:

- **GIN không bao giờ được dùng dưới RLS**: Q8a quét 4 triệu dòng qua index list rồi lọc `@>`, 7,4 giây. Bằng superuser, Q8b dùng GIN, 36 ms.
- **`numeric` không leakproof**, nên index trên cột `numeric`, kể cả ở bảng truyền thống, không phục vụ được truy vấn khoảng dưới RLS. Kiểm tra riêng trên 1 triệu dòng, khoảng chọn 0,1 % ([09-range-leakproof-check.sql](../scripts/bench/doctype-jsonb/09-range-leakproof-check.sql)):

| Cùng dữ liệu, cùng index | Plan | Thời gian |
| --- | --- | --- |
| cột `numeric(18,2)`, dưới RLS | Parallel Seq Scan + Filter | 190 ms |
| cột `bigint`, dưới RLS | Index Only Scan | 2,4 ms |
| cột `numeric`, superuser | Index Only Scan | 2,0 ms |

Đây không phải lỗi của JSONB mà là tương tác giữa RLS và mọi hàm không leakproof. Bảng truyền thống thoát ở đa số truy vấn vì `=`, `<`, `>` trên text, int, timestamp đều leakproof.

### 4.2 Cách sửa: accessor leakproof

`jsonb_object_field_text` thật ra không rò rỉ gì, chỉ là chưa được đánh dấu. Một alias `LANGUAGE internal` trỏ vào cùng hàm C, khai báo `LEAKPROOF` (cần superuser một lần; nay nằm trong mọi script bootstrap: [deploy/db/05-astra-field.sql](../deploy/db/05-astra-field.sql), `deploy/docker/init/01-app-role.sql`, init của k8s và của test):

```sql
CREATE SCHEMA astra_ext;                          -- của superuser; astra_app chỉ được USAGE
GRANT USAGE ON SCHEMA astra_ext TO astra_app;
CREATE FUNCTION astra_ext.astra_field(jsonb, text) RETURNS text
    LANGUAGE internal IMMUTABLE STRICT PARALLEL SAFE LEAKPROOF AS 'jsonb_object_field_text';
GRANT EXECUTE ON FUNCTION astra_ext.astra_field(jsonb, text) TO astra_app;
```

Hàm nằm trong schema riêng vì hai lý do: role ứng dụng gọi được nhưng không thay được nó, và Flyway đòi schema `public` rỗng trên database mới (đặt hàm trong `public` làm migration đầu tiên từ chối chạy; bộ test đã bắt được lỗi này). Bộ đo dùng `astra_field` trong `public` cho gọn; kernel dùng `astra_ext.astra_field`.

| # | Trước (ms), lần 1 / lần 2 | Sau (ms), lần 1 / lần 2 | Bảng cột (ms) | Plan sau |
| --- | --- | --- | --- | --- |
| Q2 khách hàng, 20 mới nhất | 470 / 867 | **0,13 / 0,29** | 0,07 / 0,19 | Index Scan theo index `astra_field(data, 'customer')`, 9 buffer |
| Q4 + docstatus | 608 / 864 | **0,13 / 0,29** | 0,08 / 0,18 | cùng index, lọc docstatus trên 5 dòng |
| Q9 COUNT theo khách | 545 / 876 | **0,15 / 0,29** | 0,09 / 0,12 | cùng index |

Chênh 0,05–0,1 ms còn lại là dòng JSONB rộng hơn (497 so với 291 byte trong plan) và parse `data` khi trả về.

**Vì sao `LANGUAGE internal`, không phải hàm SQL bọc `->>`?** Đã kiểm chứng ([07-leakproof-inline-check.sql](../scripts/bench/doctype-jsonb/07-leakproof-inline-check.sql)): một hàm `LANGUAGE sql ... LEAKPROOF AS 'select $1->>$2'` được PostgreSQL inline lúc plan, và sau khi inline planner thấy lại `->>` không leakproof. Dưới RLS plan là `Seq Scan` + `Filter: (data ->> 'k') = 'v5'`, dù index trên đúng hàm đó tồn tại. Alias `internal` không bị inline: `Index Scan`, `Index Cond: astra_field(data, 'k') = 'v5'`. Bằng superuser cả hai đều dùng index.

**Giới hạn của cách sửa:** chỉ superuser khai báo được `LEAKPROOF`. PostgreSQL managed (RDS, Cloud SQL, Azure) không cấp superuser thật, nên trên các dịch vụ đó cách sửa này không áp dụng được; kernel cần PostgreSQL tự quản (VM, hoặc operator Kubernetes như CloudNativePG).

## 5. Từng loại truy vấn sau khi sửa

### 5.1 Field ít giá trị, sắp xếp mới nhất (Q3)

Với giá trị phổ biến (`zone = Remote`, 10 %), planner của cả hai hình dạng đi theo index `modified_at DESC` rồi lọc; 20 dòng kết quả nằm trong vài trăm dòng đầu, dưới 3 ms. Với giá trị hiếm (`Cancelled`, 0,5 %) cùng chiến lược phải lọc 3.290 dòng: 9,6 ms JSONB, 5,9 ms bảng cột (lần 2). Index đúng cho "trạng thái X, mới nhất" là **index tổ hợp có cột sắp xếp**, nay là dạng mặc định của `ProjectionIndexer`:

```sql
CREATE INDEX astra_doc_pf_ops_status ON astra_doc (tenant_id, doctype, astra_field(data, 'ops_status'), modified_at DESC)
    WHERE astra_field(data, 'ops_status') IS NOT NULL;
```

Q3b sau đó: **0,22 / 0,42 ms, 24 buffer**, không sort. Bảng truyền thống cần đúng index tương tự để đạt cùng số; không có lợi thế cấu trúc nào ở đây.

### 5.2 Lọc theo ngày (Q6)

Baseline cả hai bảng thiếu index `created_at`: JSONB 534–766 ms, bảng cột 136–230 ms. Partition theo ngày giảm còn 322–545 ms nhờ pruning. Với index `(tenant_id, doctype, created_at DESC)`: JSONB **0,95 / 2,2 ms**, bảng cột 0,57 / 2,2 ms. `created_at` là cột quan hệ thật nên JSONB không bất lợi; kernel nay tạo index này mặc định.

### 5.3 Khoảng số tiền (Q5) và cột materialised

`(data->>'cod_amount')::numeric > ?` không index được dưới RLS (cast và `numeric_gt` đều không leakproof). Mô hình thiết kế nói field như vậy dùng `projected: materialised`: thêm cột, backfill, index. Đo quy trình đó trên 5 triệu dòng:

| Bước | Lần 1 | Lần 2 | Ghi chú |
| --- | --- | --- | --- |
| `ALTER TABLE astra_doc ADD COLUMN m_cod_amount numeric` | < 10 ms | 3 ms | metadata, không khoá |
| Backfill 20 batch × 250.000 dòng | **12 phút 26 giây** | **19 phút 52 giây** | mỗi UPDATE ghi lại cả dòng và cập nhật mọi index |
| `VACUUM ANALYZE` sau backfill | 84 giây | 93 giây | |
| Heap sau backfill | 5.314 MB, **44 % trống** | 5.405 MB, 44 % trống | bảng phình gần gấp đôi, cần `pg_repack` hoặc rewrite |

Q5 cụ thể ("Remote, COD > 4,9 triệu, 20 mới nhất") **không đổi** dù cột là `numeric` hay `bigint` (lần 2: 39 ms numeric, 104 ms bigint JSONB; 16,5 và 39 ms bảng cột), vì planner chọn đi theo index `modified_at` rồi lọc khoảng 11.000 dòng ở cả bốn trường hợp; chênh lệch giữa các lần chạy là do số worker song song. Kiểu cột quyết định khi khoảng là điều kiện chọn lọc chính (bảng ở mục 4.1: 190 ms so với 2,4 ms).

Bài học cho kernel: `Currency` khi materialised phải thành `bigint` đơn vị nhỏ nhất, không phải `numeric(18,2)`. `ledger` hiện dùng `numeric(18,2)` cho `debit`/`credit`, nên chịu cùng giới hạn nếu có truy vấn khoảng dưới RLS.

**Nay đã có trong kernel.** `projected: materialised` (Currency, Int, Float, Date, Datetime, Check) thêm cột kiểu thật `m_<field>` (Currency thành bigint phần trăm, 4.900.000,50 thành 490000050) kèm index, do một trigger tính từ `data` ở mọi lần ghi nên mọi đường ghi, kể cả instance cũ trong lúc nâng cấp, đều đúng. Dữ liệu có sẵn được backfill ở nền theo từng tenant và từng lô; khi chưa xong, bộ lọc đọc giá trị từ JSON nên kết quả không bao giờ thiếu dòng. Bộ lọc khoảng qua REST: `cod_amount__gt=4900000`, `__gte`, `__lt`, `__lte`; cũng dùng được cho `created_at` và `modified_at`. Lọc khoảng trên field không materialised bị từ chối (422) vì nó sẽ quét cả tenant. CustomerOrder khai báo `cod_amount` như vậy.

Đo trên 4 triệu đơn, "COD trên ngưỡng cao, 20 đơn mới nhất", 16 client (lần 2):

| Cách lọc | Giao dịch/giây | p50 | p99 |
| --- | --- | --- | --- |
| Từ JSON (cast mỗi dòng), như khi chưa materialise | 16 | 986 ms | 1.298 ms |
| Cột materialised, plan dùng chung của prepared statement | 46 | 303 ms | 373 ms |
| **Cột materialised, plan theo giá trị** (kernel đặt `prepareThreshold 0` cho danh sách có lọc khoảng) | **135** | **13 ms** | 336 ms |

Plan dùng chung không biết ngưỡng nên chọn đi theo index `modified_at` và lọc 13.378 dòng; plan theo giá trị đi thẳng vào index của cột. Đuôi p99 còn dài khi một ngưỡng khớp hàng nghìn đơn phải đọc từ đĩa: đây là truy vấn đối soát, không nằm trên đường nóng.

### 5.4 Tổng hợp (Q7): chỗ JSONB thua và phải tránh

SUM/COUNT 2 triệu dòng một ngày: JSONB 863 / 1.182 ms, bảng cột 353 / 656 ms, chênh 1,8–2,4 lần vì mỗi dòng phải giải mã JSONB. Cột materialised không cứu được: ngay sau backfill (bảng phình 44 %) lần 1 đo 6.410 ms; sau khi rewrite sang `bigint` (hết phình), lần 2 đo 788 ms dùng cột và 1.194 ms đọc từ JSON, trong khi bảng cột 405–1.077 ms tuỳ cache. Bảng JSONB vẫn phải đọc heap rộng gấp gần 2 lần dù chỉ cần 2 cột.

Kết luận: không chạy tổng hợp trên `astra_doc`. Dự án đã có hai nơi đúng: `replica_order` qua CDC (cột thật) và `astra_ledger`. Partition theo ngày **không** cứu Q7 (1.944 / 3.866 ms, tệ hơn bảng đơn) vì planner chọn Merge Append qua index của hai partition; ranh giới ngày +07 không trùng partition theo ngày UTC nên phải chạm 2 partition.

### 5.5 Phân trang (Q10)

`OFFSET 200000` tốn 200–250 ms ở cả hai hình dạng; không liên quan JSONB. Keyset (`WHERE (modified_at, name) < (?, ?) ORDER BY modified_at DESC, name DESC`) là 0,24 / 0,31 ms JSONB, 0,15 / 0,22 ms bảng cột. Kernel nay trả con trỏ trang sau trong header `X-Next-Cursor` và nhận nó qua `?after=`.

## 6. Giới hạn thông lượng (lần 2)

Tất cả dưới cấu hình đã chỉnh theo máy, bộ index của kernel sau sửa (projection partial, không GIN), 64 client trừ khi ghi khác. Kết quả thô: `results/xeon-.../limits/`.

### 6.1 Đọc theo số kết nối

Tải đọc: 70 % lấy một đơn, 20 % danh sách theo khách, 10 % danh sách theo trạng thái.

| Kết nối | JSONB giao dịch/giây | p99 lấy đơn (ms) | Bảng cột giao dịch/giây | p99 (ms) | CPU |
| --- | --- | --- | --- | --- | --- |
| 8 | 11.980 | 0,97 | 10.593 | 1,31 | 34 % |
| 16 | 21.186 | 1,25 | 22.648 | 1,11 | 61 % |
| 32 | 28.964 | 2,18 | 30.242 | 2,27 | 76 % |
| **64** | **32.740** | 7,01 | **34.467** | 6,34 | 81 % |
| 128 | 28.828 | 12,12 | 30.021 | 11,03 | 76 % |
| 256 | 25.817 | 22,46 | 28.119 | 18,85 | 76 % |

Từ 16 kết nối trở lên JSONB kém bảng cột 4–8 % (ở 8 kết nối nhanh hơn 13 %, trong sai số của phép đo ngắn). **Trần nằm ở 64 kết nối** (khoảng 2,7 lần số luồng CPU); quá đó thông lượng giảm và độ trễ tăng tuyến tính. Pool kết nối của kernel phải tính trên tổng mọi instance: nay chỉnh được bằng `ASTRA_DB_POOL_MAX` (mặc định 20 mỗi instance).

Đọc 70 % theo khoá phân bố đều trên mọi đơn và đọc chỉ 10 % đơn mới nhất cho cùng 36.700 giao dịch/giây: ở 5 triệu đơn mọi thứ vẫn nằm trong cache (trúng 99–100 %). Khác biệt này chỉ xuất hiện khi dữ liệu lớn hơn RAM (mục 10).

### 6.2 Ghi: tạo đơn, quét Waybill

Mỗi workload là đúng chuỗi lệnh kernel gửi: tạo đơn = gán tenant, kiểm tra Link `customer`, lấy số series, `INSERT ... RETURNING *`; quét = gán tenant, đọc Waybill, kiểm tra Link `order`, ghi lại doc (đổi trạng thái, nối `scan_log`) có kiểm tra version.

| Giao dịch/giây | 4 client | 16 client | 64 client | p99 ở 64 (ms) |
| --- | --- | --- | --- | --- |
| Tạo đơn JSONB, series gapless (kernel trước sửa) | 374 | 291 | 425 | 758 |
| Tạo đơn bảng cột, series gapless | 449 | 409 | 584 | 636 |
| **Tạo đơn JSONB, số không khoá (`gapless: false`)** | 1.370 | 5.118 | **8.868** | 36 |
| Tạo đơn bảng cột, số không khoá | 1.561 | 4.840 | 10.628 | 35 |
| Quét Waybill JSONB, 1 transaction (kernel sau sửa) | 1.180 | 3.846 | 7.050 | 46 |
| Quét Waybill JSONB, 2 transaction (REST trước sửa) | 1.129 | 3.587 | 6.493 | 40 |
| Đổi trạng thái trên doc đơn hàng JSONB | 1.288 | 3.741 | 8.246 | 28 |
| Đổi trạng thái bảng cột | 1.211 | 4.206 | 7.655 | 45 |

Ba điều rút ra:

- **Bộ đếm series là trần cứng của việc tạo đơn**, ở cả hai cách lưu. Dòng đếm `(tenant, "ORD-.20261023.-.#####")` bị khoá từ lúc lấy số tới lúc transaction commit; thêm client chỉ làm hàng đợi dài ra (p50 7 ms ở 4 client, 101 ms ở 64 client). Với Waybill, số được lấy trong hook lúc submit đơn, tức là trong giao dịch phân tán Seata, nên khoá còn giữ lâu hơn. Bỏ khoá: gấp 15–20 lần.
- **JSONB tạo đơn chậm hơn bảng cột tới 17 %** khi không bị khoá che (8.868 so với 10.628 đơn/giây ở 64 client; ở 16 client lại nhanh hơn 6 %), vì dòng rộng hơn và nhiều index hơn. Đổi trạng thái thì ngang nhau.
- **Gộp đọc-kiểm quyền-ghi vào một transaction** (kernel sau sửa) nhanh hơn đường REST cũ 4–9 %, tăng theo số client.
- Ghi bị giới hạn bởi **độ trễ commit** chứ không bởi CPU: ở 16 client CPU chỉ 27–30 %. Thông lượng tăng theo số client vì nhiều commit được fsync chung (group commit). Đĩa có fsync nhanh hơn (NVMe thật, không qua VHDX) sẽ cho số cao hơn ở ít client; `pg_test_fsync` trong bộ đo ghi lại điều này cho mỗi máy.
- **Index projection partial** không làm ghi nhanh hơn ở mức đo được (quét Waybill 4.147 so với 4.058/giây ở 16 client, tải hỗn hợp 7.761 so với 7.744): lợi ích của nó là dung lượng (mục 3).

### 6.3 Tải hỗn hợp giờ cao điểm

Tỷ lệ mỗi đơn: 1 tạo, 8 lần quét, 14 lần xem, 6 danh sách theo khách, 2 danh sách theo trạng thái (31 giao dịch). 2 triệu đơn/ngày, đỉnh gấp 10 lần trung bình, là 231 đơn/giây, tức **khoảng 7.200 giao dịch/giây**.

| Chạy hết sức | Giao dịch/giây | Tương đương đơn/giây | p99 tạo đơn | p99 quét | p99 xem | CPU |
| --- | --- | --- | --- | --- | --- | --- |
| JSONB, series gapless, 16 client | 6.710 | 216 | 71 ms | 16 ms | 2,2 ms | 31 % |
| JSONB, series gapless, 64 client | 7.145 | 230 | **1.937 ms** | 22 ms | 2,1 ms | 39 % |
| Bảng cột, series gapless, 64 client | 8.357 | 270 | 1.474 ms | 17 ms | 2,3 ms | 43 % |
| **JSONB, số không khoá, 32 client** | 12.747 | 411 | 16 ms | 17 ms | 2,1 ms | 58 % |
| **JSONB, số không khoá, 64 client** | **14.522** | **468** | 54 ms | 59 ms | 4,2 ms | 62 % |
| JSONB, số không khoá, 128 client | 13.207 | 426 | 152 ms | 163 ms | 15 ms | 62 % |

Kernel trước sửa dừng ở khoảng 230 đơn/giây, đúng bằng mức đỉnh mục tiêu, với p99 tạo đơn gần 2 giây: không đạt. Kernel sau sửa đạt **gấp 2 lần đỉnh** khi chạy hết sức; nhịp duy trì được ở mục 6.4.

### 6.4 Độ trễ ở tải cố định

`pgbench -R` giữ nhịp đến cố định; độ trễ tính cả thời gian chờ tới lượt, nên một hệ thống không theo kịp cho p99 tăng dần. 10 giây đầu mỗi lần chạy không tính vào phân vị (đợt ghi full-page ngay sau checkpoint).

| Nhịp đến (giao dịch/giây) | Cấu hình | Đạt được | p50 | p95 | Ghi chú |
| --- | --- | --- | --- | --- | --- |
| 7.200 (đỉnh) | JSONB, series gapless (trước sửa) | 7.202 | 3,6 ms | 20 ms | 1 lần chạy |
| 7.200 | **JSONB, số không khoá** | 7.118–7.208 | **2,9–5,9 ms** | 0,3–5,0 s | 4 lần chạy; p95 do môi trường dừng (dưới) |
| 7.200 | Bảng cột, số không khoá | 7.070–7.218 | 2,5–3,0 ms (một lần 373 ms) | 5 ms–7,9 s | 4 lần chạy |
| 10.800 (1,5 lần) | JSONB, series gapless | 7.534 | 9,4 s | 18 s | không theo kịp |
| 10.800 | **JSONB, số không khoá** | **10.317** | 133 ms | 3,0 s | bắt đầu tụt lại |
| 10.800 | Bảng cột, số không khoá | 10.810 | 3,0 ms | 1,2 s | theo kịp |
| 14.400 (2 lần) | JSONB, series gapless | 6.950 | 18,6 s | 30 s | không theo kịp |
| 14.400 | JSONB, số không khoá | 11.543 | 1,3 s | 10 s | không theo kịp |
| 14.400 | Bảng cột, số không khoá | 14.397 | 4,1 ms | 2,8 s | theo kịp |

Đọc bảng này cùng tiến độ từng 5 giây (`-P 5` trong `limits/rate-*.txt`): ở trạng thái ổn định, độ trễ trung bình là **1,6–5 ms** ở nhịp đỉnh. Trong mỗi lần chạy có một hai cửa sổ cả hệ thống dừng 0,5–6 giây (thông lượng rơi xuống 75–125 giao dịch/giây rồi bắt kịp ở 14.000–15.000), xảy ra ở cả JSONB lẫn bảng cột và trùng với lệnh fsync nhiều giây trong log checkpoint. Đó là đĩa ảo của Docker Desktop trên Windows, không phải PostgreSQL bão hoà; vì vậy p95/p99 ở đây không dùng được để kết luận, và phải đo lại trên server Linux bằng bộ đo.

Điều đọc được chắc chắn: **kernel trước sửa không theo kịp từ 1,5 lần nhịp đỉnh** (tạo đơn xếp hàng sau khoá series, độ trễ trung vị tính bằng giây); **kernel sau sửa giữ được nhịp đỉnh với trung vị 3 ms, và tụt lại ở 1,5 lần**; bảng cột giữ được 2 lần. Ở tải hỗn hợp nặng ghi, JSONB có ít hơn khoảng 25–30 % dư địa so với bảng cột trên cùng máy, phù hợp với chênh lệch ghi ở mục 6.2 cộng thêm chi phí parse JSON khi đọc.

### 6.5 Đánh đổi đo được

| Phương án | Được | Mất |
| --- | --- | --- |
| `gapless: false` cho DocType khối lượng lớn (đã áp cho CustomerOrder, Waybill) | tạo đơn gấp 15–20 lần; tải hỗn hợp gấp 2 lần; p99 tạo đơn từ 1,9 s xuống 54 ms | số có khoảng trống (khối số chưa dùng khi instance dừng, giao dịch rollback); số giữa các instance xen kẽ nhau, không tăng theo thời gian tuyệt đối. Không dùng cho chứng từ kế toán cần đánh số liên tục |
| Bỏ GIN (đã áp, V6) | tải hỗn hợp **+40 %** (10.406 lên 14.522), insert +31 % ở 16 client, quét +11 %, bớt 1 GB/6 triệu doc | mất tìm kiếm `@>` tuỳ ý; dưới RLS nó vốn không dùng được |
| `synchronous_commit = off` | insert +63 % (5.118 lên 8.321 ở 16 client), quét +87 %, tải hỗn hợp +14 % (16.555) | crash mất tới khoảng 0,6 giây giao dịch đã báo thành công (không hỏng dữ liệu). Hợp với log quét có thể phát lại; không hợp với tiền |
| Index projection partial (đã áp) | index nhỏ hơn 18 % trên bảng dùng chung; index của field ít DocType dùng nhỏ hơn một nửa | không đo được thay đổi thông lượng |
| 1 transaction cho cập nhật qua REST (đã áp) | quét +4–9 % | không |
| Partition theo ngày | vòng đời dữ liệu (DETACH, vacuum từng phần) | point get chậm 2–5 lần, tổng hợp chậm 2–3 lần, mất UNIQUE trên `name` (mục 7) |
| Bảng cột thay JSONB cho DocType lớn nhất | tạo đơn nhanh hơn tới 17 %, dòng nhỏ hơn 40 %, tổng hợp nhanh hơn 2 lần | mất zero-DDL cho DocType đó (mục 10) |

### 6.6 Cả hệ thống qua HTTP

Các phép đo trên dừng ở database. Bộ đo [scripts/bench/http](../scripts/bench/http/README.md) chạy chính kernel build từ repo này, Keycloak với realm của dự án (token thật, kiểm tra chữ ký và audience mỗi request), PostgreSQL và Redis, rồi dùng k6 đẩy vòng đời đơn của app Express qua REST: tạo đơn, submit (sinh Waybill và bút toán sổ cái), 4 lần quét đến khi giao (lần cuối ghi bút toán COD), 3 lần xem, 2 danh sách theo khách. Tổng cộng 11 request mỗi đơn, ở nhịp đơn/giây cố định, 60 giây mỗi nhịp. Mọi thứ chạy trên máy lần 2, kể cả k6, và database bắt đầu rỗng: đây là trần của tầng ứng dụng khi dữ liệu nằm trọn trong cache.

| Đơn/giây | Request/giây | Bỏ lượt | p99 tạo | p99 submit | p99 quét | p99 xem | CPU kernel | CPU PostgreSQL |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 1.059 | 0 | 27 ms | 34 ms | 18 ms | 6 ms | 3,1 lõi | 4,0 lõi |
| 300 | 3.267 | 0 | 25 ms | 32 ms | 23 ms | 8 ms | 7,6 lõi | 5,6 lõi |
| **400** | **4.360** | **0** | 34 ms | 47 ms | 33 ms | 14 ms | 8,0 lõi | 9,1 lõi |
| 500 | 1.630 đạt được | 57 % | 5,2 s | 10,6 s | 5,0 s | 5,0 s | 5,2 lõi | 4,1 lõi |

**Trần của một máy, khi gánh cả kernel, database, Keycloak và bộ tạo tải, là khoảng 400–450 đơn/giây, gần 2 lần mức đỉnh mục tiêu.** Ở 500 đơn/giây máy hết CPU: các request xếp hàng chờ kết nối từ pool cho tới mức chờ 5 giây thì hỏng (độ trễ dừng đúng ở 5 s), nên thông lượng sụp chứ không giảm từ từ. Mỗi request tốn khoảng 1,8 ms CPU ở kernel (xác thực token, Jackson, hook, kiểm quyền) và 2,1 ms CPU ở PostgreSQL; kernel không giữ trạng thái nên phần của nó scale bằng thêm instance, còn phần database là một primary.

**Lỗi tìm ra nhờ bài đo này, đã sửa:** header `X-Tx-LSN` (vị trí WAL trả về sau mỗi lần ghi, cho read-your-writes qua replica) được đọc bằng một kết nối thứ hai mượn từ pool sau khi giao dịch đã trả kết nối của nó. Mỗi request ghi vì thế cần hai lần lấy kết nối, và khi pool căng, các request ghi chờ lẫn nhau. Trước khi sửa, hệ thống sụp ngay ở 400 đơn/giây (đạt 147 đơn/giây, 9.248 lỗi "acquisition timeout"); sau khi đọc vị trí WAL trên chính kết nối vừa commit, cùng nhịp đó chạy sạch như bảng trên.

## 7. Partition: được gì, mất gì

### 7.1 Partition theo ngày trong chuỗi đo gốc

Bảng `astra_doc_part` là bảng kernel partition RANGE theo `created_at` từng ngày, đủ index như bảng gốc.

| | Kết quả đo |
| --- | --- |
| Point lookup theo `name` (Q1) | 0,21 / 0,61 ms so với 0,07 / 0,12 ms: không có `created_at` trong WHERE nên dò 5 partition |
| Lọc một ngày (Q6) | 322 / 545 ms so với 534 / 766 ms baseline không index; index `created_at` trên bảng đơn cho 1–2 ms, tốt hơn partition |
| Tổng hợp một ngày (Q7) | 1.944 / 3.866 ms so với 863 / 1.182 ms: tệ hơn |
| Ràng buộc unique | phải chứa khoá partition: `UNIQUE (tenant_id, doctype, name, created_at)`; tính duy nhất của `name` **không còn được PostgreSQL bảo đảm** |
| Múi giờ | partition theo ngày UTC, nghiệp vụ theo +07, mọi truy vấn "một ngày" chạm 2 partition |

### 7.2 Partition theo tháng như kernel làm (18 tháng dữ liệu)

Kernel nay có `partition: month` cho DocType `kind: transaction`: tài liệu nằm ở `astra_doc_txn`, partition theo tháng của `created_at` với ranh giới theo múi giờ nghiệp vụ (`astra.storage.partition-time-zone`), partition được tạo trước 2 tháng và có partition mặc định đỡ mọi dòng ngoài khoảng; dữ liệu cũ của DocType vừa bật được chuyển vào đúng tháng lúc khởi động; tên do người dùng chọn được kiểm tra trùng xuyên tháng dưới advisory lock (khoá unique của PostgreSQL chỉ trong một tháng). Đo bằng cùng đơn và vận đơn chép vào 18 partition, ngày tạo trải đều từ 2025-05 đến 2026-10 (7,6 triệu doc), 64 client:

| Giao dịch/giây | `astra_doc` | `astra_doc_txn`, 18 tháng | + cận dưới theo ngày trong tên |
| --- | --- | --- | --- |
| Lấy theo tên | 33.787 | 13.797 | 17.780 |
| Danh sách theo khách | 33.292 | 8.747 | không áp dụng |
| Danh sách theo trạng thái | 25.455 | 7.952 | không áp dụng |
| Tạo đơn | 8.826 | **9.941** | |
| Quét Waybill | 8.416 | 3.619 | 3.999 |
| Tải hỗn hợp | 14.306 | 6.451 | 8.294 |

Lấy một đơn theo tên trên 18 partition dò 18 index (21 ms trong plan, so với 1,3 ms trên bảng đơn); danh sách theo khách mở 18 index rồi trộn theo `modified_at`. Kernel thêm `created_at >= <ngày trong tên> - 1 ngày` khi tên đến từ series có ngày (`ORD-.YYYYMMDD.-`, `WB-.YYMM.-`): một tài liệu không bao giờ được tạo trước ngày trong tên của nó (bản sửa đổi `-1` còn tạo muộn hơn), nên các tháng cũ hơn bị bỏ qua lúc chạy (0,6 ms trong plan cho một đơn giữa kỳ). Trong phép đo, tên được chọn đều trên 18 tháng nên trung bình vẫn dò một nửa; hệ thống thật tra cứu chủ yếu đơn đang xử lý, chỉ một hai partition. Danh sách theo khách hay trạng thái không có ngày nên không thu hẹp được. Chỉ việc tạo đơn nhanh hơn, vì index của tháng hiện tại nhỏ.

**Quyết định:** `partition: month` có trong kernel nhưng **tắt cho CustomerOrder và Waybill**. Với mục tiêu thông lượng, một bảng với index đúng thắng rõ. Partition là công cụ vòng đời: vacuum từng tháng, `DETACH` rồi archive tháng cũ trong một lệnh thay vì xoá hàng trăm triệu dòng. Bật nó khi những việc đó trở thành vấn đề (bảng vài trăm GB), và giữ số partition đang gắn ít (archive sau vài tháng), vì mỗi tra cứu không có ngày phải dò mọi partition đang gắn.

## 8. Thay đổi đã làm trong kernel

| # | Thay đổi | Tác động đo được |
| --- | --- | --- |
| 1 | Hàm `astra_ext.astra_field(jsonb, text)` `LANGUAGE internal LEAKPROOF` (schema riêng của superuser) trong mọi script bootstrap ([deploy/db/05-astra-field.sql](../deploy/db/05-astra-field.sql) cho database đã có, qua `scripts/db-setup.sh`); `RowLevelSecurityGuard` kiểm tra hàm tồn tại và `proleakproof = true` lúc khởi động, từ chối khởi động nếu thiếu | Q2 470–867 ms xuống 0,13–0,29 ms; đọc đồng thời 16 kết nối trong cùng điều kiện đo từ 14 lên 6.294 giao dịch/giây (bảng cột 6.470) |
| 2 | `ProjectionIndexer` tạo `(tenant_id, doctype, astra_ext.astra_field(data, f), modified_at DESC) WHERE ... IS NOT NULL`, dựng lại index hỏng (invalid) thay vì bỏ qua, gỡ index `->>` cũ; `DocumentStore.list` lọc bằng `astra_field` | Q3b 5–10 ms xuống 0,2–0,4 ms; index nhỏ hơn 18 % |
| 3 | Index `(tenant_id, doctype, created_at DESC)` tạo online lúc khởi động | Q6 534–766 ms xuống 1–2 ms |
| 4 | Migration V6: bỏ `astra_doc_data_gin`; autovacuum riêng cho `astra_doc` (1 %, không throttle) | tải hỗn hợp +40 %, bớt 1 GB/6 triệu doc |
| 5 | Naming series `gapless: false` (SPI `Naming`, `SeriesService`): mỗi instance lấy số từ khối dự trữ đã commit riêng, nạp lại khối ở nền; mặc định vẫn gapless. CustomerOrder `ORD-.YYYYMMDD.-.#######` và Waybill `WB-.YYMM.-.########` dùng chế độ này, đủ chữ số cho 2 triệu đơn/ngày (5 chữ số cũ chỉ đủ 99.999 đơn/ngày/tenant) | tạo đơn 291–425 lên 8.868/giây; tải hỗn hợp 7.145 lên 14.522 |
| 6 | Cập nhật qua REST: kiểm quyền (kể cả if-owner) chạy trong transaction cập nhật, không đọc doc hai lần | quét +4–9 % |
| 7 | Kiểm tra mọi Link của một doc bằng một câu lệnh | bớt N−1 round trip mỗi lần ghi doc có N Link |
| 8 | Phân trang keyset: `?after=<cursor>`, header `X-Next-Cursor`; `offset` vẫn dùng được | OFFSET 200.000: 200–250 ms xuống 0,2–0,3 ms |
| 9 | Pool kết nối chỉnh được (`ASTRA_DB_POOL_MIN`, `ASTRA_DB_POOL_MAX`) | trần đọc ở khoảng 2,7 kết nối mỗi luồng CPU của database |
| 10 | `projected: materialised`: cột kiểu thật do trigger tính, backfill ở nền, bộ lọc khoảng `__gt/__gte/__lt/__lte`, plan theo giá trị cho danh sách có lọc khoảng; `cod_amount` của CustomerOrder dùng nó | lọc khoảng COD: 16 lên 135/giây, p50 986 xuống 13 ms (mục 5.3) |
| 11 | `partition: month` (tuỳ chọn): bảng `astra_doc_txn` theo tháng, tạo partition trước, chuyển dữ liệu cũ, kiểm tra tên trùng xuyên tháng, thu hẹp partition theo ngày trong tên series | có sẵn cho vòng đời dữ liệu; tắt mặc định vì giảm OLTP một nửa (mục 7.2) |
| 12 | `X-Tx-LSN` đọc trên chính kết nối vừa commit | HTTP: 400 đơn/giây từ sụp (147 đạt được) thành sạch (mục 6.6) |
| 13 | Hàm `astra_ext.tenants()` của superuser: kernel liệt kê tenant để backfill và chuyển dữ liệu, dù RLS che các tenant khác | điều kiện của 10 và 11 |

Test mới: `DocListTest` (con trỏ đi qua mọi doc đúng một lần theo thứ tự; bộ lọc projection là Index Cond dưới RLS, còn `->>` là Filter), `SeriesBlockTest` (300 đơn tạo đồng thời có số duy nhất, đúng 7 chữ số), `StorageLayoutTest` (cột materialised và index của nó dưới RLS, backfill, tài liệu tháng cũ tìm được theo tên và qua Link, tên trùng xuyên tháng bị chặn, dữ liệu cũ chuyển vào đúng tháng), và khẳng định định nghĩa index trong `ExpressFlowTest`. Toàn bộ 84 test của `astra-server` qua.

Còn lại, chưa làm:

- **Archive tháng cũ** của `astra_doc_txn` (`DETACH PARTITION` rồi chuyển sang lưu trữ rẻ) như một job của kernel; hiện làm bằng tay.
- **CPU của kernel**: khoảng 1,8 ms mỗi request (xác thực token, Jackson, hook). Chưa profile; cache khoá công khai JWT, giảm chép `Map` giữa các lớp là chỗ nên xem trước.
- Tách field nóng: trạng thái đổi nhiều lần đã nằm ở Waybill hẹp; `scan_log` trong Waybill lớn dần theo mỗi lần quét, nên doc nên giữ dưới 2 KB (TOAST) hoặc chuyển log sang bảng append-only.

## 9. Hạn chế còn lại sau khi sửa

- **Dung lượng**: 505–549 so với 300–310 byte/dòng. Khoảng 400 GB/năm heap cho 730 triệu đơn so với 220 GB.
- **Mọi `SELECT *` trả cả `data`**: một list 20 đơn trả 20 × 417 byte JSON rồi Jackson parse. Ở hàng chục nghìn request/giây đó là CPU đáng kể ở kernel; `list` nên chiếu `data` theo field `in-list`.
- **Update ghi lại cả doc**: không có "update một field". Doc càng lớn (child table, `scan_log`) càng đắt.
- **Kiểu dữ liệu**: JSON chỉ có number/string/bool; so sánh ngày, tiền đều qua cast không leakproof, nên **mọi field cần lọc khoảng phải materialised**. Expression index chỉ đủ cho đẳng thức text.
- **Không có ràng buộc kiểu, NOT NULL, FK ở tầng database** cho field; mọi thứ ở `DocumentValidator` và kiểm tra Link.
- **LIKE/ILIKE không leakproof**: tìm kiếm chuỗi con dưới RLS luôn là filter; tìm kiếm phải đi qua search index.
- **PostgreSQL managed không dùng được** (cần superuser cho `LEAKPROOF`, mục 4.2).

## 10. Khả thi cho 2 triệu đơn/ngày và 10 triệu user

**Tải quy đổi.** 2 triệu đơn/ngày là 23 đơn/giây trung bình, 231 đơn/giây giờ cao điểm (gấp 10). Mỗi đơn khoảng 8 lần quét: khoảng 1.850 lần ghi/giây cao điểm. 10 triệu user, giả định 5 % hoạt động mỗi ngày và 20 request mỗi phiên, là 10 triệu đọc/ngày, khoảng 1.200 đọc/giây cao điểm cho phía khách; cộng nhân viên vận hành và bưu tá, mô hình tải hỗn hợp ở mục 6.3 tính khoảng 5.000 đọc/giây. Tổng **khoảng 7.200 giao dịch database/giây** giờ cao điểm.

**Năng lực đo được, một primary, máy lần 2** (Xeon 2014, Docker Desktop, pgbench cùng máy):

| | Kernel trước sửa | Kernel sau sửa | Mục tiêu đỉnh |
| --- | --- | --- | --- |
| Tạo đơn tối đa | 230 đơn/giây | 468 đơn/giây trong tải hỗn hợp; 8.868/giây khi chỉ tạo | 231 |
| Tải hỗn hợp tối đa, chạy hết sức | 7.145 giao dịch/giây | 14.522 | 7.200 |
| Nhịp cố định duy trì được | 7.200 (p99 tạo đơn 316 ms); không theo kịp ở 10.800 | 7.200 (trung vị 3 ms); tụt lại ở 10.800 | 7.200 |
| Đọc tối đa | 14 giao dịch/giây (lọc theo field) | 32.700 | khoảng 5.000 |
| **Cả hệ thống qua HTTP, một máy** | sụp ở 400 đơn/giây | **400 đơn/giây sạch**, sụp ở 500 khi hết CPU | 231 đơn/giây |

**Kết luận: khả thi trên một primary PostgreSQL cỡ vừa cộng replica đọc**, với các thay đổi ở mục 8 và:

1. Server database Linux thật với NVMe cục bộ; đo bằng bộ đo này trước khi chọn cấu hình (`PROFILE=auto` để tìm trần, `fixed16` để so phần cứng).
2. Đọc scale ngang bằng replica (streaming replication); 10 triệu user chủ yếu là tải đọc.
3. Partition `astra_doc` theo tháng, archive sau 12–18 tháng; khi dữ liệu vượt RAM, phần đơn đang xử lý (vài ngày gần nhất, khoảng 10 GB) vẫn phải nằm trong cache: cỡ RAM database theo tập nóng, không theo tổng dữ liệu.
4. Báo cáo, đối soát, dashboard trên `replica_*` và `astra_ledger`; theo dõi độ trễ CDC (`_cdc_sync_watermark.lag_ms`).
5. Tổng kết nối từ mọi instance kernel khoảng 2–3 lần số luồng CPU database; dùng pool phía kernel, hoặc PgBouncer ở chế độ transaction (kernel luôn gán tenant trong `BEGIN` tường minh).
6. Tầng kernel tốn khoảng 1,8 ms CPU mỗi request: 2.540 request/giây giờ cao điểm cần khoảng 5 lõi cho kernel, chia trên 2–3 pod để còn dư; database cần khoảng 2,1 ms CPU mỗi request, tức khoảng 6 lõi giờ cao điểm cộng phần dự phòng.

**Có nên dùng bảng cột riêng cho CustomerOrder?** Sau khi sửa, khác biệt đọc dưới 0,1 ms và dưới 8 % thông lượng; tạo đơn chậm hơn tới 17 %; tổng hợp chậm 2 lần (đã chuyển sang replica); dung lượng gấp 1,7 lần. Chuyển một hai DocType khối lượng lớn nhất sang bảng cột (`storage: ledger`-like, overlay chỉ thêm vào cột `extra jsonb`) đáng cân nhắc vì **dung lượng**, không vì tốc độ. Đó là quyết định sản phẩm.

## 11. Tái lập

Trên một máy chủ Linux có Docker (xem [README của bộ đo](../scripts/bench/doctype-jsonb/README.md)):

```bash
cd scripts/bench/doctype-jsonb
DATA_DIR=/mnt/nvme/astra-bench BENCH_NOTE="<loại máy, đĩa, vùng>" ./bench.sh   # khoảng 2,5 giờ
./compare.sh                                                                     # so mọi lần chạy trong results/
```

Cả hệ thống qua HTTP (kernel build từ repo, Keycloak, PostgreSQL, Redis, k6):

```bash
cd scripts/bench/http
RATES="100 200 300 400 500" BENCH_NOTE="<loại máy>" ./run.sh
```

Mỗi lần chạy ghi vào `results/<label>/`: thông tin máy, `pg_test_fsync`, mọi plan, mọi output pgbench, `metrics.csv` và `report.md`. Hai lần chạy trong tài liệu này nằm ở `results/m1pro-10c-16g-docker-20261022/` và `results/xeon-e5-2678v3-24t-docker-wsl2-20261023/`.
