Trong kiến trúc của Astra, việc phân tách giữa **Build-time** (thời điểm biên dịch/đóng gói) và **Run-time** (thời điểm hệ thống đang chạy) được thiết kế để giải quyết bài toán cốt lõi: **Làm sao để vừa có hiệu năng cực cao của Quarkus, vừa cho phép thay đổi tính năng, thêm trường dữ liệu và cập nhật plugin mỗi ngày mà không bao giờ phải tắt hệ thống (Zero-downtime)**.

Astra giải quyết bài toán này bằng cách chia các ứng dụng/plugin ("Apps") thành **3 tầng cô lập (Three Isolation Tiers)** trải dài từ Build-time sang Run-time.

---

## 1. Build-time hoạt động như thế nào? (Tier 0: Shared)

**Build-time** là giai đoạn bạn chạy lệnh `astra build` để đóng gói nhân hệ thống (Kernel) cùng các ứng dụng lõi thành một container image bất biến (immutable image) trước khi đem đi triển khai.

* **Cơ chế nạp và liên kết (Wiring):** Astra tận dụng triệt để cơ chế `@BuildStep` và bộ chỉ mục bytecode **Jandex** của Quarkus ngay trong lúc build. Toàn bộ việc quét class, kiểm tra phụ thuộc (CDI) và đăng ký các điểm mở rộng (Extension Points) của các ứng dụng **Tier 0** đều được giải quyết xong ở bước này thay vì đợi lúc khởi động server.


* **Hiệu năng lúc chạy:** Vì mọi thứ đã được nối kết cứng từ lúc build, khi hệ thống chạy, việc gọi vào code của ứng dụng Tier 0 là **gọi hàm trực tiếp trong bộ nhớ (direct call, zero copy)**, không tốn chi phí trung gian. Nhờ vậy, một pod Kernel khởi động chỉ mất **dưới 5 giây trên JVM** và **dưới 1 giây nếu biên dịch ra GraalVM Native**.


* **Quyền hạn và ứng dụng:** Chỉ các ứng dụng ở Build-time (Tier 0) mới có toàn quyền hệ thống, bao gồm quyền định nghĩa thêm các Extension Point mới và quyền ghi trực tiếp vào các bảng Sổ cái kế toán/kho (`ledger` storage). Đây là nơi đặt các phân hệ cốt lõi, chạy trên mọi request như Kế toán (`erp.accounts`) hay Kho (`erp.stock`).


* **Điểm đánh đổi:** Vì nằm trong image, bất kỳ thay đổi nào ở Tier 0 đều yêu cầu phải **build lại image và thực hiện rolling deploy** (khởi động lại pod theo cụm).



---

## 2. Run-time hoạt động như thế nào? (Tier 1 & Tier 2)

**Run-time** là giai đoạn hệ thống đang phục vụ người dùng thực tế. Để các khách hàng (tenants) có thể cài thêm tính năng riêng, viết thêm luật kiểm tra (validation) hoặc cho phép AI tự sinh code mới mà **không cần khởi động lại pod**, Astra cung cấp 2 tầng nạp động ở Run-time và kết hợp với cơ chế dữ liệu không khóa bảng:

### A. Tier 1: Isolated JVM (Nạp động bằng Java/Kotlin ở Run-time)

Dành cho các module tích hợp hoặc bản địa hóa phức tạp (ví dụ: module Hóa đơn điện tử và Thuế Việt Nam).

* **Cơ chế nạp (ClassLoader riêng biệt):** Khi bạn đẩy một bản cập nhật lên, hệ thống dùng một `PluginClassLoader` cô lập (theo phong cách PF4J) cho từng phiên bản ứng dụng để nạp thẳng file `.jar` vào bộ nhớ đang chạy.


* **Gọi hàm không dùng Reflection:** Thông thường, việc nạp code động ở run-time hay dùng Java Reflection rất chậm. Astra loại bỏ hoàn toàn Reflection trên luồng xử lý chính (hot path) bằng cách gắn kết các **`MethodHandle`** ngay tại thời điểm nạp plugin (load time) và tính toán trước chuỗi gọi hàm (precomputed dispatch chains). Kết quả là chi phí điều phối lời gọi hàm chỉ mất **dưới 1 micro-giây (< 1 µs)**, gần như tương đương với gọi hàm trực tiếp.


* **Cô lập sự cố (Bulkhead):** Nếu một plugin Tier 1 bị lỗi, nó không làm sập cả Pod như Tier 0 mà bị chặn lại bởi vách ngăn (giới hạn timeout, giới hạn số luồng đồng thời, circuit breaker).


* **Giới hạn với GraalVM Native:** Do GraalVM Native Image đóng băng toàn bộ thế giới class lúc build (closed-world assumption) và không cho phép nạp bytecode Java động lúc run-time, nên **Tier 1 chỉ chạy được trên chế độ JVM tiêu chuẩn, không hỗ trợ GraalVM Native**.



### B. Tier 2: Wasm Sandbox (Chạy mã WebAssembly an toàn tuyệt đối ở Run-time)

Dành cho các đoạn script tùy chỉnh của từng khách hàng, ứng dụng trên chợ (marketplace) hoặc **code do AI tự động viết ra**.

* **Cơ chế nạp và biên dịch thông minh (Chicory Engine):** Developer hoặc AI có thể viết code bằng Rust, Go, JS, Python... rồi biên dịch ra chuẩn WebAssembly (Wasm). Astra dùng engine **Chicory** (chạy thuần trên JVM) để nạp module Wasm này:


* **Khi chạy trên JVM:** Ngay lúc phát hành phiên bản mới (ở bước *warm-up*, nằm ngoài luồng request của người dùng), Chicory sẽ **dịch sẵn mã Wasm sang JVM Bytecode** (mất dưới 500ms). Đến khi có request thật gọi vào, đoạn code đó chạy nhanh như bytecode gốc với độ trễ **dưới 5ms**.


* **Khi chạy trên GraalVM Native:** Vì trình biên dịch Wasm-sang-Bytecode của Chicory bắt buộc phải chạy ở *build-time* nếu dùng GraalVM Native, nên nếu bạn cập nhật nóng (hot-swap) một file Wasm mới vào lúc *run-time* trên bản Native Image, Astra vẫn chạy được bằng cách tự động chuyển sang **chế độ thông dịch (interpreted mode)**.




* **Cô lập cứng (Hard Sandbox):** Code ở Tier 2 bị khóa chặt trong lồng kính: giới hạn cứng số trang bộ nhớ (memory pages), ngắt cưỡng bức nếu chạy quá thời gian (deadline/interrupt), và hoàn toàn không thể gọi ra mạng hay đọc database trừ khi được Kernel cấp quyền (host functions) rõ ràng.



---

## 3. Sự phối hợp giữa Build-time và Run-time khi có thay đổi (Hot-Swap & Zero-DDL)

Để hệ thống "thay đổi mỗi ngày mà không bao giờ sập", Astra kết hợp khéo léo các bước chuẩn bị ngầm ở Run-time cho cả **Code** lẫn **Dữ liệu**:

1. **Quá trình thay thế Code đang chạy (Run-time Rollout):**
* **Nạp song song & Làm nóng (Side-by-side Load & Warm-up):** Khi có plugin mới (Tier 1 hoặc Tier 2), thông báo được bắn qua Redis Pub/Sub tới toàn bộ các Pod. Các Pod tải bản mới về nằm **song song** với bản cũ, kiểm tra chữ ký bảo mật, dịch sẵn Wasm ra bytecode (hoặc gắn `MethodHandle`) và dựng sẵn chuỗi gọi hàm mới trong chưa tới **2 giây**.


* **Chuyển mạch nguyên tử (Atomic Cutover):** Khi đã sẵn sàng, Pod chỉ việc trỏ lại con trỏ điều phối (dispatch re-point) sang bản mới. Thao tác này diễn ra tức thời (**< 10 ms**).


* **Xả luồng cũ (Drain):** Những request của người dùng đang chạy dở dang ở mili-giây trước đó vẫn tiếp tục chạy trên phiên bản code cũ cho đến khi hoàn tất; các request mới sẽ vào code mới. Sau khi bản cũ không còn request nào và không còn khách hàng nào ghim (pin) nữa, hệ thống mới gỡ ClassLoader cũ và kiểm tra rò rỉ bộ nhớ (leak check).




2. **Thay đổi cấu trúc Dữ liệu lúc Run-time mà không khóa bảng (Zero-DDL):**
* Thông thường, thêm một cột dữ liệu lúc hệ thống đang chạy (Run-time) sẽ phải chạy lệnh `ALTER TABLE` gây khóa bảng (lock table).


* Astra tránh hoàn toàn việc này bằng cách lưu các trường dữ liệu của DocType và các bản vá tùy chỉnh của khách hàng (Overlays) vào cột **`JSONB`** của PostgreSQL. Khi một trường cần tìm kiếm hay sắp xếp nhanh, hệ thống tự động tạo **Expression Index** ngầm (`CREATE INDEX CONCURRENTLY`) hoàn toàn online mà không làm gián đoạn bất kỳ giao dịch nào đang chạy.





---

## Bảng tóm tắt đối chiếu nhanh

| Tiêu chí | Build-time (Tier 0: Shared) | Run-time (Tier 1: Isolated JVM) | Run-time (Tier 2: Wasm Sandbox) |
| --- | --- | --- | --- |
| **Thời điểm nạp code** | Lúc chạy lệnh `astra build` tạo Image

 | Nạp nóng (Hot-swap) khi server đang chạy

 | Nạp nóng (Hot-swap) khi server đang chạy

 |
| **Công nghệ cốt lõi** | Quarkus `@BuildStep` + Jandex + CDI

 | `PluginClassLoader` riêng + `MethodHandle`<br> | Chicory Wasm Engine + Extism ABI

 |
| **Tốc độ gọi hàm** | Nhanh nhất (Direct call, zero copy)

 | Rất nhanh (< 1 µs cho bước điều phối)

 | Nhanh (< 5 ms khi đã dịch sẵn ra bytecode)

 |
| **Cập nhật cần làm gì?** | Build lại Image + Rolling Deploy các Pod

 | Tự động thay thế nóng trong vài giây (0 downtime)

 | Tự động thay thế nóng trong vài giây (0 downtime)

 |
| **Hỗ trợ GraalVM Native** | **Có** (Tối ưu hóa toàn diện lúc build)

 | **Không** (Do Native không cho nạp Class động)

 | **Có** (Chuyển sang chế độ thông dịch khi hot-swap)

 |
| **Phạm vi ảnh hưởng lỗi** | Sập cả Pod nếu có lỗi nghiêm trọng

 | Bị chặn bởi Bulkhead (Timeout, Circuit Breaker)

 | Cô lập tuyệt đối (Giới hạn cứng RAM & thời gian)

 |
| **Mục đích sử dụng** | Các module ERP/CMS cốt lõi, Sổ cái (Ledgers)

 | Module mở rộng theo vùng/khách hàng, Tích hợp

 | Script kiểm tra dữ liệu, Marketplace, **Code do AI sinh ra**<br> |