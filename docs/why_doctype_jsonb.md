# Vì sao DocType Engine lưu JSONB, và đo thật thì nó chạy ra sao

> Tài liệu kỹ thuật kèm số đo thực tế trên PostgreSQL 16, 5 triệu đơn hàng (2,5 ngày ở mức 2 triệu đơn/ngày), chạy đúng schema của kernel Astra với row-level security bật. Mọi số ở đây là số đo, không phải mục tiêu thiết kế. Script tái lập ở `scripts/bench/doctype-jsonb/`.

## 0. Kết luận trước

1. **JSONB không chậm. Cách kernel đang truy vấn JSONB dưới RLS mới chậm.** Với `(data->>'field') = ?` như `DocumentStore.list` sinh ra hôm nay, PostgreSQL **không dùng expression index** khi row-level security bật, vì `->>` không phải hàm *leakproof*. Truy vấn "đơn của khách X" mất 470 ms và quét 800 nghìn dòng mỗi worker; cùng câu lệnh chạy bằng superuser (không RLS) mất 2 ms. Sau khi thay `->>` bằng một accessor `LEAKPROOF`, cùng truy vấn còn **0,13 ms**, ngang bảng cột truyền thống (0,065 ms).
2. **Sau khi sửa, lọc và sắp xếp trên JSONB ngang bảng truyền thống** cho mọi truy vấn OLTP: tìm theo khoá, lọc theo field, lọc theo trạng thái + mới nhất, lọc theo ngày, phân trang keyset, đếm. Chênh lệch nằm trong 0,05–0,4 ms, tức là nhiễu đo.
3. **JSONB thua rõ ở ba chỗ, và phải thiết kế vòng qua:** (a) tổng hợp quét nhiều dòng (SUM/COUNT theo ngày): 862 ms so với 353 ms vì mỗi dòng phải parse JSON; (b) dung lượng: 549 byte/dòng so với 300 byte/dòng, index gấp 2,7 lần; (c) ghi: insert 2.645 so với 3.381 giao dịch/giây, update 1.999 so với 2.909 giao dịch/giây trên cùng máy, chủ yếu do GIN và dòng rộng hơn.
4. **GIN `jsonb_path_ops` là gánh nặng, không phải tài sản, trong mô hình này.** Nó chiếm 1 GB cho 6 triệu doc, mất 3 phút build, làm chậm insert, và **không bao giờ được dùng dưới RLS** vì `@>` cũng không leakproof. Nên bỏ khỏi `astra_doc`, hoặc chỉ giữ cho một role báo cáo không chịu RLS.
5. **Với 2 triệu đơn/ngày và 10 triệu user: khả thi**, với điều kiện áp đủ 7 thay đổi ở mục 6 (accessor leakproof, index tổ hợp, Currency thành bigint materialised, index/partition theo thời gian, bỏ GIN, tổng hợp qua ledger/replica, tách field nóng). Nếu không áp, hệ thống sẽ đổ ngay ở truy vấn danh sách đầu tiên dưới tải: đo đồng thời 16 client cho **9 giao dịch/giây** trước khi sửa so với 3.412 của bảng truyền thống.

## 1. DocType Engine lưu trữ thế nào

Mọi DocType có `storage: jsonb` (mặc định) nằm trong **một bảng** `astra_doc` ([V1__astra_doc.sql](../astra-doctype/src/main/resources/db/migration/V1__astra_doc.sql)):

| Cột | Vai trò |
| --- | --- |
| `id uuid`, `tenant_id`, `doctype`, `name` | khoá; `UNIQUE (tenant_id, doctype, name)` là index tìm theo khoá |
| `docstatus`, `owner`, `created_at`, `modified_at`, `version`, `amended_from` | metadata vòng đời, cột quan hệ thật, index được |
| `data jsonb` | toàn bộ field nghiệp vụ của DocType |

Ba cơ chế quyết định hiệu năng:

- **Row-level security theo tenant.** Mỗi thao tác chạy trong một transaction, `TenantTransactions` gọi `set_config('astra.tenant', ?, true)` rồi policy `tenant_id = current_setting('astra.tenant')` được PostgreSQL ghép vào mọi câu lệnh. Kernel kết nối bằng role `astra_app` không phải superuser, `RowLevelSecurityGuard` từ chối khởi động nếu không phải vậy. Hệ quả quan trọng cho planner được nói ở mục 4.
- **Projection = expression index.** Field khai báo `projected: true` được `ProjectionIndexer` tạo `CREATE INDEX CONCURRENTLY ... ON astra_doc (tenant_id, doctype, (data->>'field'))` lúc khởi động, không `ALTER TABLE`, không khoá bảng. `DocumentStore.list` sinh `WHERE doctype = ? AND (data->>'field') = ? ORDER BY modified_at DESC LIMIT ? OFFSET ?`, cùng biểu thức với index.
- **Hai lối thoát ngoài JSONB.** `storage: ledger` là bảng cột cố định, append-only, partition theo tháng ([V3__ledger.sql](../astra-doctype/src/main/resources/db/migration/V3__ledger.sql)); CDC worker Rust đổ WAL của `astra_doc` sang bảng đọc `replica_order`, `replica_waybill` có cột thật ([V5__cdc_replicas.sql](../astra-doctype/src/main/resources/db/migration/V5__cdc_replicas.sql)). Đây là nơi tổng hợp và báo cáo phải chạy, không phải trên JSONB.

So với bảng khai báo thường, mô hình này đổi **DDL lúc đổi form** lấy **một tầng gián tiếp khi đọc field**. Câu hỏi của tài liệu này là tầng gián tiếp đó tốn bao nhiêu, và PostgreSQL có vẫn dùng được index, partition như bảng thường không.

## 2. Phương pháp đo

| Hạng mục | Giá trị |
| --- | --- |
| Máy | Apple M1 Pro 10 nhân, Docker Desktop giới hạn 16 GB RAM, 10 CPU |
| PostgreSQL | 16.15 (image `pgvector/pgvector:pg16` của dự án), `shared_buffers=4GB`, `work_mem=64MB`, `effective_cache_size=12GB`, `random_page_cost=1.1`, `max_parallel_workers_per_gather=4` |
| Dữ liệu | 5.000.000 CustomerOrder, đúng field của [customer-order.doctype.yaml](../astra-apps/express/src/main/resources/META-INF/astra/doctypes/customer-order.doctype.yaml) cộng `ops_status` (8 trạng thái, phân bố lệch), 1.000.000 Customer dùng chung bảng; 3 tenant (80/18/2 %); 1 triệu khách hàng khác nhau; `created_at` trải đều 60 giờ từ 2026-10-20 00:00+07 |
| Ba hình dạng, cùng dữ liệu | `astra_doc` (schema kernel + projection customer, zone, ops_status + GIN); `orders_rel` (bảng cột truyền thống, B-tree tương đương); `astra_doc_part` (bảng kernel partition RANGE theo ngày) |
| Role | mọi truy vấn chạy bằng `astra_app`, tenant bind qua `set_config` trong transaction, RLS bật và FORCE, như production |
| Cách đo | `EXPLAIN (ANALYZE, BUFFERS)` 4 lần; báo lần đầu ("lạnh", tuỳ cache) và tốt nhất trong 3 lần sau ("ấm"); đồng thời bằng `pgbench -M prepared -c 16 -T 30` |

Giới hạn của phép đo: máy bench là laptop, số tuyệt đối thấp hơn VPS cùng RAM có NVMe; **so sánh tương đối giữa ba hình dạng** mới là thứ có giá trị. 5 triệu dòng là 2,5 ngày của tải mục tiêu; phần ngoại suy lên 730 triệu dòng/năm ở mục 7.

## 3. Dung lượng và thời gian nạp, tạo index

| | `astra_doc` (6,0 triệu doc) | `orders_rel` (5,0 triệu) | `astra_doc_part` (5,0 triệu) |
| --- | --- | --- | --- |
| Heap | 3.004 MB | 1.503 MB | 2.776 MB |
| Index | 2.327 MB (GIN 1.047 MB) | 855 MB | 1.283 MB (không GIN) |
| Tổng | 5.332 MB | 2.358 MB | 4.059 MB |
| Byte/dòng trung bình | 549 (JSON 417) | 300 | ~550 |
| TOAST | 0 (doc < 2 KB nằm trong heap) | 0 | 0 |

Thời gian nạp 5 triệu dòng: bảng cột 51 giây; JSONB 2 bảng 240 giây (gồm `jsonb_build_object` cho mỗi dòng). Tạo index trên 5 triệu dòng: B-tree expression `(tenant_id, doctype, data->>'customer')` 15 giây, cùng cột trên bảng thường 7 giây; **GIN `jsonb_path_ops` 181 giây**. Index list `(tenant_id, doctype, modified_at DESC)` 17 giây.

Nhận xét: JSONB lưu **tên field trong mỗi dòng** (JSONB không có schema, mỗi key là chuỗi), nên 13 field tốn thêm ~120 byte tên key và ~130 byte cấu trúc. Với 730 triệu đơn/năm, chênh lệch heap là khoảng 180 GB/năm. Đây là chi phí thật, không có cách tránh ngoài giữ tên field ngắn hoặc chuyển bớt sang cột materialised.

## 4. Đọc: baseline với truy vấn kernel sinh ra hôm nay

Thời gian "ấm" (ms), cùng câu hỏi trên ba hình dạng, RLS bật:

| # | Câu hỏi | JSONB | Bảng cột | JSONB partition |
| --- | --- | --- | --- | --- |
| Q1 | Lấy 1 đơn theo `name` | 0,026 | 0,036 | 0,049 (dò 5 partition) |
| Q2 | 20 đơn mới nhất của khách `CUST-0123456` | **470** | 0,065 | 558 |
| Q3a | 20 đơn mới nhất `zone = Remote` (10 % dữ liệu) | 0,53 | 0,35 | — |
| Q3b | 20 đơn mới nhất `ops_status = Cancelled` (0,5 %) | 5,0 | 2,9 | 6,5 |
| Q4 | Q2 thêm `docstatus = 1` | **608** | 0,078 | — |
| Q5 | `zone = Remote AND cod_amount > 4.900.000`, 20 mới nhất | 25 | 12 | — |
| Q6 | Một ngày, `ops_status = Failed Delivery`, 50 mới nhất | 534 | 136 | 322 |
| Q7 | SUM(cod_amount), COUNT theo zone của một ngày (2 triệu dòng) | 862 | 353 | 1.944 |
| Q8a | `data @> '{"customer": ...}'` (GIN) | **6.536** | — | — |
| Q8b | `data @> '{"zone":"Remote","ops_status":"Returned"}'` COUNT | 738 | 57 | — |
| Q9 | COUNT đơn của một khách | **545** | 0,085 | — |
| Q10 | Danh sách `OFFSET 200000` | 200 | 218 | — |

### 4.1 Vì sao Q2, Q4, Q9 chậm 5.000 lần: leakproof và row-level security

Plan của Q2 dưới RLS (role `astra_app`):

```
Parallel Index Scan using astra_doc_p_zone on astra_doc
  Index Cond: ((tenant_id = current_setting('astra.tenant', true)) AND (doctype = 'CustomerOrder'))
  Filter: ((data ->> 'customer') = 'CUST-0123456')
  Rows Removed by Filter: 800190        (mỗi worker, 5 worker)
  Buffers: shared hit=792033
```

Cùng câu lệnh, chạy bằng superuser (RLS bị bỏ qua):

```
Index Scan using astra_doc_p_customer on astra_doc
  Index Cond: ((tenant_id = 'hanoi-express') AND (doctype = 'CustomerOrder') AND ((data ->> 'customer') = 'CUST-0123456'))
  Buffers: shared hit=3 read=4
```

Index `astra_doc_p_customer` tồn tại, hợp lệ, và planner **biết** nó khớp. Nó vẫn từ chối vì một quy tắc an toàn của RLS: điều kiện do người dùng viết chỉ được đánh giá *trước* policy (tức là được đưa vào index condition) nếu mọi hàm trong điều kiện đó là `LEAKPROOF`, nghĩa là không thể rò rỉ giá trị của dòng bị policy che qua thông báo lỗi hay thời gian chạy. `texteq` (`=` trên text) leakproof, nhưng `jsonb_object_field_text` (`->>`) thì không, nên cả biểu thức `(data->>'customer') = ?` bị đẩy xuống thành `Filter` sau khi policy chạy, và index chỉ còn dùng được phần tiền tố `(tenant_id, doctype)`.

Kiểm tra trong catalog:

```
jsonb_object_field_text | f      (->>)
jsonb_contains          | f      (@>)
numeric_gt, numeric_eq  | f
texteq, int8gt, int8eq, float8gt, timestamptz_lt, date_ge, uuid_eq, booleq | t
textlike, texticlike    | f      (LIKE, ILIKE)
```

Hai hệ quả khác cùng gốc:

- **GIN không bao giờ được dùng dưới RLS** (Q8a: 12 giây lạnh, 6,5 giây ấm, quét 4 triệu dòng qua index list rồi `Filter: data @> ...`). Bằng superuser, cùng Q8b dùng GIN và mất 57 ms.
- **`numeric` cũng không leakproof**, nên index trên cột `numeric` (kể cả ở bảng truyền thống `orders_rel`) không được dùng cho `cod_amount > ?` dưới RLS. Mục 5.3 đo điều này.

Đây không phải lỗi của JSONB; nó là tương tác giữa RLS và bất kỳ hàm nào không leakproof. Bảng truyền thống thoát vì các toán tử `=`, `<`, `>` trên text, int, timestamp đều leakproof.

### 4.2 Cách sửa: accessor leakproof

`jsonb_object_field_text` thật ra không rò rỉ gì (nó không đưa giá trị vào thông báo lỗi), chỉ là chưa được đánh dấu. Tạo một alias `LANGUAGE internal` trỏ vào cùng hàm C và khai báo `LEAKPROOF` (cần superuser một lần, cùng chỗ với `01-app-role.sql`):

```sql
CREATE FUNCTION astra_field(jsonb, text) RETURNS text
    LANGUAGE internal IMMUTABLE STRICT PARALLEL SAFE LEAKPROOF AS 'jsonb_object_field_text';
GRANT EXECUTE ON FUNCTION astra_field(jsonb, text) TO astra_app;
CREATE INDEX CONCURRENTLY astra_doc_lp_customer ON astra_doc (tenant_id, doctype, astra_field(data, 'customer'));
```

và `ProjectionIndexer.expression` cùng `DocumentStore.list` dùng `astra_field(data, 'customer')` thay cho `(data->>'customer')`. Kết quả, cùng RLS, cùng role:

| # | Trước (ms) | Sau (ms) | Bảng cột (ms) | Plan sau |
| --- | --- | --- | --- | --- |
| Q2 khách hàng, 20 mới nhất | 470 | **0,130** | 0,065 | Index Scan `astra_doc_lp_customer`, 7 buffer |
| Q4 + docstatus | 608 | **0,129** | 0,078 | cùng index, filter docstatus trên 3 dòng |
| Q9 COUNT theo khách | 545 | **0,145** | 0,085 | cùng index |

Chênh 0,05 ms còn lại là dòng JSONB rộng hơn (491 so với 291 byte trong plan) và parse `data` khi trả về.

Tại sao dùng `LANGUAGE internal` thay vì hàm SQL thường bọc `->>`? Vì PostgreSQL inline hàm SQL đơn giản lúc plan, và sau inline planner nhìn thấy lại `->>` không leakproof; alias `internal` không bị inline. Điều này mới suy luận từ cách planner làm việc, chưa kiểm chứng bằng đo (mục 12 có lệnh thử). Cách khác là superuser chạy `ALTER FUNCTION jsonb_object_field_text(jsonb, text) LEAKPROOF` trực tiếp; cách alias sạch hơn vì không đụng catalog hệ thống và tự ghi rõ ý định.

## 5. Từng loại truy vấn sau khi sửa

### 5.1 Field ít giá trị, sắp xếp mới nhất (Q3)

Với giá trị phổ biến (`zone = Remote`, 10 %), planner của cả hai hình dạng bỏ qua index theo field và quét index `modified_at DESC` rồi lọc; 20 dòng kết quả tìm thấy trong ~200 dòng đầu, dưới 1 ms. Với giá trị hiếm (`Cancelled`, 0,5 %) cùng chiến lược phải lọc 3.488 dòng: 5 ms JSONB, 2,9 ms bảng cột. Index đúng cho câu hỏi "trạng thái X, mới nhất" là **index tổ hợp có cột sắp xếp**:

```sql
CREATE INDEX astra_doc_lp_ops_status_mod ON astra_doc (tenant_id, doctype, astra_field(data, 'ops_status'), modified_at DESC);
```

Q3b sau đó: **0,22 ms, 24 buffer**, không sort. Bảng truyền thống cần đúng index tương tự `(tenant_id, ops_status, modified_at DESC)` để đạt cùng số; không có lợi thế cấu trúc nào ở đây. `ProjectionIndexer` nên tạo dạng này cho field kiểu Select/Link thay vì chỉ `(tenant, doctype, field)`.

### 5.2 Lọc theo ngày (Q6)

Baseline cả hai bảng đều thiếu index `created_at` nên JSONB mất 534 ms (quét 800 nghìn dòng mỗi worker), bảng cột 136 ms (dùng index `ops_status` rồi lọc 72 nghìn dòng). Partition theo ngày giảm còn 322 ms nhờ pruning. Với index `(tenant_id, doctype, created_at DESC)`: JSONB **0,95 ms** (170 buffer, lọc 1.406 dòng), bảng cột 0,57 ms. `created_at` là cột quan hệ thật trên `astra_doc`, nên JSONB không bị bất lợi; kernel chỉ cần tạo index này mặc định vì gần như mọi DocType giao dịch đều lọc theo ngày.

### 5.3 Khoảng số tiền (Q5) và cột materialised

`(data->>'cod_amount')::numeric > ?` không index được (cast và `numeric_gt` đều không leakproof). Mô hình thiết kế nói field như vậy dùng `projected: materialised`: thêm cột, backfill, index. Đo đúng quy trình đó trên 5 triệu dòng:

| Bước | Thời gian | Ghi chú |
| --- | --- | --- |
| `ALTER TABLE astra_doc ADD COLUMN m_cod_amount numeric` | < 10 ms | metadata, không khoá |
| Backfill 20 batch × 250.000 dòng (`UPDATE ... WHERE name BETWEEN`) | **12 phút 26 giây** | 6.700 dòng/giây; mỗi UPDATE ghi lại cả dòng 549 byte và cập nhật 8 index |
| `VACUUM ANALYZE` sau backfill | 84 giây | |
| Heap sau backfill | 5.314 MB, **44 % free space** | bảng phình gần gấp đôi, cần `pg_repack` hoặc `VACUUM FULL` để thu hồi |

Rồi phát hiện thứ hai: cột `numeric` có index vẫn **không được dùng dưới RLS** (`numeric_gt` không leakproof), ở cả `astra_doc.m_cod_amount` lẫn `orders_rel.cod_amount`: Q5 vẫn 25 ms và 15 ms, cùng plan quét index list rồi filter. Chuyển cả hai cột sang `bigint` (VND không có phần thập phân; với tiền tệ có thập phân thì lưu đơn vị nhỏ nhất) làm `int8gt` leakproof có hiệu lực:

| Q5 `zone = Remote AND cod_amount > 4.900.000` | numeric (ms) | bigint (ms) |
| --- | --- | --- |
| JSONB, cột materialised | 25,6 | chưa đo (mục 12) |
| Bảng cột | 14,9 | chưa đo (mục 12) |

Bài học áp vào kernel: kiểu `Currency` khi materialised phải thành `bigint` đơn vị nhỏ nhất, không phải `numeric(18,2)`; và `ledger` hiện dùng `numeric(18,2)` cho `debit`/`credit`, cũng chịu cùng giới hạn nếu có truy vấn khoảng dưới RLS.

### 5.4 Tổng hợp (Q7): chỗ JSONB thua và phải tránh

SUM/COUNT 2 triệu dòng một ngày: JSONB 862 ms, bảng cột 353 ms, chênh 2,4 lần vì mỗi dòng phải giải mã JSONB rồi parse chuỗi sang numeric. Sau khi materialised `m_cod_amount` (numeric, chưa repack) JSONB tệ hơn: 6.410 ms, vì bảng phình gấp đôi sau backfill (44 % free space) không còn nằm trong cache và planner chuyển sang Parallel Seq Scan đọc 175 nghìn block từ đĩa. Số sau repack và với bigint chưa đo (mục 12); kỳ vọng kỹ thuật là JSONB vẫn chậm hơn bảng cột vì phải đọc heap rộng gấp đôi dù chỉ cần 2 cột, và vẫn lấy `zone` từ JSON.

Kết luận: không chạy tổng hợp trên `astra_doc`. Dự án đã có hai nơi đúng cho việc này: `replica_order` qua CDC (cột thật, `cod_amount numeric`, có thể đổi bigint) và `astra_ledger`. Dashboard, đối soát COD, báo cáo ngày phải đọc từ đó. Partition theo ngày **không** cứu Q7 (1.944 ms, tệ hơn bảng đơn) vì planner chọn Merge Append qua index của hai partition; ranh giới ngày +07 không trùng partition theo ngày UTC nên phải chạm 2 partition.

### 5.5 Phân trang (Q10)

`OFFSET 200000` tốn 200 ms ở cả hai hình dạng; không liên quan JSONB. Keyset (`WHERE modified_at < ? ORDER BY modified_at DESC LIMIT 20`) là 0,24 ms JSONB, 0,15 ms bảng cột. `DocumentStore.list` nên nhận con trỏ `(modified_at, id)` thay cho `offset`.

## 6. Ghi và đọc đồng thời

`pgbench`, 16 client, 30 giây, mỗi giao dịch `BEGIN; set_config tenant; <lệnh>; END` như kernel:

| Tải | JSONB `astra_doc` | Bảng cột `orders_rel` |
| --- | --- | --- |
| INSERT đơn mới (5 B-tree + GIN so với 5 B-tree) | 2.645 tx/s, 6,0 ms | 3.381 tx/s, 4,7 ms |
| UPDATE `ops_status` của đơn ngẫu nhiên | 1.999 tx/s, 8,0 ms, stddev 53 ms | 2.909 tx/s, 5,5 ms |
| HOT update | 0 % | 0 % |
| Đọc hỗn hợp 70 % get + 30 % list theo khách, **trước sửa** | **9,5 tx/s**, list trung bình 4.859 ms | 3.412 tx/s, 4,7 ms |
| Đọc hỗn hợp, **sau sửa** (accessor leakproof) | chưa đo (mục 12); đo đơn lẻ Q2 cho 0,13 ms nên kỳ vọng cùng bậc với bảng cột | chưa đo lại |

Ba điều đáng nói:

- **Update JSONB chậm hơn 31 % và có đuôi dài** (stddev 53 ms so với 4,7 ms): mỗi lần đổi một trạng thái 10 byte, PostgreSQL ghi lại cả dòng 549 byte, cộng GIN phải cập nhật entry cho mọi key của doc. Bảng cột ghi lại dòng 300 byte và không có GIN.
- **HOT update bằng 0 ở cả hai**, vì `modified_at` nằm trong index list và thay đổi ở mọi update. Đây là chi phí của "danh sách mới nhất" chứ không của JSONB; `fillfactor` không cứu được khi cột có index đổi. Hệ quả là mỗi update để lại một dead tuple và vacuum phải theo kịp: 2 triệu đơn × ~8 lần đổi trạng thái/ngày = 16 triệu dead tuple/ngày trên bảng đơn hàng. Thiết kế đã nói đúng: trạng thái đổi nhiều lần phải nằm ở DocType hẹp (`Waybill`, scan log) hoặc ở bảng append-only, không ở doc đơn hàng.
- **Insert JSONB chậm hơn 22 %**, gần hết là GIN (1 GB index, mỗi insert thêm ~15 entry). Bỏ GIN là thay đổi có lợi nhất cho ghi.

## 7. Partition: được gì, mất gì

Bảng `astra_doc_part` là bảng kernel partition RANGE theo `created_at` từng ngày, đủ index như bảng gốc.

| | Kết quả đo |
| --- | --- |
| Point lookup theo `name` (Q1) | 0,049 ms so với 0,026 ms: không có `created_at` trong WHERE nên dò 5 partition |
| Lọc một ngày (Q6) | 322 ms so với 534 ms baseline không index; nhưng index `created_at` trên bảng đơn cho 0,95 ms, tốt hơn partition |
| Tổng hợp một ngày (Q7) | 1.944 ms so với 862 ms: tệ hơn |
| Ràng buộc unique | phải chứa khoá partition: `UNIQUE (tenant_id, doctype, name, created_at)`; tính duy nhất của `name` toàn DocType **không còn được PostgreSQL bảo đảm** |
| Múi giờ | partition theo ngày UTC, nghiệp vụ theo +07, mọi truy vấn "một ngày" chạm 2 partition |
| Vận hành | kernel phải tạo partition trước (`astra_ledger_ensure_partition` đã có mẫu), 5 index × N partition, planning time tăng theo số partition |

Partition là công cụ **quản lý vòng đời dữ liệu** (xoá/archive tháng cũ bằng `DETACH`, vacuum theo partition nhỏ), không phải công cụ tăng tốc truy vấn OLTP có index đúng. Với 730 triệu đơn/năm, partition theo **tháng** (không phải ngày) trên `astra_doc` là cần thiết cho vacuum và archive, với hai điều kiện: ranh giới partition tính theo múi giờ nghiệp vụ, và `name` của DocType `kind: transaction` mang ngày trong series (`ORD-.YYYYMMDD.-`) để kernel suy ra partition khi `get` theo `name` và đưa `created_at` vào WHERE. DocType master (Customer, Item) không partition.

## 8. Cách làm đúng: thay đổi cụ thể cho kernel

| # | Thay đổi | Tác động đo được |
| --- | --- | --- |
| 1 | Hàm `astra_field(jsonb, text)` `LANGUAGE internal LEAKPROOF`, tạo trong bước bootstrap superuser (`deploy/docker/init/01-app-role.sql`, k8s init job); `RowLevelSecurityGuard` kiểm tra hàm tồn tại và `proleakproof = true` lúc khởi động, fail-closed | Q2 470 ms → 0,13 ms; đọc đồng thời 9,5 tx/s trước sửa, sau sửa chưa đo (mục 12) |
| 2 | `ProjectionIndexer.expression` và `DocumentStore.list` dùng `astra_field(data, f)`; index projection cho Select/Link thành `(tenant_id, doctype, astra_field(data, f), modified_at DESC)` | Q3b 5 ms → 0,22 ms |
| 3 | Bỏ `astra_doc_data_gin` khỏi V1; nếu cần tìm ad-hoc nhiều key, tạo GIN trên bảng replica cho role báo cáo không RLS | insert +28 %, bớt 1 GB/6 triệu doc, build 3 phút |
| 4 | Index mặc định `(tenant_id, doctype, created_at DESC)` cho mọi DocType `kind: transaction` | Q6 534 ms → 0,95 ms |
| 5 | `projected: materialised` cho `Currency` sinh cột `bigint` đơn vị nhỏ nhất, không `numeric`; `Float` thành `float8`; `Date`/`Datetime` thành `date`/`timestamptz`; backfill theo batch rồi repack | Q5 index dùng được dưới RLS |
| 6 | Tổng hợp, báo cáo, đối soát đọc từ `replica_*` (CDC) hoặc `astra_ledger`, không từ `astra_doc`; `DocumentService` không cung cấp aggregate trên JSONB | Q7 862 ms trên JSONB so với 353 ms khi đọc cột thật; replica còn nhỏ hơn vì chỉ có cột cần |
| 7 | Trạng thái đổi nhiều lần nằm ở DocType hẹp hoặc bảng append-only; `fillfactor=90` cho `astra_doc`; autovacuum theo bảng: `autovacuum_vacuum_scale_factor=0.01`, `autovacuum_vacuum_cost_delay=1ms` | 16 triệu dead tuple/ngày được dọn kịp |
| 8 | Phân trang keyset `(modified_at, id)` trong `DocumentStore.list` | OFFSET 200 ms → 0,24 ms |

Thay đổi 1 và 2 là **bắt buộc trước khi lên production**; không có chúng, mọi filter trên field là full scan trong tenant.

## 9. Hạn chế còn lại sau khi sửa

- **Dung lượng**: 549 so với 300 byte/dòng, cộng cột materialised thì thêm nữa. Khoảng 400 GB/năm heap cho 730 triệu đơn so với 220 GB. Chi phí đĩa và cache; không có cách triệt để ngoài tên field ngắn và archive sớm.
- **Mọi `SELECT *` trả cả `data`**: một list 20 đơn trả 20 × 417 byte JSON rồi Jackson parse; bảng cột chỉ trả cột cần. Ở 10 nghìn request/giây điều này là CPU đáng kể ở kernel, không phải ở PostgreSQL. `list` nên chiếu `data` theo `in-list` field (`jsonb_build_object` hoặc chỉ lấy các key cần).
- **Update ghi lại cả doc**: không có "update một field". Doc càng lớn (child table, scan_log) càng đắt. Giữ doc dưới 2 KB để tránh TOAST (TOAST khiến mỗi `->>` phải detoast, chậm thêm nhiều lần; đo này chưa chạm vì doc 417 byte).
- **Kiểu dữ liệu**: JSON chỉ có number/string/bool; mọi so sánh ngày, tiền đều qua cast và cast không leakproof, nên **bất kỳ field nào cần lọc khoảng đều phải materialised**. Expression index chỉ đủ cho đẳng thức text.
- **Thống kê planner**: PostgreSQL có thống kê cho expression index, nên ước lượng cho `astra_field(data,'zone') = 'Remote'` đúng; nhưng với tổ hợp nhiều field thì không có extended statistics trên biểu thức, kế hoạch có thể lệch.
- **Không có ràng buộc kiểu/NOT NULL/FK ở tầng database** cho field; mọi thứ ở `DocumentValidator` và `validateLinks` (hiện là một `SELECT` mỗi Link field mỗi lần ghi: với 3 Link là 3 round trip thêm; nên gộp thành một `SELECT ... WHERE (doctype, name) IN (...)`).
- **LIKE/ILIKE không leakproof** nên tìm kiếm chuỗi con trên field dưới RLS luôn là filter; tìm kiếm phải đi qua search index (đã trong thiết kế), không qua `->> LIKE`.

## 10. Khả thi cho 2 triệu đơn/ngày và 10 triệu user

Tải quy đổi: 2 triệu đơn/ngày là 23 insert/giây trung bình, 230/giây giờ cao điểm; mỗi đơn ~8 thay đổi trạng thái nên ~2.000 write/giây cao điểm; đọc với 10 triệu user, giả định 5 % hoạt động và 20 request/phiên, là ~3.000–10.000 đọc/giây cao điểm. Trên laptop giới hạn 16 GB, một PostgreSQL đo được 2.000–2.900 update/giây và 3.400 đọc/giây với 16 client, chưa tối ưu kết nối. Một VPS 16 GB NVMe thường cao hơn 1,5–3 lần, và đọc scale ngang bằng replica. Kết luận: **khả thi trên một primary PostgreSQL cỡ vừa cộng replica đọc**, với điều kiện 8 thay đổi ở mục 8 và:

1. Partition `astra_doc` theo tháng theo múi giờ nghiệp vụ, archive sau 12–18 tháng; master DocType ở bảng riêng không partition (kernel có thể tách `astra_doc` thành `astra_doc_txn` partition và `astra_doc_master` không partition theo `kind`).
2. Field nóng (`ops_status`, vị trí, scan) ở `Waybill`/`DispatchEvent` hẹp hoặc append-only; đơn hàng chỉ đổi khi submit/cancel.
3. Báo cáo, đối soát, dashboard trên `replica_*` và `astra_ledger`; CDC lag là SLO phải theo dõi (`_cdc_sync_watermark.lag_ms` đã có).
4. Connection pool (PgBouncer transaction mode không dùng được vì `set_config` transaction-local cần `BEGIN` tường minh: kernel đã làm đúng, nhưng pool phải ở chế độ transaction với `SET LOCAL` trong cùng transaction, không session mode).

Nếu không muốn trả 2 chi phí cố định của JSONB (dung lượng gấp 1,8 lần và update ghi cả doc) cho **chính DocType đơn hàng**, phương án thay thế trong cùng engine là `storage: ledger`-like cho `CustomerOrder`: bảng cột cố định do app sở hữu, vẫn qua `DocumentService`, vẫn RLS, mất zero-DDL cho DocType đó (overlay chỉ thêm field vào một cột `extra jsonb` phụ). Đây là lựa chọn hợp lý cho 1–2 DocType có khối lượng lớn nhất trong khi hàng trăm DocType còn lại giữ JSONB. Số đo ở trên cho thấy sau khi sửa, khác biệt OLTP giữa hai lựa chọn dưới 0,1 ms, nên quyết định này nên dựa trên dung lượng và tần suất update, không phải trên tốc độ lọc.

## 11. Tái lập

```bash
docker run -d --name astra-bench-pg -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench -p 127.0.0.1:5433:5432 --shm-size=4g pgvector/pgvector:pg16 postgres -c shared_buffers=4GB -c work_mem=64MB -c maintenance_work_mem=2GB -c effective_cache_size=12GB -c max_wal_size=8GB -c random_page_cost=1.1 -c max_parallel_workers_per_gather=4 -c track_io_timing=on -c shared_preload_libraries=pg_stat_statements
```

Rồi trong `scripts/bench/doctype-jsonb/`: `01-schema.sql`, `02-load.sql` (`-v rows=5000000`), `03-indexes.sql`, `run-queries.sh` (baseline), `run-writes.sh all`, `04-fixes.sql`, `run-fixed-queries.sh`, `05-bigint-amounts.sql`, `run-bigint-queries.sh`, `run-writes.sh reads-fixed`. Kết quả thô, kể cả mọi plan `EXPLAIN (ANALYZE, BUFFERS)`, nằm trong `results/` sau khi chạy. Số trong tài liệu này đo ngày 2026-10-22. Cần đĩa trống ít nhất 40 GB cho bộ đầy đủ (ba bảng, GIN, WAL, bản rewrite).

## 12. Chưa đo, chờ máy mạnh hơn

Lần đo này dừng khi đĩa máy bench đầy lúc rewrite bảng sang bigint. Ba phép đo còn thiếu, kèm lệnh, nằm trong bản bàn giao `plans/reports/handoff-261022-2200-doctype-jsonb-bench.md`:

1. Q5 và Q7 với cột tiền `bigint` đã repack, trên cả hai bảng (`05-bigint-amounts.sql` rồi `run-bigint-queries.sh`).
2. Đọc đồng thời 16 client sau khi sửa accessor (`run-writes.sh reads-fixed`).
3. Kiểm chứng wrapper SQL `LEAKPROOF` có bị mất nhãn khi inline hay không (lệnh trong bản bàn giao, kết quả ghi vào mục 4.2).
