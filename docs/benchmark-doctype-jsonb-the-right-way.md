# Benchmark DocType JSONB: giới hạn, cách làm đúng và đánh đổi

| | |
| --- | --- |
| Đối tượng | Kỹ sư, kiến trúc sư và trưởng nhóm kỹ thuật đánh giá hoặc vận hành Astra |
| Phạm vi | Lưu trữ DocType bằng JSONB trong PostgreSQL 16, dưới row-level security; toàn hệ thống qua HTTP |
| Dữ liệu | 5 triệu đơn hàng (2,5 ngày ở mức 2 triệu đơn/ngày), 1 triệu khách hàng, 3 tenant |
| Thời gian đo | 22–23/10/2026, hai máy (mục 2) |
| Tái lập | [scripts/bench/doctype-jsonb](../scripts/bench/doctype-jsonb/README.md), [scripts/bench/http](../scripts/bench/http/README.md) |

Mọi con số trong tài liệu là số đo thực tế, không phải mục tiêu thiết kế. Kết quả thô của từng lần chạy nằm trong thư mục `results/` của hai bộ đo.

## Tóm tắt

**Câu hỏi.** DocType Engine lưu mọi field nghiệp vụ trong một cột JSONB để thay đổi form mà không cần DDL. Tài liệu này kiểm chứng ba điều: cách lưu trữ đó tốn bao nhiêu so với một bảng quan hệ truyền thống, PostgreSQL có còn dùng được index và partition như với bảng thường không khi row-level security (RLS) bật, và toàn hệ thống có đáp ứng được 2 triệu đơn hàng mỗi ngày với 10 triệu người dùng không.

**Kết quả chính.**

1. **Dưới RLS, PostgreSQL không dùng index trên biểu thức JSONB.** Toán tử `->>` không được đánh dấu `LEAKPROOF`, nên mọi bộ lọc theo field quét toàn bộ dữ liệu của tenant: truy vấn "20 đơn mới nhất của một khách" mất 470–867 ms. Kernel nay dùng một accessor leakproof; cùng truy vấn còn 0,13–0,29 ms, ngang bảng quan hệ.
2. **Sau khi khắc phục, đọc trên JSONB tương đương bảng quan hệ**: chênh lệch dưới 0,1 ms mỗi truy vấn và 4–8 % thông lượng.
3. **Điểm nghẽn ghi nằm ở bộ đếm đánh số chứng từ, không ở JSONB.** Bộ đếm không hở số khoá một dòng đến khi giao dịch tạo đơn commit, giới hạn việc tạo đơn ở khoảng 300 đơn/giây mỗi tenant ở mọi mức đồng thời. Chế độ cấp số theo khối nâng con số này lên 8.868 đơn/giây và tăng gấp đôi thông lượng tải hỗn hợp.
4. **Toàn hệ thống qua HTTP** (kernel, xác thực Keycloak, PostgreSQL trên cùng một máy chủ) xử lý **400 đơn/giây** (4.360 request/giây, p99 dưới 47 ms), khoảng gấp đôi tải giờ cao điểm mục tiêu.
5. **JSONB có ba chi phí cố định**: dung lượng lớn hơn khoảng 70 %, truy vấn tổng hợp chậm hơn khoảng 2 lần, tạo đơn chậm hơn tới 17 % so với bảng quan hệ.
6. **Partition theo tháng giảm khoảng một nửa thông lượng giao dịch** (18 partition), vì tra cứu theo tên hay theo khách không chứa khoá partition. Tính năng có trong kernel nhưng là tuỳ chọn và không bật cho các DocType giao dịch chính.

**Kết luận.** Mục tiêu 2 triệu đơn/ngày và 10 triệu người dùng khả thi trên một primary PostgreSQL cỡ vừa cộng các bản sao đọc, với các thay đổi kernel trong mục 11 và khuyến nghị vận hành trong mục 10.

**Khuyến nghị.**

- Triển khai PostgreSQL tự quản (VM hoặc operator Kubernetes): accessor leakproof cần quyền superuser, mà các dịch vụ managed như RDS hay Cloud SQL không cấp.
- Dùng chế độ đánh số theo khối (`gapless: false`) cho chứng từ khối lượng lớn; giữ chế độ không hở số cho chứng từ kế toán.
- Chạy báo cáo và tổng hợp trên bảng replica hoặc sổ cái, không trên bảng tài liệu JSONB.
- Đo lại trên máy chủ Linux đích bằng bộ đo kèm theo trước khi chọn cấu hình phần cứng.

## 1. Mô hình lưu trữ DocType

Mọi DocType có `storage: jsonb` (mặc định) lưu trong một bảng chung `astra_doc` ([V1__astra_doc.sql](../astra-doctype/src/main/resources/db/migration/V1__astra_doc.sql)):

| Cột | Vai trò |
| --- | --- |
| `id`, `tenant_id`, `doctype`, `name` | Khoá; `UNIQUE (tenant_id, doctype, name)` phục vụ tra cứu theo tên |
| `docstatus`, `owner`, `created_at`, `modified_at`, `version`, `amended_from` | Metadata vòng đời, là cột quan hệ có thể đánh index |
| `data jsonb` | Toàn bộ field nghiệp vụ |

Ba cơ chế quyết định hiệu năng:

- **Cách ly tenant bằng row-level security.** Mỗi thao tác chạy trong một giao dịch gán tenant qua `set_config('astra.tenant', ?, true)`; policy `tenant_id = current_setting('astra.tenant')` được PostgreSQL áp vào mọi câu lệnh. Kernel kết nối bằng một role không phải superuser và từ chối khởi động nếu điều kiện này không đúng.
- **Projection.** Field khai báo `projected: true` được đánh index theo biểu thức, tạo online lúc khởi động mà không cần `ALTER TABLE`. Truy vấn danh sách dùng đúng biểu thức đó.
- **Lưu trữ ngoài JSONB.** `storage: ledger` là bảng quan hệ cố định, chỉ ghi thêm, partition theo tháng ([V3__ledger.sql](../astra-doctype/src/main/resources/db/migration/V3__ledger.sql)). Worker CDC đồng bộ `astra_doc` sang các bảng đọc có cột thật như `replica_order` ([V5__cdc_replicas.sql](../astra-doctype/src/main/resources/db/migration/V5__cdc_replicas.sql)).

So với bảng khai báo cố định, mô hình này đổi việc phải chạy DDL khi thay đổi form lấy một tầng gián tiếp khi đọc field. Câu hỏi của benchmark là tầng gián tiếp đó tốn bao nhiêu.

## 2. Phạm vi và phương pháp

### 2.1 Môi trường

| | Lần đo 1 | Lần đo 2 |
| --- | --- | --- |
| Máy | Apple M1 Pro, 10 nhân; Docker Desktop 16 GB, 10 CPU | Intel Xeon E5-2678 v3 (12 nhân / 24 luồng, 2,5 GHz), 32 GB RAM, NVMe WD SN570; Docker Desktop trên Windows (WSL2), máy ảo 16 GB / 24 CPU, dùng chung với khoảng 3 GB container khác |
| PostgreSQL | 16.15, image `pgvector/pgvector:pg16` | Như lần 1 |
| Cấu hình | `shared_buffers=4GB`, `work_mem=64MB`, `effective_cache_size=12GB`, `max_wal_size=8GB` | Chuỗi đo gốc: như lần 1. Các phép đo giới hạn: chỉnh theo máy (`max_connections=400`, `max_wal_size=16GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, autovacuum tích cực hơn) |
| Bộ tạo tải | pgbench 18.3 trên host | pgbench 16.15 trong container, dùng chung CPU với PostgreSQL; k6 cho phép đo HTTP |
| Kết quả thô | [m1pro-10c-16g-docker-20261022](../scripts/bench/doctype-jsonb/results/m1pro-10c-16g-docker-20261022/) | [xeon-e5-2678v3-24t-docker-wsl2-20261023](../scripts/bench/doctype-jsonb/results/xeon-e5-2678v3-24t-docker-wsl2-20261023/) và các thư mục cùng tiền tố |

### 2.2 Dữ liệu

- 5.000.000 đơn `CustomerOrder` với đúng các field của [customer-order.doctype.yaml](../astra-apps/express/src/main/resources/META-INF/astra/doctypes/customer-order.doctype.yaml), thêm `ops_status` (8 trạng thái, phân bố lệch).
- 1.000.000 `Customer` trong cùng bảng; 3 tenant với tỷ lệ 80/18/2 %; `created_at` trải đều 60 giờ từ 20/10/2026 00:00 +07.
- Các phép đo giới hạn bổ sung 3.800.890 `Waybill`, mỗi đơn của tenant lớn một vận đơn, `scan_log` 3 mục.
- Cùng dữ liệu được lưu theo ba cách: `astra_doc` (schema của kernel), `orders_rel` (bảng quan hệ truyền thống với B-tree tương đương), `astra_doc_part` (bảng kernel partition theo ngày).

Mọi truy vấn chạy bằng role ứng dụng, tenant gán trong giao dịch, RLS bật ở chế độ FORCE, như môi trường production.

### 2.3 Cách đo

- **Truy vấn đơn lẻ:** `EXPLAIN (ANALYZE, BUFFERS)` 4 lần; báo cáo lần đầu và giá trị tốt nhất trong 3 lần sau (gọi là "ấm").
- **Thông lượng:** mỗi kịch bản tái hiện đúng chuỗi câu lệnh kernel gửi cho một thao tác (gán tenant, kiểm tra Link, cấp số chứng từ, ghi kèm `RETURNING *`), chạy bằng `pgbench -M prepared` 30 giây mỗi mức đồng thời, có `CHECKPOINT` trước mỗi lần. Ghi nhận giao dịch/giây (TPS), độ trễ p50/p95/p99 theo từng loại giao dịch, byte WAL mỗi giao dịch, mức dùng CPU và tỷ lệ trúng cache.
- **Toàn hệ thống:** k6 gửi vòng đời đơn hàng qua REST với token Keycloak thật, ở nhịp đơn/giây cố định (mục 6).

### 2.4 Giới hạn của phép đo

- Cả hai máy chạy PostgreSQL trong Docker Desktop, dữ liệu nằm trên tệp đĩa ảo. Thao tác fsync vì vậy chậm hơn đĩa vật lý (khoảng 970 lần `fdatasync`/giây theo `pg_test_fsync`) và đôi khi tạm dừng vài giây. Số liệu ghi trên máy chủ Linux với NVMe cục bộ sẽ cao hơn và ổn định hơn.
- Ở lần đo 2, bộ tạo tải chạy cùng máy và chia CPU với cơ sở dữ liệu; trần đo được thấp hơn trần thực của máy chủ.
- 5 triệu đơn (khoảng 10 GB dữ liệu và index) gần như nằm trọn trong cache. Ảnh hưởng của dữ liệu lớn hơn RAM được ngoại suy ở mục 10.
- Giá trị nhất là so sánh tương đối giữa các phương án trên cùng máy; số tuyệt đối phụ thuộc phần cứng.
- Script `run-writes.sh` của chuỗi đo gốc truyền `-d bench` cho pgbench. Từ pgbench 17, `-d` là tên database; với pgbench 16 (lần đo 2), `-d` bật chế độ debug và làm giảm thông lượng. Số liệu `run-writes.sh` của lần đo 2 chỉ dùng để so sánh trong cùng lần chạy; các phép đo giới hạn ở mục 5 đã sửa lỗi này và thay thế chúng.

## 3. Phát hiện chính: row-level security và hàm leakproof

### 3.1 Hiện tượng

Với truy vấn kernel sinh ra trước khi tối ưu, các bộ lọc theo field chậm hơn bảng quan hệ hàng nghìn lần (thời gian "ấm", ms, lần đo 1 / lần đo 2; bảng đầy đủ ở phụ lục A):

| Truy vấn | JSONB | Bảng quan hệ |
| --- | --- | --- |
| Q2: 20 đơn mới nhất của một khách | 470 / 867 | 0,07 / 0,19 |
| Q4: Q2 thêm `docstatus = 1` | 608 / 864 | 0,08 / 0,18 |
| Q9: đếm số đơn của một khách | 545 / 876 | 0,09 / 0,12 |
| Q8a: `data @> '{"customer": ...}'` qua GIN | 6.537 / 7.404 | |

### 3.2 Nguyên nhân

Plan của Q2 khi chạy bằng role ứng dụng (RLS bật):

```
Parallel Index Scan using astra_doc_p_zone on astra_doc
  Index Cond: ((tenant_id = current_setting('astra.tenant', true)) AND (doctype = 'CustomerOrder'))
  Filter: ((data ->> 'customer') = 'CUST-0123456')
  Rows Removed by Filter: 800168        (mỗi worker, 5 worker)
```

Cùng câu lệnh chạy bằng superuser (RLS không áp dụng) dùng đúng index theo khách hàng và mất 0,13 ms.

Index tồn tại, hợp lệ và khớp với biểu thức. PostgreSQL không dùng nó vì một quy tắc bảo mật của RLS: điều kiện do người dùng viết chỉ được đánh giá trước policy, tức là được đưa vào điều kiện index, khi mọi hàm trong điều kiện được đánh dấu `LEAKPROOF` (không thể làm lộ giá trị của các dòng bị policy che qua thông báo lỗi). `texteq` là leakproof, nhưng `jsonb_object_field_text` (toán tử `->>`) thì không. Toàn bộ biểu thức vì vậy bị đánh giá sau policy như một bộ lọc, và index chỉ còn dùng được phần `(tenant_id, doctype)`.

Trạng thái leakproof trong catalog:

```
jsonb_object_field_text | f      (->>)
jsonb_contains          | f      (@>)
numeric_gt, numeric_eq  | f
texteq, text_lt, int8gt, int8eq, float8gt, timestamptz_lt, date_ge, uuid_eq, booleq | t
textlike, texticlike    | f      (LIKE, ILIKE)
```

Cùng nguyên nhân dẫn tới hai hệ quả khác:

- **Index GIN không bao giờ được dùng dưới RLS.** Q8a quét 4 triệu dòng rồi lọc `@>` (7,4 giây); bằng superuser, cùng loại truy vấn dùng GIN và mất 36 ms.
- **Cột `numeric` không phục vụ được truy vấn khoảng dưới RLS**, kể cả trên bảng quan hệ. Kiểm tra trên 1 triệu dòng với khoảng chọn 0,1 % ([09-range-leakproof-check.sql](../scripts/bench/doctype-jsonb/09-range-leakproof-check.sql)):

| Cùng dữ liệu, cùng index | Plan | Thời gian |
| --- | --- | --- |
| Cột `numeric(18,2)`, RLS bật | Parallel Seq Scan và Filter | 190 ms |
| Cột `bigint`, RLS bật | Index Only Scan | 2,4 ms |
| Cột `numeric`, superuser | Index Only Scan | 2,0 ms |

Vấn đề không nằm ở JSONB mà ở tương tác giữa RLS và mọi hàm không leakproof. Bảng quan hệ tránh được ở phần lớn truy vấn vì các phép so sánh trên text, số nguyên và thời gian đều leakproof.

### 3.3 Khắc phục: accessor leakproof

`jsonb_object_field_text` không làm lộ dữ liệu; nó chỉ chưa được đánh dấu. Kernel dùng một alias `LANGUAGE internal` trỏ tới cùng hàm C, khai báo `LEAKPROOF`. Việc khai báo cần superuser một lần, nên hàm nằm trong mọi script khởi tạo database ([deploy/db/05-astra-field.sql](../deploy/db/05-astra-field.sql) cho database đã có):

```sql
CREATE SCHEMA astra_ext;                          -- thuộc superuser; role ứng dụng chỉ có USAGE
GRANT USAGE ON SCHEMA astra_ext TO astra_app;
CREATE FUNCTION astra_ext.astra_field(jsonb, text) RETURNS text
    LANGUAGE internal IMMUTABLE STRICT PARALLEL SAFE LEAKPROOF AS 'jsonb_object_field_text';
GRANT EXECUTE ON FUNCTION astra_ext.astra_field(jsonb, text) TO astra_app;
```

Hàm đặt trong schema riêng vì hai lý do: role ứng dụng gọi được nhưng không thay thế được nó, và Flyway yêu cầu schema `public` rỗng khi khởi tạo database mới. Kernel từ chối khởi động nếu hàm không tồn tại hoặc không được đánh dấu leakproof.

| Truy vấn | Trước (ms) | Sau (ms) | Bảng quan hệ (ms) | Plan sau tối ưu |
| --- | --- | --- | --- | --- |
| Q2 | 470 / 867 | **0,13 / 0,29** | 0,07 / 0,19 | Index Scan trên index `astra_field(data, 'customer')`, 9 buffer |
| Q4 | 608 / 864 | **0,13 / 0,29** | 0,08 / 0,18 | Cùng index, lọc `docstatus` trên 5 dòng |
| Q9 | 545 / 876 | **0,15 / 0,29** | 0,09 / 0,12 | Cùng index |

Chênh lệch 0,05–0,1 ms còn lại đến từ dòng JSONB rộng hơn (497 so với 291 byte) và việc giải mã `data` khi trả về.

Một hàm `LANGUAGE sql` bọc `->>` không thay thế được cách làm này. Đã kiểm chứng ([07-leakproof-inline-check.sql](../scripts/bench/doctype-jsonb/07-leakproof-inline-check.sql)): PostgreSQL inline hàm SQL lúc lập plan, sau đó thấy lại `->>` không leakproof, và dưới RLS chọn Seq Scan dù index trên đúng hàm đó tồn tại. Alias `LANGUAGE internal` không bị inline và được dùng làm điều kiện index.

**Giới hạn.** Chỉ superuser mới khai báo được `LEAKPROOF`. Các dịch vụ PostgreSQL managed (RDS, Cloud SQL, Azure) không cấp quyền superuser thực sự, nên kernel cần PostgreSQL tự quản: trên máy ảo, hoặc qua operator Kubernetes như CloudNativePG.

## 4. Hiệu năng từng loại truy vấn sau tối ưu

| Loại truy vấn | Trước tối ưu | Sau tối ưu | Bảng quan hệ | Thay đổi trong kernel |
| --- | --- | --- | --- | --- |
| Lọc theo field, nhiều giá trị (Q2) | 470–867 ms | 0,13–0,29 ms | 0,07–0,19 ms | Accessor leakproof |
| Lọc theo trạng thái hiếm, mới nhất (Q3b) | 5,0–9,6 ms | 0,22–0,42 ms | 2,9–5,9 ms | Index projection có thêm `modified_at DESC` |
| Lọc trong một ngày (Q6) | 534–766 ms | 0,95–2,2 ms | 0,57–2,2 ms | Index `created_at` mặc định |
| Phân trang sâu (Q10) | 200–250 ms (`OFFSET 200000`) | 0,24–0,31 ms | 0,15–0,22 ms | Phân trang theo con trỏ |
| Tổng hợp một ngày (Q7) | 863–1.182 ms | Không đổi | 353–656 ms | Chuyển sang replica |

**Lọc theo trạng thái, mới nhất trước (Q3).** Với giá trị phổ biến, cả hai cách lưu đi theo index `modified_at` và chỉ đọc vài trăm dòng (dưới 3 ms). Với giá trị hiếm (0,5 % số đơn), chiến lược đó phải lọc 3.290 dòng. Index đúng cho truy vấn "trạng thái X, mới nhất" là index tổ hợp có cột sắp xếp. Kernel nay tạo dạng này cho mọi projection, kèm điều kiện `IS NOT NULL` để bỏ qua các DocType không có field đó:

```sql
CREATE INDEX astra_doc_pf_ops_status ON astra_doc (tenant_id, doctype, astra_field(data, 'ops_status'), modified_at DESC)
    WHERE astra_field(data, 'ops_status') IS NOT NULL;
```

**Lọc theo ngày (Q6).** `created_at` là cột quan hệ nên JSONB không chịu bất lợi; chỉ cần index `(tenant_id, doctype, created_at DESC)`, nay được kernel tạo mặc định.

**Phân trang (Q10).** Chi phí của `OFFSET` lớn không liên quan đến JSONB. Phân trang theo con trỏ (`WHERE (modified_at, name) < (?, ?)`) giữ chi phí không đổi ở mọi độ sâu. API trả con trỏ trang sau trong header `X-Next-Cursor` và nhận qua tham số `after`.

**Tổng hợp (Q7).** Đây là điểm JSONB chậm hơn rõ rệt: tính SUM và COUNT trên 2 triệu dòng phải giải mã JSONB từng dòng, chậm hơn bảng quan hệ 1,8–2,4 lần. Cột materialised không khắc phục được, vì bảng JSONB vẫn phải đọc heap rộng gần gấp đôi. Truy vấn tổng hợp, báo cáo và đối soát vì vậy nên chạy trên bảng replica qua CDC hoặc trên sổ cái, không trên `astra_doc`.

## 5. Thông lượng cơ sở dữ liệu

Các phép đo trong mục này dùng cấu hình đã chỉnh theo máy và bộ index của kernel sau tối ưu, trên lần đo 2. Kết quả thô nằm trong thư mục `limits/` của lần đo.

### 5.1 Đọc theo số kết nối

Tải gồm 70 % lấy một đơn theo tên, 20 % danh sách theo khách, 10 % danh sách theo trạng thái.

| Kết nối | JSONB (TPS) | p99 lấy đơn | Bảng quan hệ (TPS) | p99 lấy đơn | CPU |
| --- | --- | --- | --- | --- | --- |
| 8 | 11.980 | 0,97 ms | 10.593 | 1,31 ms | 34 % |
| 16 | 21.186 | 1,25 ms | 22.648 | 1,11 ms | 61 % |
| 32 | 28.964 | 2,18 ms | 30.242 | 2,27 ms | 76 % |
| **64** | **32.740** | 7,01 ms | **34.467** | 6,34 ms | 81 % |
| 128 | 28.828 | 12,12 ms | 30.021 | 11,03 ms | 76 % |
| 256 | 25.817 | 22,46 ms | 28.119 | 18,85 ms | 76 % |

Từ 16 kết nối trở lên, JSONB thấp hơn bảng quan hệ 4–8 %. Thông lượng đạt đỉnh ở 64 kết nối, khoảng 2,7 lần số luồng CPU; vượt mức đó, thông lượng giảm và độ trễ tăng tuyến tính. Kích thước pool kết nối cần tính trên tổng mọi instance kernel (biến `ASTRA_DB_POOL_MAX`, mặc định 20 mỗi instance).

Ở quy mô 5 triệu đơn, đọc ngẫu nhiên trên toàn bộ dữ liệu và đọc chỉ 10 % đơn mới nhất cho cùng thông lượng (khoảng 36.700 TPS), vì dữ liệu nằm trọn trong cache.

### 5.2 Ghi và bộ đếm đánh số chứng từ

Tạo đơn gồm gán tenant, kiểm tra Link khách hàng, cấp số chứng từ và `INSERT ... RETURNING *`. Quét vận đơn gồm gán tenant, đọc Waybill, kiểm tra Link đơn hàng và ghi lại tài liệu kèm kiểm tra phiên bản.

| TPS | 4 kết nối | 16 kết nối | 64 kết nối | p99 ở 64 kết nối |
| --- | --- | --- | --- | --- |
| Tạo đơn JSONB, đánh số không hở (trước tối ưu) | 374 | 291 | 425 | 758 ms |
| Tạo đơn bảng quan hệ, đánh số không hở | 449 | 409 | 584 | 636 ms |
| **Tạo đơn JSONB, đánh số theo khối** | 1.370 | 5.118 | **8.868** | 36 ms |
| Tạo đơn bảng quan hệ, đánh số theo khối | 1.561 | 4.840 | 10.628 | 35 ms |
| Quét vận đơn JSONB, một giao dịch (sau tối ưu) | 1.180 | 3.846 | 7.050 | 46 ms |
| Quét vận đơn JSONB, hai giao dịch (trước tối ưu) | 1.129 | 3.587 | 6.493 | 40 ms |
| Đổi trạng thái trên tài liệu đơn hàng JSONB | 1.288 | 3.741 | 8.246 | 28 ms |
| Đổi trạng thái trên bảng quan hệ | 1.211 | 4.206 | 7.655 | 45 ms |

- **Bộ đếm không hở số là giới hạn cứng của việc tạo đơn**, với cả hai cách lưu. Dòng đếm của mỗi tenant mỗi ngày bị khoá từ lúc cấp số đến lúc giao dịch commit, nên các lần tạo đơn chạy tuần tự; thêm kết nối chỉ làm hàng đợi dài hơn (p50 tăng từ 7 ms lên 101 ms). Chế độ cấp số theo khối (`gapless: false`) loại bỏ khoá này và tăng thông lượng 15–20 lần.
- **Khi không còn bị khoá che khuất, JSONB tạo đơn chậm hơn bảng quan hệ tới 17 %** (8.868 so với 10.628 ở 64 kết nối), do dòng rộng hơn và nhiều index hơn. Đổi trạng thái có hiệu năng tương đương.
- **Gộp đọc, kiểm quyền và ghi vào một giao dịch** (thay đổi số 6 ở mục 11) nhanh hơn 4–9 %.
- **Ghi bị giới hạn bởi độ trễ commit, không phải CPU**: ở 16 kết nối, CPU chỉ dùng 27–30 %. Đĩa có fsync nhanh hơn sẽ cho thông lượng cao hơn ở mức đồng thời thấp.
- **Index projection có điều kiện `IS NOT NULL`** không thay đổi thông lượng ghi ở mức đo được; lợi ích của nó là dung lượng (mục 9).

### 5.3 Tải hỗn hợp giờ cao điểm

Mô hình tải mỗi đơn: 1 lần tạo, 8 lần quét, 14 lần xem, 6 danh sách theo khách, 2 danh sách theo trạng thái (31 giao dịch). 2 triệu đơn/ngày với giờ cao điểm gấp 10 lần trung bình tương ứng 231 đơn/giây, tức khoảng 7.200 TPS.

| Chạy ở công suất tối đa | TPS | Quy đổi đơn/giây | p99 tạo đơn | p99 quét | p99 xem | CPU |
| --- | --- | --- | --- | --- | --- | --- |
| JSONB, đánh số không hở, 16 kết nối | 6.710 | 216 | 71 ms | 16 ms | 2,2 ms | 31 % |
| JSONB, đánh số không hở, 64 kết nối | 7.145 | 230 | 1.937 ms | 22 ms | 2,1 ms | 39 % |
| Bảng quan hệ, đánh số không hở, 64 kết nối | 8.357 | 270 | 1.474 ms | 17 ms | 2,3 ms | 43 % |
| JSONB, đánh số theo khối, 32 kết nối | 12.747 | 411 | 16 ms | 17 ms | 2,1 ms | 58 % |
| **JSONB, đánh số theo khối, 64 kết nối** | **14.522** | **468** | 54 ms | 59 ms | 4,2 ms | 62 % |
| JSONB, đánh số theo khối, 128 kết nối | 13.207 | 426 | 152 ms | 163 ms | 15 ms | 62 % |

Trước tối ưu, hệ thống dừng ở khoảng 230 đơn/giây, đúng mức đỉnh mục tiêu, với p99 tạo đơn gần 2 giây. Sau tối ưu, công suất tối đa đạt khoảng 2 lần mức đỉnh.

### 5.4 Độ trễ ở tải cố định

`pgbench -R` giữ nhịp đến cố định; độ trễ tính cả thời gian chờ đến lượt, nên một hệ thống không theo kịp sẽ có p99 tăng dần. 10 giây đầu mỗi lần chạy không được tính vào phân vị.

| Nhịp đến (TPS) | Cấu hình | Đạt được | p50 | p95 | Đánh giá |
| --- | --- | --- | --- | --- | --- |
| 7.200 (đỉnh) | JSONB, đánh số không hở | 7.202 | 3,6 ms | 20 ms | Theo kịp (1 lần chạy) |
| 7.200 | JSONB, đánh số theo khối | 7.118–7.208 | 2,9–5,9 ms | 0,3–5,0 s | Theo kịp; p95 chịu ảnh hưởng môi trường (dưới) |
| 7.200 | Bảng quan hệ, đánh số theo khối | 7.070–7.218 | 2,5–3,0 ms (một lần 373 ms) | 5 ms–7,9 s | Theo kịp (4 lần chạy) |
| 10.800 (1,5 lần) | JSONB, đánh số không hở | 7.534 | 9,4 s | 18 s | Không theo kịp |
| 10.800 | JSONB, đánh số theo khối | 10.317 | 133 ms | 3,0 s | Bắt đầu tụt lại |
| 10.800 | Bảng quan hệ, đánh số theo khối | 10.810 | 3,0 ms | 1,2 s | Theo kịp |
| 14.400 (2 lần) | JSONB, đánh số không hở | 6.950 | 18,6 s | 30 s | Không theo kịp |
| 14.400 | JSONB, đánh số theo khối | 11.543 | 1,3 s | 10 s | Không theo kịp |
| 14.400 | Bảng quan hệ, đánh số theo khối | 14.397 | 4,1 ms | 2,8 s | Theo kịp |

Ở trạng thái ổn định, độ trễ trung bình là 1,6–5 ms ở nhịp đỉnh. Mỗi lần chạy có một đến hai khoảng toàn hệ thống tạm dừng 0,5–6 giây, xảy ra ở cả hai cách lưu và trùng với các lệnh fsync kéo dài trong log checkpoint. Đây là đặc điểm của đĩa ảo Docker Desktop, không phải giới hạn của PostgreSQL; vì vậy p95 và p99 ở bảng này cần được đo lại trên máy chủ Linux.

Kết luận chắc chắn rút ra: trước tối ưu, hệ thống không theo kịp từ 1,5 lần nhịp đỉnh; sau tối ưu, hệ thống giữ được nhịp đỉnh với p50 3 ms và bắt đầu tụt lại ở 1,5 lần; bảng quan hệ giữ được 2 lần. Với tải nặng về ghi, JSONB có ít hơn khoảng 25–30 % dư địa so với bảng quan hệ.

## 6. Hiệu năng toàn hệ thống qua HTTP

Bộ đo [scripts/bench/http](../scripts/bench/http/README.md) chạy kernel build từ mã nguồn, Keycloak với realm của dự án (xác thực chữ ký và audience của token ở mỗi request), PostgreSQL và Redis. k6 gửi vòng đời đơn hàng của ứng dụng Express qua REST: tạo đơn, submit (tạo vận đơn và bút toán sổ cái), 4 lần quét đến khi giao (lần cuối ghi bút toán COD), 3 lần xem và 2 danh sách theo khách, tổng cộng 11 request mỗi đơn, 60 giây mỗi nhịp. Mọi thành phần, kể cả k6, chạy trên máy của lần đo 2, và database bắt đầu rỗng; kết quả là trần của tầng ứng dụng khi dữ liệu nằm trọn trong cache.

| Đơn/giây | Request/giây | Lượt bị bỏ | p99 tạo | p99 submit | p99 quét | p99 xem | CPU kernel | CPU PostgreSQL |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 1.059 | 0 | 27 ms | 34 ms | 18 ms | 6 ms | 3,1 nhân | 4,0 nhân |
| 300 | 3.267 | 0 | 25 ms | 32 ms | 23 ms | 8 ms | 7,6 nhân | 5,6 nhân |
| **400** | **4.360** | **0** | 34 ms | 47 ms | 33 ms | 14 ms | 8,0 nhân | 9,1 nhân |
| 500 | 1.630 (đạt được) | 57 % | 5,2 s | 10,6 s | 5,0 s | 5,0 s | 5,2 nhân | 4,1 nhân |

Khi một máy chủ gánh cả kernel, cơ sở dữ liệu, Keycloak và bộ tạo tải, trần nằm trong khoảng 400–450 đơn/giây, gần gấp đôi mức đỉnh mục tiêu. Ở 500 đơn/giây máy hết CPU; các request chờ kết nối từ pool cho đến khi hết thời gian chờ 5 giây, nên thông lượng giảm đột ngột thay vì giảm dần. Mỗi request tốn khoảng 1,8 ms CPU ở kernel (xác thực token, xử lý JSON, hook, kiểm quyền) và 2,1 ms CPU ở PostgreSQL. Kernel không giữ trạng thái nên mở rộng được bằng cách thêm instance; cơ sở dữ liệu là một primary.

**Lỗi phát hiện và đã khắc phục.** Header `X-Tx-LSN` (vị trí WAL trả về sau mỗi lần ghi, phục vụ đọc nhất quán qua replica) trước đây được đọc bằng một kết nối thứ hai lấy từ pool, sau khi giao dịch đã trả kết nối của nó. Mỗi request ghi vì thế cần hai lần lấy kết nối, và khi pool chịu tải, các request ghi chờ lẫn nhau. Trước khi khắc phục, hệ thống chỉ đạt 147 đơn/giây ở nhịp 400 đơn/giây với 9.248 lỗi hết thời gian chờ kết nối. Sau khi vị trí WAL được đọc trên chính kết nối vừa commit, cùng nhịp đó chạy không lỗi như bảng trên.

## 7. Lọc khoảng và cột materialised

Biểu thức `(data->>'cod_amount')::numeric > ?` không thể dùng index dưới RLS, vì cả phép ép kiểu lẫn so sánh `numeric` đều không leakproof (mục 3.2). Field cần lọc khoảng phải được sao sang một cột có kiểu thật.

**Quy trình thủ công trên 5 triệu dòng** (thêm cột, backfill theo lô, đánh index):

| Bước | Lần đo 1 | Lần đo 2 | Ghi chú |
| --- | --- | --- | --- |
| `ADD COLUMN` | dưới 10 ms | 3 ms | Chỉ thay đổi metadata, không khoá bảng |
| Backfill 20 lô × 250.000 dòng | 12 phút 26 giây | 19 phút 52 giây | Mỗi UPDATE ghi lại toàn bộ dòng và cập nhật mọi index |
| `VACUUM ANALYZE` | 84 giây | 93 giây | |
| Heap sau backfill | 5.314 MB, 44 % trống | 5.405 MB, 44 % trống | Bảng phình gần gấp đôi, cần `pg_repack` hoặc ghi lại bảng |

**Triển khai trong kernel.** Khai báo `projected: materialised` (cho Currency, Int, Float, Date, Datetime, Check) tạo cột `m_<field>` với kiểu tương ứng; Currency lưu dạng `bigint` theo đơn vị phần trăm (4.900.000,50 thành 490000050), vì so sánh `bigint` là leakproof còn `numeric` thì không. Cột được tính bởi trigger từ `data` ở mọi lần ghi, nên mọi đường ghi, kể cả instance phiên bản cũ trong lúc nâng cấp, đều cho giá trị đúng. Dữ liệu có sẵn được backfill ở nền theo từng tenant và từng lô; trong thời gian đó, bộ lọc đọc giá trị trực tiếp từ JSON để kết quả không bao giờ thiếu dòng. API hỗ trợ `field__gt`, `__gte`, `__lt`, `__lte`, cũng như trên `created_at` và `modified_at`; lọc khoảng trên field không materialised bị từ chối (HTTP 422) vì sẽ phải quét toàn bộ tenant. `CustomerOrder.cod_amount` dùng cơ chế này.

Kernel dùng trigger thay vì cột `GENERATED ALWAYS ... STORED` vì thêm cột generated buộc PostgreSQL ghi lại toàn bộ bảng dưới khoá độc quyền; với bảng hàng trăm triệu dòng, điều đó tương đương dừng ghi.

**Kết quả** trên 4 triệu đơn, truy vấn "đơn có COD trên ngưỡng cao, 20 đơn mới nhất", 16 kết nối:

| Cách lọc | TPS | p50 | p99 |
| --- | --- | --- | --- |
| Đọc từ JSON (khi chưa materialise) | 16 | 986 ms | 1.298 ms |
| Cột materialised, plan dùng chung của prepared statement | 46 | 303 ms | 373 ms |
| **Cột materialised, plan theo giá trị** | **135** | **13 ms** | 336 ms |

Với prepared statement, sau vài lần thực thi PostgreSQL có thể chuyển sang một plan dùng chung không phụ thuộc giá trị tham số. Plan đó không biết ngưỡng thực tế nên chọn đi theo index `modified_at` và lọc 13.378 dòng. Kernel vì vậy buộc các truy vấn danh sách có lọc khoảng được lập plan theo giá trị (`prepareThreshold = 0`), và plan đi thẳng vào index của cột. Phần đuôi p99 phản ánh các ngưỡng khớp hàng nghìn đơn phải đọc từ đĩa; đây là truy vấn đối soát, không nằm trên đường xử lý chính.

## 8. Partition theo tháng

Kernel hỗ trợ `partition: month` cho DocType loại giao dịch. Tài liệu được lưu trong bảng `astra_doc_txn`, partition theo tháng của `created_at` với ranh giới tính theo múi giờ nghiệp vụ (`astra.storage.partition-time-zone`). Partition được tạo trước 2 tháng, một partition mặc định nhận các dòng ngoài khoảng, và dữ liệu có sẵn được chuyển vào đúng tháng khi DocType được bật. Vì ràng buộc unique của PostgreSQL trên bảng partition phải chứa khoá partition, kernel tự kiểm tra trùng tên xuyên tháng (dưới advisory lock) với các tên do người dùng chọn.

Đo trên 7,6 triệu đơn và vận đơn chia đều 18 partition (05/2025 đến 10/2026), 64 kết nối:

| TPS | Một bảng | 18 partition | 18 partition, có thu hẹp theo ngày trong tên |
| --- | --- | --- | --- |
| Lấy theo tên | 33.787 | 13.797 | 17.780 |
| Danh sách theo khách | 33.292 | 8.747 | Không áp dụng |
| Danh sách theo trạng thái | 25.455 | 7.952 | Không áp dụng |
| Tạo đơn | 8.826 | 9.941 | |
| Quét vận đơn | 8.416 | 3.619 | 3.999 |
| Tải hỗn hợp | 14.306 | 6.451 | 8.294 |

Truy vấn theo tên hoặc theo khách không chứa `created_at`, nên PostgreSQL phải dò index của từng partition: tra cứu một đơn trên 18 partition mất 21 ms trong plan, so với 1,3 ms trên một bảng. Với tên lấy từ chuỗi đánh số có ngày (ví dụ `ORD-20261023-…`), kernel thêm điều kiện `created_at >= <ngày trong tên> - 1 ngày`: tài liệu không bao giờ được tạo trước ngày ghi trong tên, nên các tháng cũ hơn bị loại khi thực thi (0,6 ms trong plan). Trong phép đo, tên được chọn ngẫu nhiên trên cả 18 tháng nên trung bình vẫn phải dò một nửa số partition; hệ thống thực tế chủ yếu tra cứu đơn đang xử lý nên chỉ chạm một đến hai partition. Danh sách theo khách hay trạng thái không có thông tin ngày nên không thu hẹp được. Chỉ việc tạo đơn nhanh hơn, nhờ index của tháng hiện tại nhỏ.

**Quyết định.** Partition theo tháng là công cụ quản lý vòng đời dữ liệu, không phải công cụ tăng tốc: nó cho phép vacuum từng tháng và tách, lưu trữ tháng cũ bằng một lệnh thay vì xoá hàng trăm triệu dòng. Tính năng có trong kernel nhưng không bật cho `CustomerOrder` và `Waybill`. Nên bật khi kích thước bảng (vài trăm GB) khiến vacuum và lưu trữ trở thành vấn đề vận hành, và giữ số partition đang gắn ở mức thấp.

Partition theo ngày, đo trong chuỗi đo gốc, cho kết quả tương tự và được ghi ở phụ lục B.

## 9. Dung lượng lưu trữ

| | `astra_doc` (6,0 triệu tài liệu) | `orders_rel` (5,0 triệu) | `astra_doc_part` (5,0 triệu) |
| --- | --- | --- | --- |
| Heap | 3.004 MB | 1.503 MB | 2.775 MB |
| Index | 2.328 MB (GIN 1.047 MB) | 854 MB | 1.282 MB (không có GIN) |
| Tổng | 5.332 MB | 2.357 MB | 4.056 MB |
| Byte mỗi dòng | 505–549 (JSON 417) | 300–310 | khoảng 550 |
| TOAST | 0 | 0 | 0 |

JSONB lưu tên field trong từng dòng: với 13 field, phần tên khoá chiếm khoảng 120 byte và cấu trúc khoảng 130 byte. Ở quy mô 730 triệu đơn mỗi năm, chênh lệch heap khoảng 180 GB mỗi năm. Có thể giảm bằng tên field ngắn, lưu trữ sớm dữ liệu cũ, hoặc dùng bảng quan hệ cho các DocType khối lượng lớn nhất.

Index projection có điều kiện `IS NOT NULL` giảm đáng kể dung lượng khi nhiều DocType dùng chung bảng: trên 10 triệu tài liệu, tổng index giảm từ 4.557 MB xuống 3.726 MB (index theo khách từ 716 MB xuống 383 MB). Bỏ index GIN giải phóng thêm khoảng 1 GB cho mỗi 6 triệu tài liệu.

Thời gian nạp 5 triệu dòng (lần đo 2): bảng quan hệ 82 giây, JSONB (hai bảng) 7 phút 31 giây. Tạo index biểu thức theo khách mất 26 giây (cột tương đương trên bảng quan hệ: 20 giây); index GIN mất 156 giây.

## 10. Đánh giá năng lực cho 2 triệu đơn/ngày và 10 triệu người dùng

**Tải quy đổi.** 2 triệu đơn/ngày tương ứng 23 đơn/giây trung bình và 231 đơn/giây ở giờ cao điểm (gấp 10 lần). Mỗi đơn có khoảng 8 lần cập nhật trạng thái, tức khoảng 1.850 lần ghi/giây ở giờ cao điểm. Với 10 triệu người dùng, giả định 5 % hoạt động mỗi ngày và 20 request mỗi phiên, phía khách hàng tạo khoảng 1.200 lần đọc/giây ở giờ cao điểm; cộng nhân viên vận hành và bưu tá, mô hình tải ở mục 5.3 tính khoảng 5.000 lần đọc/giây. Tổng cộng khoảng 7.200 giao dịch cơ sở dữ liệu mỗi giây ở giờ cao điểm.

**Năng lực đo được trên một primary** (lần đo 2):

| | Trước tối ưu | Sau tối ưu | Mục tiêu giờ cao điểm |
| --- | --- | --- | --- |
| Tạo đơn tối đa | 230 đơn/giây | 468 đơn/giây trong tải hỗn hợp; 8.868 khi chỉ tạo đơn | 231 đơn/giây |
| Tải hỗn hợp, công suất tối đa | 7.145 TPS | 14.522 TPS | 7.200 TPS |
| Tải cố định duy trì được | 7.200 TPS (p99 tạo đơn 316 ms); không theo kịp ở 10.800 | 7.200 TPS (p50 3 ms); tụt lại ở 10.800 | 7.200 TPS |
| Đọc tối đa | 14 TPS (lọc theo field) | 32.700 TPS | khoảng 5.000 TPS |
| Toàn hệ thống qua HTTP, một máy | Không đạt ở 400 đơn/giây | 400 đơn/giây; giới hạn CPU ở 500 | 231 đơn/giây |

**Đánh giá.** Mục tiêu khả thi trên một primary PostgreSQL cỡ vừa cộng các bản sao đọc, với các thay đổi ở mục 11 và các khuyến nghị sau:

1. Dùng máy chủ Linux với NVMe cục bộ cho cơ sở dữ liệu, và đo bằng bộ đo kèm theo trước khi chốt cấu hình (`PROFILE=auto` để tìm trần, `PROFILE=fixed16` để so sánh phần cứng).
2. Mở rộng đọc bằng streaming replication; 10 triệu người dùng chủ yếu tạo tải đọc.
3. Định cỡ RAM cơ sở dữ liệu theo tập dữ liệu đang hoạt động (đơn đang xử lý trong vài ngày gần nhất, khoảng 10 GB), không theo tổng dữ liệu. Lưu trữ dữ liệu cũ sau 12–18 tháng; cân nhắc partition theo tháng khi bảng đạt vài trăm GB (mục 8).
4. Chạy báo cáo, đối soát và dashboard trên bảng replica và sổ cái; theo dõi độ trễ CDC (`_cdc_sync_watermark.lag_ms`).
5. Giữ tổng số kết nối từ mọi instance kernel ở khoảng 2–3 lần số luồng CPU của cơ sở dữ liệu. PgBouncer ở chế độ transaction dùng được vì kernel luôn gán tenant trong một giao dịch tường minh.
6. Định cỡ tầng kernel theo khoảng 1,8 ms CPU mỗi request: 2.540 request/giây ở giờ cao điểm cần khoảng 5 nhân, phân bổ trên 2–3 instance để có dự phòng. Cơ sở dữ liệu cần khoảng 2,1 ms CPU mỗi request, tức khoảng 6 nhân ở giờ cao điểm cộng phần dự phòng.

**Bảng quan hệ riêng cho DocType khối lượng lớn nhất?** Sau tối ưu, chênh lệch đọc dưới 0,1 ms và dưới 8 % thông lượng; tạo đơn chậm hơn tới 17 %; tổng hợp chậm khoảng 2 lần (đã chuyển sang replica); dung lượng lớn hơn khoảng 1,7 lần. Chuyển một hoặc hai DocType khối lượng lớn nhất sang bảng quan hệ cố định (overlay chỉ ghi vào một cột `jsonb` phụ) đáng cân nhắc vì lý do dung lượng, không vì tốc độ. Đây là quyết định sản phẩm.

## 11. Thay đổi trong kernel

| # | Thay đổi | Tác động đo được |
| --- | --- | --- |
| 1 | Accessor `astra_ext.astra_field` (`LANGUAGE internal LEAKPROOF`) trong mọi script khởi tạo database; kernel từ chối khởi động nếu thiếu | Q2: 470–867 ms xuống 0,13–0,29 ms; đọc đồng thời ở 16 kết nối từ 14 lên 6.294 TPS trong cùng điều kiện (bảng quan hệ 6.470) |
| 2 | Index projection `(tenant_id, doctype, astra_field(data, f), modified_at DESC) WHERE ... IS NOT NULL`; tự dựng lại index hỏng; gỡ index `->>` cũ | Q3b: 5–10 ms xuống 0,2–0,4 ms; index nhỏ hơn 18 % |
| 3 | Index `(tenant_id, doctype, created_at DESC)` tạo online mặc định | Q6: 534–766 ms xuống 1–2 ms |
| 4 | Migration V6: bỏ index GIN; autovacuum riêng cho `astra_doc` | Tải hỗn hợp tăng 40 %; giảm 1 GB mỗi 6 triệu tài liệu |
| 5 | Đánh số theo khối (`gapless: false`); `CustomerOrder` và `Waybill` dùng chế độ này với 7 và 8 chữ số, đủ cho 2 triệu đơn/ngày (5 chữ số trước đây chỉ đủ 99.999 đơn/ngày/tenant) | Tạo đơn: 291–425 lên 8.868/giây; tải hỗn hợp: 7.145 lên 14.522 TPS |
| 6 | Cập nhật qua REST kiểm quyền trong cùng giao dịch, không đọc tài liệu hai lần | Quét vận đơn nhanh hơn 4–9 % |
| 7 | Kiểm tra mọi Link của một tài liệu bằng một câu lệnh | Bớt N−1 lượt truy vấn cho tài liệu có N Link |
| 8 | Phân trang theo con trỏ (`after`, header `X-Next-Cursor`) | Trang sâu: 200–250 ms xuống 0,2–0,3 ms |
| 9 | Kích thước pool kết nối cấu hình được (`ASTRA_DB_POOL_MIN`, `ASTRA_DB_POOL_MAX`) | Cho phép định cỡ theo mục 5.1 |
| 10 | `projected: materialised`, bộ lọc khoảng, plan theo giá trị | Lọc khoảng COD: 16 lên 135 TPS, p50 986 xuống 13 ms |
| 11 | `partition: month` (tuỳ chọn) với thu hẹp partition theo ngày trong tên | Sẵn sàng cho quản lý vòng đời; không bật mặc định (mục 8) |
| 12 | `X-Tx-LSN` đọc trên kết nối vừa commit | Toàn hệ thống: 147 lên 400 đơn/giây ở nhịp 400 |
| 13 | Hàm `astra_ext.tenants()` của superuser, cho phép kernel liệt kê tenant để backfill và chuyển dữ liệu dù RLS che các tenant khác | Điều kiện của 10 và 11 |

Kiểm thử bổ sung: `DocListTest` (phân trang con trỏ trả mỗi tài liệu đúng một lần theo thứ tự; bộ lọc projection là điều kiện index dưới RLS), `SeriesBlockTest` (300 đơn tạo đồng thời có số duy nhất), `StorageLayoutTest` (cột materialised và index dưới RLS, backfill, tra cứu tài liệu tháng cũ theo tên và qua Link, chặn trùng tên xuyên tháng, chuyển dữ liệu có sẵn vào đúng tháng). Toàn bộ 84 kiểm thử của `astra-server` đạt.

## 12. Đánh đổi

| Phương án | Lợi ích | Chi phí |
| --- | --- | --- |
| Đánh số theo khối cho chứng từ khối lượng lớn (đã áp dụng) | Tạo đơn nhanh hơn 15–20 lần; tải hỗn hợp gấp đôi; p99 tạo đơn từ 1,9 s xuống 54 ms | Số có khoảng trống (khối chưa dùng khi instance dừng, giao dịch bị rollback); số giữa các instance xen kẽ. Không dùng cho chứng từ kế toán cần đánh số liên tục |
| Bỏ index GIN (đã áp dụng) | Tải hỗn hợp tăng 40 % (10.406 lên 14.522 TPS); tạo đơn tăng 31 % ở 16 kết nối; giảm 1 GB mỗi 6 triệu tài liệu | Mất khả năng truy vấn `@>` tuỳ ý, vốn không dùng được dưới RLS |
| `synchronous_commit = off` | Tạo đơn tăng 63 %, quét vận đơn tăng 87 %, tải hỗn hợp tăng 14 % | Khi máy chủ gặp sự cố, mất tối đa khoảng 0,6 giây giao dịch đã xác nhận (không làm hỏng dữ liệu). Phù hợp với dữ liệu quét có thể phát lại, không phù hợp với giao dịch tài chính |
| Index projection có điều kiện (đã áp dụng) | Index nhỏ hơn 18 % trên bảng dùng chung | Không ảnh hưởng thông lượng ở mức đo được |
| Một giao dịch cho cập nhật qua REST (đã áp dụng) | Quét vận đơn nhanh hơn 4–9 % | Không |
| Partition theo tháng | Vacuum và lưu trữ theo tháng | Thông lượng giao dịch giảm khoảng một nửa với 18 partition |
| Bảng quan hệ cho DocType lớn nhất | Tạo đơn nhanh hơn tới 17 %; dòng nhỏ hơn 40 %; tổng hợp nhanh hơn khoảng 2 lần | Mất khả năng thay đổi form không cần DDL cho DocType đó |

## 13. Hạn chế còn lại và việc tiếp theo

- **Dung lượng:** khoảng 400 GB heap mỗi năm cho 730 triệu đơn, so với khoảng 220 GB với bảng quan hệ.
- **Truy vấn danh sách trả toàn bộ `data`:** 20 đơn tương ứng khoảng 8 KB JSON phải giải mã ở kernel. Ở hàng chục nghìn request/giây, nên chỉ trả các field hiển thị trong danh sách.
- **Cập nhật ghi lại toàn bộ tài liệu:** tài liệu lớn (child table, `scan_log`) làm mỗi lần cập nhật tốn kém hơn. Nên giữ tài liệu dưới 2 KB để tránh TOAST, hoặc chuyển nhật ký quét sang bảng chỉ ghi thêm.
- **Kiểu dữ liệu:** mọi field cần lọc khoảng phải được materialise; index biểu thức chỉ phục vụ so sánh bằng trên text.
- **Ràng buộc dữ liệu:** kiểu, NOT NULL và khoá ngoại của field được kiểm tra ở kernel, không ở cơ sở dữ liệu.
- **Tìm kiếm chuỗi con:** `LIKE` và `ILIKE` không leakproof; tìm kiếm cần đi qua chỉ mục tìm kiếm riêng.
- **PostgreSQL managed:** không hỗ trợ, do accessor leakproof cần quyền superuser.
- **Lưu trữ tháng cũ** của bảng partition hiện thực hiện thủ công; cần một tác vụ trong kernel.
- **CPU của kernel** (khoảng 1,8 ms mỗi request) chưa được profile; nên xem trước bộ nhớ đệm khoá công khai JWT và việc sao chép dữ liệu giữa các lớp.

## 14. Tái lập kết quả

Cơ sở dữ liệu, trên máy chủ Linux có Docker:

```bash
cd scripts/bench/doctype-jsonb
DATA_DIR=/mnt/nvme/astra-bench BENCH_NOTE="<loại máy, đĩa, vùng>" ./bench.sh   # khoảng 2,5 giờ
./compare.sh                                                                     # so sánh các lần chạy
```

Toàn hệ thống qua HTTP:

```bash
cd scripts/bench/http
RATES="100 200 300 400 500" BENCH_NOTE="<loại máy>" ./run.sh
```

Mỗi lần chạy ghi vào `results/<nhãn>/`: thông tin máy, kết quả `pg_test_fsync`, mọi plan, mọi output của bộ tạo tải, `metrics.csv` và `report.md`. Hướng dẫn chi tiết và cách so sánh khách quan giữa các máy nằm trong README của từng bộ đo.

## Phụ lục A. Truy vấn đơn lẻ trước tối ưu

Thời gian "ấm" (ms), RLS bật, lần đo 1 / lần đo 2. Máy của lần đo 2 chậm hơn khoảng 1,5–2 lần ở truy vấn đơn lẻ; hình dạng kết quả giống nhau.

| # | Truy vấn | JSONB | Bảng quan hệ | JSONB, partition theo ngày |
| --- | --- | --- | --- | --- |
| Q1 | Lấy 1 đơn theo tên | 0,07 / 0,12 | 0,08 / 0,23 | 0,21 / 0,61 |
| Q2 | 20 đơn mới nhất của một khách | 470 / 867 | 0,07 / 0,19 | 558 / 1.018 |
| Q3a | 20 đơn mới nhất, `zone = Remote` (10 %) | 0,53 / 2,3 | 0,35 / 0,57 | |
| Q3b | 20 đơn mới nhất, `ops_status = Cancelled` (0,5 %) | 5,0 / 9,6 | 2,9 / 5,9 | 6,5 / 11,5 |
| Q4 | Q2 thêm `docstatus = 1` | 608 / 864 | 0,08 / 0,18 | |
| Q5 | `zone = Remote` và COD trên 4.900.000, 20 mới nhất | 25 / 28 | 12 / 23 | |
| Q6 | Một ngày, `ops_status = Failed Delivery`, 50 mới nhất | 534 / 766 | 136 / 230 | 322 / 545 |
| Q7 | SUM và COUNT theo vùng trong một ngày (2 triệu dòng) | 863 / 1.182 | 353 / 656 | 1.944 / 3.866 |
| Q8a | `data @> '{"customer": ...}'` | 6.537 / 7.404 | | |
| Q8b | `data @> '{"zone": ..., "ops_status": ...}'`, COUNT | 738 / 925 | 57 / 78 | |
| Q9 | Đếm đơn của một khách | 545 / 876 | 0,09 / 0,12 | |
| Q10 | Danh sách với `OFFSET 200000` | 200 / 248 | 218 / 248 | |

Q5 không thay đổi khi cột tiền chuyển từ `numeric` sang `bigint` (lần đo 2: 39 và 104 ms trên JSONB; 16,5 và 39 ms trên bảng quan hệ), vì planner chọn đi theo index `modified_at` và lọc khoảng 11.000 dòng trong cả bốn trường hợp. Kiểu cột chỉ quyết định khi điều kiện khoảng là điều kiện chọn lọc chính (mục 3.2 và mục 7).

## Phụ lục B. Partition theo ngày

| | Kết quả |
| --- | --- |
| Lấy theo tên (Q1) | 0,21 / 0,61 ms so với 0,07 / 0,12 ms, vì phải dò 5 partition |
| Lọc trong một ngày (Q6) | 322 / 545 ms so với 534 / 766 ms khi chưa có index; index `created_at` trên một bảng cho 1–2 ms |
| Tổng hợp một ngày (Q7) | 1.944 / 3.866 ms so với 863 / 1.182 ms |
| Ràng buộc unique | Phải chứa khoá partition, nên PostgreSQL không còn bảo đảm tên duy nhất trong DocType |
| Múi giờ | Partition theo ngày UTC trong khi nghiệp vụ theo +07, nên truy vấn "một ngày" chạm 2 partition |
