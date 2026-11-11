# Luật giá là dữ liệu (Drools, Tier 0)

Bảng cước của Astra Express không còn hard code trong Java. Giá chung của mỗi tenant là một **bộ luật Drools** (DRL) do BA soạn trên Desk, lưu như dữ liệu, duyệt và phát hành như code: không build, không khởi động lại pod. Engine là Apache KIE Drools **10.1.0**, ghim cứng (KIE chưa công bố hỗ trợ Java 25; bản này đã chạy thử trên đúng image của cụm).

## Luồng làm việc

1. BA mở DocType **PricingRuleset**, dán hoặc sửa luật, lưu. Khi lưu, mọi từ trong luật được đối chiếu với danh sách cho phép, luật được biên dịch và chạy thử trên một đơn mẫu; sai cú pháp hoặc dùng thứ không được phép thì bị từ chối kèm số dòng.
2. BA **Submit** bộ luật: hệ thống tạo bản nháp `AppRelease` (slot `pricing.base`, tier `rules`).
3. Admin **Submit** release (duyệt). Slot chưa có luật Live thì lên Live ngay; có rồi thì canary trên một pod, đủ số lời gọi khoẻ thì lên cả cụm, lỗi vượt ngân sách thì tự rollback.

Không có bộ luật Live nào thì đơn tính theo bảng cước dự phòng trong `TariffLines` (cùng số với `standard-tariff.drl`).

## Viết luật

```drl
package astra.express.pricing

import io.astra.spi.pricing.QuoteRequest
global io.astra.spi.pricing.QuoteLines lines

rule "base-remote"
    salience 100
when
    QuoteRequest(zone == "Remote", $kg : weightKg)
then
    lines.add("base", "Cước nền vùng xa", 45000 + 7000 * (long) Math.ceil(Math.max(0, $kg - 1) / 0.5));
end
```

Bộ luật chuẩn: [`standard-tariff.drl`](../astra-apps/express/src/main/resources/META-INF/astra/rules/standard-tariff.drl), tái tạo đúng bảng cước cũ (kiểm trên 864 tổ hợp vùng × dịch vụ × cân × COD trong `StandardTariffRulesTest`). Ví dụ luật thêm: [`peak-1111.drl`](../astra-apps/express/src/main/resources/META-INF/astra/rules/peak-1111.drl).

**Fact `QuoteRequest`** (đọc qua thuộc tính, không sửa được): `zone`, `service`, `express`, `weightKg`, `lengthCm`, `widthCm`, `heightCm`, `volumetricKg` (D×R×C/6000), `codAmount`, `customer`, `segment`, `day`, `month`.

**Global `lines`** (thứ duy nhất luật được gọi):

| Gọi | Tác dụng |
| --- | --- |
| `lines.add(code, label, amount)` | thêm một dòng giá; `code` không được trùng; dòng ghi tên luật đã thêm nó (`rule:<tên luật>`) |
| `lines.amount(code)`, `lines.has(code)`, `lines.total()` | đọc các dòng đã thêm (dùng `salience` để định thứ tự) |
| `lines.lock(code)` | khoá dòng: giá riêng của khách không sửa hay bỏ được (ví dụ phụ phí bắt buộc) |
| `lines.floor(amount)` | sàn tổng: giá riêng của khách không kéo tổng xuống dưới mức này |

`code` của dòng (`base`, `express`, `cod_fee`, `peak`…) là hợp đồng giữa luật và các plugin giá riêng: đổi tên `code` là thay đổi phá vỡ với mọi plugin đang dùng nó.

## Danh sách cho phép (ranh giới an toàn)

Luật chạy trong JVM lõi, nên trước khi biên dịch, `RuleValidator` kiểm **từng từ** theo danh sách cho phép, không theo danh sách cấm:

- chỉ `package`, `import io.astra.spi.pricing.QuoteRequest|QuoteLines`, `global QuoteLines lines`, `rule … end`;
- trong luật: từ khoá DRL (`when`, `then`, `salience`, `no-loop`, `date-effective`, `not`, `in`, `exists`…), thuộc tính của `QuoteRequest`, `lines` và sáu hàm của nó, `Math.ceil|floor|min|max|round|abs`, ép kiểu `long|int|double`, biến `$…`, số và chuỗi;
- không có dấu `\` ở bất cứ đâu (chặn Unicode escape trong code Java sinh ra), không có dấu nháy đơn `'` và text block `"""`: chuỗi viết trong nháy kép. Bộ kiểm tra xoá nội dung chuỗi trước khi soát từ, nên ranh giới chuỗi của nó phải trùng với của trình biên dịch; một ký tự `'"'` từng làm hai bên lệch nhau và giấu được code Java trong "chuỗi" (lỗi tìm thấy khi review bảo mật, có test hồi quy).

Vì vậy những thứ sau bị từ chối khi lưu: `System`, `Runtime`, `Class`, reflection, `new`, tên đầy đủ (`java.lang…`), `eval`, `from`, `accumulate`, `function`, `query`, `declare`, `insert`/`modify`/`delete`, `drools`, `kcontext`, `.class`, `dialect "mvel"`, `matches` (biểu thức chính quy không dừng ở deadline). Kiểm thử: `RuleValidatorTest`.

Khi chạy: mỗi đơn một session mới trên virtual thread, **deadline 50 ms**, tối đa 500 luật được kích hoạt, `KieBase` cache theo SHA-256 (16 bộ gần nhất) và được làm nóng một lần ngay sau khi biên dịch. Luật lỗi lúc chạy không định giá đơn: đơn lấy bảng cước dự phòng, `priced_by` ghi lý do, release bị đếm lỗi (đủ nhiều thì canary tự rollback).

## Thứ tự các tầng giá

1. **Luật** (`pricing.base`): các dòng nền.
2. **`PricingRule` đang bật** khớp segment của khách (module Wasm của video 14): giá trị `fee` **thay dòng `base`**, các dòng khác giữ nguyên.
3. **Plugin giá riêng của khách** (`pricing.customer`, Wasm): nhận các dòng, trả thao tác `scale`/`set`/`remove`/`add` (xem [overlay-wasm.md §4.2](overlay-wasm.md)). Dòng `locked` và `floor` của luật được host bảo vệ.
4. Làm tròn lên nghìn đồng (dòng `rounding`). `shipping_fee` là tổng các dòng; `price_breakdown` ghi nguồn gốc từng dòng.

## Luật hay plugin?

| | Luật Drools (Tier 0) | Plugin giá riêng (Tier 2) |
| --- | --- | --- |
| Dùng cho | bảng cước chung, phụ phí, khuyến mãi theo điều kiện | điều khoản riêng của một khách: bậc thang sản lượng, điều kiện chéo, trần/sàn, ngoại lệ theo thời điểm |
| Ai viết | BA, dạng khai báo, ai cũng đọc được | BA/PO nhờ AI viết theo phụ lục hợp đồng, người có thẩm quyền duyệt |
| Phạm vi | mọi đơn của tenant | chỉ đơn của khách được gắn |
| Thay đổi | lưu → duyệt → canary, vài giây | đề xuất → duyệt → canary, hot-reload vài giây |

Viết điều khoản của một khách bằng luật thì mỗi khách thêm nhiều luật đan vào luật chung, thứ tự ưu tiên khó đọc; viết bằng plugin thì luật chung giữ gọn, plugin chỉ ghi phần khác biệt và **tự kế thừa** mọi thay đổi của luật chung. So sánh trên phụ lục của Shop ABC: [demo-data/shop-abc-as-rules.drl](demo-data/shop-abc-as-rules.drl).
