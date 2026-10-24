# Astra kernel HTTP benchmark

Đo toàn bộ đường đi của một đơn hàng qua REST API của kernel, với token Keycloak thật, PostgreSQL, Redis và chính bản kernel build từ checkout này. Bộ đo database ([../doctype-jsonb](../doctype-jsonb/README.md)) cho biết trần của PostgreSQL; bộ này cho biết trần của cả hệ thống (HTTP, xác thực token, Jackson, hook, kiểm quyền, giao dịch).

## Chạy

```bash
./run.sh                                          # nhịp 25 50 100 150 200 250 300 đơn/giây, mỗi nhịp 60 giây
RATES="100 200 300" DURATION=120s BENCH_NOTE="aws c7i.4xlarge" ./run.sh
```

Yêu cầu: Linux, Docker với `docker compose`. Kernel phải build sẵn (`astra-server/target/quarkus-app`); thiếu thì `run.sh` tự build bằng Maven trong Docker (`BUILD=1` để build lại).

Mỗi lượt k6 là trọn vòng đời một đơn của app Express, 11 request:

| Bước | Request | Ghi chú |
| --- | --- | --- |
| create | `POST /api/doc/CustomerOrder` | kiểm tra Link khách, tính phí ship trong hook, số đơn từ khối series |
| submit | `POST /api/doc/CustomerOrder/{name}/submit` | tạo Waybill, bút toán sổ cái, cùng một giao dịch |
| scan ×4 | `PUT /api/doc/Waybill/{wb}` | Picked Up, At Hub, Out for Delivery, Delivered (bút toán COD) |
| get ×3 | `GET /api/doc/CustomerOrder/{name}` | |
| list ×2 | `GET /api/doc/CustomerOrder?customer=...` | bộ lọc projection |

2 triệu đơn/ngày, giờ cao điểm gấp 10 lần trung bình, là 231 đơn/giây, tức khoảng 2.540 request/giây.

## Kết quả

`results/<label>/`: `machine.txt`, `summary.txt` (mỗi nhịp: số đơn đạt được, số lượt bị bỏ vì không kịp, request/giây, tỷ lệ lỗi, trung vị/p95/p99/max theo từng bước, CPU và bộ nhớ của kernel, PostgreSQL, Keycloak), `k6-rate-*.json` (toàn bộ số liệu k6), `kernel-log-summary.txt` (đầu, cuối và thống kê lỗi của log kernel).

Đọc kết quả: nhịp cao nhất mà `dropped` bằng 0 và p99 còn trong ngưỡng chấp nhận là trần của cấu hình đó. Mọi dịch vụ chạy trên một máy nên tranh CPU với nhau; trần một máy là cận dưới. Kernel không giữ trạng thái nên scale ngang bằng thêm instance (`ASTRA_DB_POOL_MAX` chia theo số instance).
