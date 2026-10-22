# Bài viết chuyên sâu về Astra CMS

Mỗi video demo có một bài viết dành cho developer và tech lead. Mỗi bài giải thích vấn đề, video chứng minh điều gì,
cơ chế hoạt động kèm code trích từ chính repository này, một ví dụ thực tế, các đánh đổi mà tech lead cần cân nhắc,
cách tự chạy thử, phần nào chưa làm, và prompt để tạo hình vẽ tay minh hoạ ý tưởng.

Danh sách video nằm trong [README](../../README.vi.md#video-demo); kịch bản từng cảnh nằm trong
[demo-video-series.md](../demo-video-series.md). English version: [README.md](./README.md).

## Prompt vẽ hình

Mỗi bài kết thúc bằng mục *Minh hoạ: prompt vẽ hình*. Prompt dùng được với mọi mô hình tạo ảnh (Midjourney, DALL·E,
Imagen, Nano Banana) và được giữ nguyên tiếng Anh để mô hình hiểu chính xác. Tất cả dùng chung một dòng phong cách để các
hình trông như cùng một bộ:

```text
Hand-drawn whiteboard sketch, black ink line art on off-white paper, one accent color (#6b8cff blue) for the key path, simple boxes and arrows, short handwritten labels in English, generous white space, no logos, no photorealism, 16:9.
```

Hãy đối chiếu từng hình tạo ra với bài viết trước khi đăng: mô hình tạo ảnh hay viết sai nhãn và tự thêm mũi tên.

## Bắt đầu

| Video | Bài viết | Bạn sẽ hiểu |
| --- | --- | --- |
| [▶ 1](https://media.zerohostcms.com/astra-cms/astra-video-1-quickstart-vi.mp4) <sub>2:32</sub> | [Astra là gì &amp; chạy trong 5 phút](./01-quickstart.vi.md) | Kiến trúc kernel + DocType engine + plugin host, rồi dựng cả cụm k3d bằng <code>make k3d-up</code>, nạp dữ liệu qua REST API và đăng nhập Desk. |
| [▶ 2](https://media.zerohostcms.com/astra-cms/astra-video-2-doctype-vi.mp4) <sub>2:38</sub> | [DocType: từ một file YAML đến API, giao diện và index](./02-doctype.vi.md) | Một file YAML sinh ra form, REST API, validation và index biểu thức online; mọi DocType chung một bảng JSONB, không ALTER TABLE. |
| [▶ 3](https://media.zerohostcms.com/astra-cms/astra-video-3-hooks-vi.mp4) <sub>2:53</sub> | [Quy tắc nghiệp vụ: hook &amp; validation](./03-hooks.vi.md) | Phí ship 73.000 ₫ do hook tính, lỗi validation trả về cùng lúc, Link kiểm tra trong tenant; Desk và API gặp cùng một luật. |

## Chứng từ và quy trình nghiệp vụ

| Video | Bài viết | Bạn sẽ hiểu |
| --- | --- | --- |
| [▶ 4](https://media.zerohostcms.com/astra-cms/astra-video-4-lifecycle-vi.mp4) <sub>3:10</sub> | [Vòng đời chứng từ: Submit, Cancel, Amend](./04-lifecycle.vi.md) | Submit sinh vận đơn và bút toán trong một transaction; chứng từ đã duyệt bất biến; hủy ghi bút toán đảo; amend tạo bản sửa đổi. |
| [▶ 5](https://media.zerohostcms.com/astra-cms/astra-video-5-ops-vi.mp4) <sub>3:00</sub> | [Vận hành: máy trạng thái bưu kiện](./05-ops.vi.md) | Lọc theo trường projected, timeline quét mã, không nhảy cóc trạng thái, lỗi rồi thành công trên cùng form, không hủy được đơn đã giao. |
| [▶ 6](https://media.zerohostcms.com/astra-cms/astra-video-6-care-vi.mp4) <sub>2:30</sub> | [CSKH nhìn thấy vận hành](./06-care.vi.md) | Ticket tự lên mức High và SLA +24h theo từ khoá; thẻ CARE replica đọc bản sao do worker CDC giữ. |

## Nền tảng, đa thuê bao và vận hành

| Video | Bài viết | Bạn sẽ hiểu |
| --- | --- | --- |
| [▶ 7](https://media.zerohostcms.com/astra-cms/astra-video-7-tenants-vi.mp4) <sub>2:28</sub> | [Đa thuê bao](./07-tenants.vi.md) | Hai công ty trên cùng một server: cùng URL, dữ liệu khác hẳn; Link không vượt tenant, 403/400 khi không rõ tenant, RLS trả 0 dòng. |
| [▶ 8](https://media.zerohostcms.com/astra-cms/astra-video-8-failclosed-vi.mp4) <sub>2:02</sub> | [Bảo mật fail-closed](./08-failclosed.vi.md) | Kết nối bằng superuser thì kernel từ chối khởi động, trước cả khi Flyway chạy migration; role ứng dụng NOSUPERUSER NOBYPASSRLS. |
| [▶ 10](https://media.zerohostcms.com/astra-cms/astra-video-10-k8s-vi.mp4) <sub>2:06</sub> | [Chạy trên Kubernetes](./10-k8s.vi.md) | Hai pod kernel ghi song song mà chuỗi số không trùng, không lỗ; rolling restart dưới tải và phần còn thiếu (chưa có drain). |

## Xây dựng trên Astra

| Video | Bài viết | Bạn sẽ hiểu |
| --- | --- | --- |
| [▶ 9](https://media.zerohostcms.com/astra-cms/astra-video-9-developer-vi.mp4) <sub>2:22</sub> | [Dành cho developer: viết một app Astra](./09-developer.vi.md) | App là Java thường + YAML trên astra-spi; dev mode live reload thêm cột mới trong 0,73 s; 44 test tích hợp với PostgreSQL, Seata và Redis thật. |
| [▶ 13](https://media.zerohostcms.com/astra-cms/astra-video-13-overlay-vi.mp4) <sub>2:24</sub> | [Overlay theo tenant](./13-overlay.vi.md) | Hà Nội thêm trường VAT và vùng đảo bằng một file YAML, có hiệu lực ngay; app Express không đổi một dòng. |
| [▶ 14](https://media.zerohostcms.com/astra-cms/astra-video-14-wasm-vi.mp4) <sub>2:20</sub> | [Tier 2 Wasm: công thức cước của riêng tenant](./14-wasm.vi.md) | Khuyến mãi Tết viết bằng Rust (421 byte), bật lên đổi phí 73.000 → 51.000; vòng lặp vô hạn bị dừng ở 50 ms. |

## Danh tính, phân quyền và tiền

| Video | Bài viết | Bạn sẽ hiểu |
| --- | --- | --- |
| [▶ 11](https://media.zerohostcms.com/astra-cms/astra-video-11-keycloak-vi.mp4) <sub>2:18</sub> | [Keycloak: một danh tính, role theo tenant, quyền theo DocType](./11-keycloak.vi.md) | Đăng nhập PKCE theme Astra, role là nhóm Keycloak, quyền khai báo trên DocType; người lập phiếu chi không tự duyệt được (403). |
| [▶ 21](https://media.zerohostcms.com/astra-cms/astra-video-21-sso-vi.mp4) <sub>2:32</sub> | [SAML SSO và SCIM](./21-sso.vi.md) | Đăng nhập qua IdP SAML có chữ ký, nhóm của IdP thành role của tenant; SCIM 2.0 tạo, đổi nhóm, vô hiệu hoá và xoá user theo từng tenant, token của tenant khác bị chặn. |
| [▶ 12](https://media.zerohostcms.com/astra-cms/astra-video-12-ledger-vi.mp4) <sub>2:32</sub> | [Sổ cái append-only](./12-ledger.vi.md) | Bút toán kép đi cùng transaction của chứng từ, hủy là đảo, trigger chặn UPDATE kể cả chủ bảng, phân vùng theo tháng. |

## Tích hợp: agent AI, CDC, giao dịch phân tán

| Video | Bài viết | Bạn sẽ hiểu |
| --- | --- | --- |
| [▶ 15](https://media.zerohostcms.com/astra-cms/astra-video-15-mcp-vi.mp4) <sub>2:24</sub> | [MCP: agent AI dùng Astra bằng quyền của người dùng](./15-mcp.vi.md) | Claude thật gọi tool qua MCP: PII bị che, agent của nhân viên CSKH không tạo được đơn, kế toán hỏi COD trả lời từ sổ cái. |
| [▶ 16](https://media.zerohostcms.com/astra-cms/astra-video-16-cdc-vi.mp4) <sub>2:36</sub> | [Rust CDC + Sync-Aware LSN](./16-cdc.vi.md) | Worker Rust đọc WAL, lệnh ghi trả X-Tx-LSN, đọc replica chưa kịp thì nhận 202 Sync Pending thay vì dữ liệu cũ. |
| [▶ 17](https://media.zerohostcms.com/astra-cms/astra-video-17-seata-vi.mp4) <sub>2:26</sub> | [Seata: một lần submit, ba tài nguyên](./17-seata.vi.md) | Nhánh AT trừ hạn mức BMS, nhánh TCC giữ chỗ trên Redis, kernel commit cuối; một nhánh từ chối là tất cả quay lại. |

## App nghiệp vụ

| Video | Bài viết | Bạn sẽ hiểu |
| --- | --- | --- |
| [▶ 19](https://media.zerohostcms.com/astra-cms/astra-video-19-hrm-vi.mp4) <sub>2:40</sub> | [HRM: nhân sự và tiền lương](./19-hrm.vi.md) | Theo khái niệm Frappe HRMS: chấm công quyết định ngày công, Payroll Entry tính phiếu lương song song, thưởng bưu tá bằng Wasm, một bút toán dồn tích cho cả kỳ, huỷ là đảo; agent AI chỉ thấy tên đã che. |
| [▶ 20](https://media.zerohostcms.com/astra-cms/astra-video-20-stms-vi.mp4) <sub>2:58</sub> | [STMS: điều phối xe với Timefold + AI copilot](./20-stms.vi.md) | Timefold xếp tuyến theo tải trọng và khung giờ, giải lại khi xe hỏng, đơn gấp, kẹt xe; copilot nhận lệnh tiếng Việt và gọi tool thật. LLM của copilot là giả lập theo kịch bản demo, ghi rõ trên mọi khung hình. |

## Phần tiếp theo

| Video | Bài viết | Bạn sẽ hiểu |
| --- | --- | --- |
| [▶ 18](https://media.zerohostcms.com/astra-cms/astra-video-18-roadmap-vi.mp4) <sub>1:36</sub> | [phần còn lại của use-case-01](./18-roadmap.vi.md) | Những gì video 1–17 và 19–21 đã chạy thật (gồm HRM, STMS, SAML và SCIM) và những gì còn đang phát triển: multi-schema, Tier 1, streaming CDC, agent tự hành, audit hash-chain, mã hoá trường. |
