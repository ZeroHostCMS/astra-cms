# Phase 3 · Plugin mẫu Lớp 2: hãng vận chuyển và hoá đơn điện tử theo nước (Tier 1)

## Mục tiêu
Sample code video chiếu cho hai ô của Lớp 2 trên slide:
- **Hãng vận chuyển · 3PL last-mile**: chứng minh hot-swap, canary theo pod, tự rollback, capability.
- **Hoá đơn điện tử · thuế từng nước**: chứng minh "thêm nước = thêm mô-đun": mỗi tenant ghim plugin của nước mình, thêm nước mới không khởi động lại.

## SPI (`astra-spi`, cạnh `CarrierAdapter` của phase 1)
- `io.astra.spi.integration.EInvoiceProvider`: `String country()`, `IssuedInvoice issue(InvoiceRequest r)`; `InvoiceRequest`/`IssuedInvoice` là `record`
  (người mua, MST/mã thuế, dòng hàng, VAT, tiền tệ → số hoá đơn, mã tra cứu, trạng thái).

## Plugin (`astra-apps/express-integrations/`, mỗi plugin một Maven module, chỉ phụ thuộc `astra-spi` scope `provided`)
| Plugin | Phiên bản | Hành vi |
| --- | --- | --- |
| `fastship` | 1.0.0 | `POST /fastship/bookings` lên partner sandbox, trả mã vận đơn + phí |
| `fastship` | 1.1.0 | thêm ETA, chọn dịch vụ nhanh cho zone `Same city`; một lời gọi thử `https://example.com` để cảnh demo thấy bị từ chối |
| `fastship` | 1.2.0-bad | ném lỗi với zone `Remote` (cố ý) để chứng minh tự rollback |
| `einvoice-vn` | 1.0.0 | VND, VAT 8/10 %, ký hiệu mẫu số, gọi `/tax/vn/invoices` |
| `einvoice-kh` | 1.0.0 | USD/KHR, VAT 10 %, song ngữ Khmer/Anh, gọi `/tax/kh/invoices` |

Mỗi jar có `META-INF/astra-app.yaml`: `tier: isolated-jvm`, `capabilities.network` chỉ host sandbox tương ứng, `runtime: { timeout: 300ms, max-concurrency: 32 }`.
`scripts/build-plugins.sh` build tất cả jar vào `astra-apps/express-integrations/plugins/`.

## Partner sandbox
`tools/partner-sandbox/` + `deploy/k8s/base/partner-sandbox.yaml`: một HTTP service nhỏ đóng vai sandbox của 3PL và cơ quan thuế
(`/fastship/*`, `/tax/vn/*`, `/tax/kh/*`), trả mã, độ trễ 20–80 ms. Video nói rõ đây là sandbox, giống sandbox các đối tác thật cấp cho tích hợp.

## Kernel / app Express (Tier 0)
- `CarrierBookingHook` (`afterSave` khi Waybill được tạo) chỉ đặt `carrier_status = Pending` trong transaction của submit. `CarrierBookingJob` (2 s) nhận đơn Pending bằng khoá Redis theo vận đơn (`Counters.markOnce`, đúng vì khoá này theo tenant), gọi `resolve("carrier.booking", tenant, customer?)`, rồi ghi `carrier`, `carrier_tracking`,
  `carrier_eta`, `booked_by` (`fastship@1.1.0 on <pod>`). Gọi ra partner không nằm trong transaction submit. Plugin lỗi không làm hỏng Waybill: ghi `booked_by = "pending (… failed)"`, đếm lỗi.
<!-- Updated: Validation Session 1 - carrier HTTP moved out of submit -->
- `EInvoiceHook` (`onSubmit` của CustomerOrder): chỉ đặt `einvoice_status = Pending` trong transaction. `EInvoiceJob` (2 s) trên mọi pod
  nhận đơn Pending bằng khoá Redis theo đơn, gọi plugin của slot `einvoice.issue` theo `AppPin` của tenant, ghi `einvoice_no`,
  `einvoice_lookup`, `einvoice_by`. Gọi ra ngoài không nằm trong transaction submit.
- DocType: Waybill thêm 4 field carrier; CustomerOrder thêm 4 field einvoice (zero-DDL, JSONB).

## Tenant thứ ba: `angkor-express` (Campuchia)
- Tenant config `country: KH`, `currency: USD`; user Keycloak `sophea` (admin) trong `deploy/keycloak/realm/astra-realm.json`;
  `demo-seed.sh` / `demo-reset.sh` thêm tenant này (khách, tài khoản tối thiểu).
- Cảnh demo: tenant mới chưa có plugin hoá đơn → đơn submit ở trạng thái `einvoice_status = Pending (no provider)`;
  upload + duyệt `einvoice-kh`, ghim cho `angkor-express` → job phát hành hoá đơn cho đơn đang chờ, không khởi động lại pod nào.

## Ranh giới Tier 0 ↔ Tier 1
`PluginContext` không có `DocumentService` hay `GeneralLedger` → plugin không thể ghi sổ cái. Cảnh T0 chiếu điều này cạnh
`OrderPostingHook` (Tier 0) ghi bút toán.

## Test
Đơn → Waybill có `booked_by`; đổi release giữa chừng không lỗi đơn nào; bản `-bad` bị rollback; saigon-express ra hoá đơn VN,
angkor-express ra hoá đơn KH cùng lúc; plugin hoá đơn lỗi không chặn submit.
