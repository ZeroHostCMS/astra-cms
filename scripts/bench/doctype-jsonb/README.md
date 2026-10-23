# DocType JSONB benchmark

Bộ đo hiệu năng lưu trữ DocType (bảng JSONB `astra_doc` của kernel) so với bảng cột truyền thống, dưới row-level security như production. Mục tiêu là biết **giới hạn**, **các phương án tối ưu** và **đánh đổi** của từng phương án, đo lặp lại được trên nhiều máy chủ Linux và cloud. Kết quả và phân tích: [docs/why_doctype_jsonb.md](../../../docs/why_doctype_jsonb.md).

PostgreSQL chạy trong Docker từ **một image cố định** (mặc định `pgvector/pgvector:pg16`, image của dự án; digest được ghi lại), nên mọi máy và mọi cloud chạy cùng một bản build. Container dùng mạng của host (`--network host`, không NAT) và có thể đặt dữ liệu lên đúng đĩa cần đo (`DATA_DIR`). `pgbench` và `psql` cũng chạy từ image đó.

## Chạy

```bash
DATA_DIR=/mnt/nvme/astra-bench BENCH_NOTE="aws m7i.2xlarge, gp3 16k IOPS, ap-southeast-1" ./bench.sh
./compare.sh                     # bảng so sánh mọi lần chạy trong results/
```

| Lệnh | Làm gì | Thời gian (24 luồng, NVMe, 5 triệu đơn) |
| --- | --- | --- |
| `./bench.sh` | Mọi giai đoạn: `load baseline fixes bigint limits report` | khoảng 2,5 giờ |
| `./bench.sh quick limits` | Nạp gọn (không GIN, không partition) rồi chỉ đo giới hạn | khoảng 1 giờ |
| `./bench.sh load baseline` | Bất kỳ tập con nào, theo thứ tự trên | |
| `./summarize.sh results/<label>` | Tạo lại `metrics.csv` và `report.md` từ dữ liệu thô | vài giây |

Yêu cầu: Linux, Docker, bash, awk, **ít nhất 60 GB trống** cho dữ liệu với 5 triệu đơn (bộ đầy đủ gồm ba bảng, GIN, WAL 16 GB, bản rewrite khi đổi kiểu cột). Cuối lần chạy, container và dữ liệu bị xoá; `KEEP=1` để giữ lại.

## Biến môi trường chính

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `DATA_DIR` | volume Docker | Thư mục dữ liệu trên host; đặt trên đĩa muốn đo. Phải rỗng |
| `BENCH_LABEL` | `<hostname>-<yyyymmdd-hhmm>` | Tên thư mục kết quả |
| `BENCH_NOTE` | | Ghi chú lưu vào `machine.txt`: loại instance, vùng, loại đĩa và IOPS, giá |
| `ROWS` | `5000000` | Số đơn sinh ra; `2000000` là một ngày ở tải mục tiêu |
| `PROFILE` | `auto` | `auto`: chỉnh PostgreSQL theo RAM và CPU của máy. `fixed16`: cùng một cấu hình 16 GB trên mọi máy, để so phần cứng |
| `PG_ARGS` | | Tham số server thêm, ví dụ `"-c synchronous_commit=off"` |
| `PG_IMAGE` | `pgvector/pgvector:pg16` | Đổi để đo phiên bản PostgreSQL khác; giữ nguyên giữa các máy cần so |
| `SECS` | `30` | Thời gian mỗi lần chạy pgbench trong giai đoạn limits |
| `WARMUP` | `10` | Số giây đầu mỗi lần chạy không tính vào p50/p95/p99 (đợt ghi full-page ngay sau checkpoint) |
| `RATES` | `7200 10800 14400` | Các mức tải cố định (giao dịch/giây) cho phép đo độ trễ: mức đỉnh mục tiêu, gấp 1,5 và gấp 2 |
| `LIMIT_MODES` | `reads writes mixed rate locality partition sync-off gin` | Các nhóm đo trong giai đoạn limits; `partition` dựng thêm bảng 18 partition tháng (`12-partition-setup.sql`) và in plan (`13-partition-plans.sql`) |
| `RATE_SECS`, `WARMUP` | `60`, `10` | Thời lượng mỗi lần chạy tải cố định; số giây đầu không tính vào phân vị |
| `BENCH_PORT` | `5433` | Cổng PostgreSQL trên mạng host |
| `KEEP`, `REUSE` | `0` | Giữ container và dữ liệu sau khi chạy; chạy tiếp trên container đã có |

## Kết quả

Mỗi lần chạy ghi vào `results/<label>/`:

| Tệp | Nội dung |
| --- | --- |
| `machine.txt` | CPU, số lõi, RAM, loại instance (DMI), CPU governor, THP, đĩa chứa dữ liệu, digest image, phiên bản PostgreSQL, profile |
| `pg_test_fsync.txt` | Độ trễ fsync của đĩa dữ liệu, thứ giới hạn ghi khi ít client |
| `pg-settings.txt`, `postgres.log` | Mọi tham số khác mặc định; log server (checkpoint, autovacuum) |
| `summary.txt`, `q*.txt` | Từng truy vấn đơn, plan `EXPLAIN (ANALYZE, BUFFERS)` của 4 lần chạy |
| `writes-summary.txt`, `pgbench-*.txt` | Ghi và đọc đồng thời của bộ đo gốc |
| `limits/summary.txt`, `limits/*.txt` | Mỗi lần chạy giới hạn: giao dịch/giây, lỗi, % CPU, % trúng cache, byte WAL mỗi giao dịch, p50/p95/p99/max theo từng loại giao dịch |
| `sizes-*.txt`, `limits/sizes.txt` | Dung lượng heap, index, TOAST, byte mỗi dòng, % bloat |
| `metrics.csv` | Mọi số chính dạng `key,value`, để `compare.sh` ghép nhiều lần chạy |
| `report.md` | Các bảng tóm tắt |

`./compare.sh` in bảng Markdown các số chính của mọi lần chạy; `ALL=1 ./compare.sh > all.csv` xuất mọi chỉ số; `KEYS='...' ./compare.sh` chọn chỉ số.

## Để so sánh khách quan

- **Chạy mỗi cấu hình ít nhất 3 lần** (cùng máy, khác `BENCH_LABEL`) và so trung vị; độ lệch giữa các lần chạy là sai số của phép đo.
- **So phần cứng thì dùng `PROFILE=fixed16`** trên mọi máy; **tìm giới hạn của một máy thì dùng `PROFILE=auto`**. Giữ cùng `PG_IMAGE` (tốt nhất ghim theo digest trong `machine.txt`).
- Không chạy việc khác trên máy trong lúc đo; ghi `BENCH_NOTE` đủ để người khác dựng lại máy.
- CPU governor nên là `performance`. Trên cloud, tránh instance burstable (t3, t4g, B-series): hết credit CPU giữa chừng làm số sai lệch.
- Đĩa: dữ liệu và WAL cùng nằm dưới `DATA_DIR`. Volume mạng (EBS gp3, Persistent Disk) có độ trễ fsync cao hơn NVMe cục bộ nhiều lần; `pg_test_fsync.txt` cho thấy điều đó, và thông lượng ghi ít client tỉ lệ nghịch với nó.
- pgbench chạy cùng máy với PostgreSQL và tranh CPU với nó, nên trần đo được thấp hơn trần thật của server. Để tách, dựng server trên VM database và chạy pgbench từ VM client:

  ```bash
  # VM database; chỉ mở BENCH_PORT cho IP của VM client trong firewall của cloud
  KEEP=1 LISTEN=0.0.0.0 BENCH_SU_PASSWORD='<mật khẩu>' ./bench.sh quick
  # VM client, cùng repo
  BENCH_HOST=<ip-database> BENCH_SU_PASSWORD='<mật khẩu>' ./bench.sh limits report
  ```

## Các workload (thư mục `workloads/`)

Mỗi tệp tái hiện đúng các câu lệnh kernel gửi cho một thao tác, bằng role ứng dụng, có `BEGIN`, gán tenant bằng `set_config` và RLS như production:

| Tệp | Thao tác |
| --- | --- |
| `jsonb-get.sql`, `rel-get.sql` | `DocumentStore.get` một đơn theo `name` |
| `jsonb-list-customer.sql`, `rel-list-customer.sql` | 20 đơn mới nhất của một khách, qua accessor leakproof |
| `jsonb-list-status.sql`, `rel-list-status.sql` | 20 đơn mới nhất của một trạng thái |
| `jsonb-insert-series.sql`, `rel-insert-series.sql` | Tạo đơn: kiểm tra Link, bộ đếm series gapless (khoá dòng tới commit), `INSERT ... RETURNING *` |
| `jsonb-insert-seq.sql`, `rel-insert-seq.sql` | Như trên nhưng số không lấy từ dòng bị khoá (tương đương `gapless: false`) |
| `jsonb-update-order.sql` | Đổi trạng thái trên doc đơn hàng rộng |
| `jsonb-update-waybill.sql` | Một lần quét: đọc, kiểm tra Link, ghi Waybill hẹp và nối `scan_log`, một transaction |
| `jsonb-update-waybill-2tx.sql` | Lần quét qua REST trước khi sửa: hai transaction, đọc doc hai lần |
| `jsonb-get-hot.sql`, `jsonb-update-waybill-hot.sql` | Chỉ trên 10 % đơn mới nhất: mẫu truy cập của hệ thống thật |
| `rel-update.sql` | Đổi trạng thái trên bảng cột |
| `txn-*.sql` | Cùng các thao tác trên `astra_doc_txn` (partition theo tháng, 18 partition); `txn-*-pruned.sql` thêm cận dưới `created_at` như kernel làm với tên series có ngày |
| `range-cod-col.sql`, `txn-range-cod-col.sql`, `txn-range-cod-json.sql` | Lọc khoảng COD trên cột materialised so với cùng phép lọc tính từ JSON; chạy cả với plan dùng chung (`-M prepared`) và plan theo giá trị (`-M extended`) |

Tải hỗn hợp giờ cao điểm (`mixed`, `rate`) theo tỷ lệ mỗi đơn: 1 tạo, 8 lần quét, 14 lần xem, 6 danh sách theo khách, 2 danh sách theo trạng thái. 2 triệu đơn/ngày, đỉnh gấp 10 lần trung bình, là 231 đơn/giây và khoảng 7.200 giao dịch/giây.

## PostgreSQL managed (RDS, Cloud SQL, Azure)

Các dịch vụ này không cấp superuser thật, nên **không tạo được hàm `LEAKPROOF`** (`astra_field`). Không có nó, mọi bộ lọc theo field dưới RLS là quét toàn tenant, và kernel từ chối khởi động. Bộ đo cũng cần superuser. Để đo trên cloud, dùng VM chạy bộ đo này (hoặc operator Kubernetes cấp superuser như CloudNativePG).
