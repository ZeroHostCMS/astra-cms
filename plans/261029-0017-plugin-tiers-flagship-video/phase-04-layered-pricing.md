# Phase 4 · Định giá xếp tầng: Rule Engine Drools (Tier 0) + plugin "Khai giá Astra" (Wasm hot-reload, AI cấu hình)

## Mục tiêu
Hai ví dụ làm rõ tính ưu việt trên cùng một bài toán: **báo giá cước cho từng khách**. Video đặt ba cách đổi giá cạnh nhau:

| Cách | Ai sửa | Thời gian lên vận hành | Phù hợp |
| --- | --- | --- | --- |
| Hard code (`TARIFF` trong `ShippingFeeHook` hiện tại) | dev | build + deploy | không nên dùng cho giá |
| **Drools, luật là dữ liệu** (Tier 0) | BA trên Desk, có duyệt | vài giây, không khởi động lại | bảng cước chuẩn, phụ phí, khuyến mãi theo điều kiện: luật khai báo, ai cũng đọc được |
| **Wasm "Khai giá Astra"** (Tier 2) | BA/PO viết bằng AI theo điều khoản hợp đồng, người có thẩm quyền duyệt | vài giây, hot-reload | điều khoản riêng từng khách mà luật hay hard code sẽ phình to: bậc thang sản lượng, điều kiện chéo, trần/sàn, ngoại lệ theo thời điểm |

Luồng một đơn, luôn ba tầng khi tầng đó có bản: **Drools tính trước** ra danh sách dòng giá; **`PricingRule.active` của video 14, nếu khớp segment, thay đúng dòng `base`** (module `fee` không còn ghi đè cả phí); **plugin riêng (Wasm) kế thừa** danh sách đó và
chỉ khai báo phần khác biệt (giảm %, đặt lại, bỏ, thêm dòng). Plugin **không tính lại từ đầu**: dòng nào nó không đụng thì giữ nguyên.
Đơn lưu `price_breakdown` ghi mỗi dòng: nguồn gốc (`rule:base-domestic@v3` hoặc `pricing-rule:<tên>` khi dòng `base` bị thay), thao tác của plugin nếu có
(`wasm:khaigia-shopabc@1.1.0 scale 0.92`), số tiền trước và sau. `shipping_fee` là tổng các dòng.
<!-- Updated: Validation Session 1 - PricingRule is the middle layer -->

Hệ quả cần chứng minh trong video:
- BA đổi luật chung (thêm phụ phí 11.11, tăng giá cước nền) → khách có plugin riêng **tự kế thừa** thay đổi đó, không ai sửa plugin.
- Sửa plugin (phụ lục v2) chỉ là sửa phần khác biệt, vài dòng; không đụng tới cách tầng Rule tính cước.

## A. Rule Engine Drools ở Tier 0, luật là dữ liệu
- Phụ thuộc: **Apache KIE Drools 10.1.0, ghim cứng phiên bản** (quyết định user 2026-11-10): `drools-engine`, `drools-engine-classic`,
  `drools-mvel` (compile DRL lúc chạy). Thiếu `drools-engine-classic`/`drools-mvel` là nguyên nhân thường gặp của
  `UnsupportedOperationException` trong `DummyConstraintBuilder`, nên khai báo đủ cả ba. Spike Java 25 + Quarkus ở phase 0.
- `astra-kernel`: `RuleEngine` service giữ `KieBase` theo `(tenant, ruleset, version)`, biên dịch lười, cache theo SHA-256 của DRL;
  fact model cố định trong `astra-spi`: `io.astra.spi.pricing.QuoteRequest` (zone, dịch vụ, cân, kích thước, COD, khách, segment, thời điểm)
  và `QuoteLines` (thêm dòng cước có `code` ổn định, `label`, tên luật; luật có thể đánh dấu dòng `locked` và đặt `floor` cho tổng).
  SPI mới `PricingEngine.quote(QuoteRequest) → RuleQuote`. `code` của dòng (`base`, `express`, `cod_fee`, `peak`, `vat`…) là hợp đồng
  giữa tầng Rule và plugin: đổi tên `code` là thay đổi phá vỡ, validator cảnh báo khi một ruleset mới bỏ `code` mà plugin đang dùng.
- Luật đi qua **cùng đường phát hành** của phase 2: `AppRelease` với `tier: rules`, `slot: pricing.base`, artifact là file `.drl`.
  BA soạn/sửa trên Desk (field Code của DocType `PricingRuleset` gắn vào release), admin duyệt bằng Submit, canary theo pod, tự rollback.
- An toàn (luật chạy trong JVM lõi nên phải chặn chặt):
  - khi lưu: biên dịch thử, lỗi cú pháp → từ chối kèm dòng lỗi;
  - validator chỉ cho `import` các class trong `io.astra.spi.pricing.*` và `java.math.*`; từ chối `System`, `Runtime`, `Class`, reflection,
    `Thread`, I/O, `eval`, `modify` ngoài fact model; RHS chỉ được gọi API của `QuoteLines`;
  - chạy có deadline (50 ms) và giới hạn số lần fire luật; lỗi lúc chạy → giữ ruleset Live trước, đếm lỗi cho controller.
- Ruleset mặc định `standard-tariff.drl` tái tạo đúng `TARIFF` hiện tại (Same city / Domestic / Remote, Express ×1,5, COD 1 % tối thiểu 5.000)
  thành các dòng (`base`, `express`, `cod_fee`, …). `shipping_fee` là tổng các dòng sau khi áp hết tầng. Khi không có `PricingRule` và không có plugin khách, tổng này bằng phí hard code cũ.
  `ShippingFeeHook` bỏ nhánh trả về sớm: hôm nay `PricingRule.active` ghi một `shipping_fee` rồi `return`. Sau phase này hàm `fee` của module video 14 chỉ thay dòng `base`; các dòng Drools còn lại giữ nguyên, rồi tới plugin khách.
  Bảng `TARIFF` chỉ còn dự phòng khi chưa có ruleset nào Live.
<!-- Updated: Validation Session 1 - three layers always; scene 12 is a sum -->
- Cảnh demo: BA thêm luật "phụ phí cao điểm 11.11: +5.000 ₫ zone Remote, từ 10/11 đến 12/11" trên Desk → duyệt → đơn mới có dòng
  `rule:peak-1111`, không pod nào khởi động lại. Một luật sai cú pháp và một luật có `System.exit` bị từ chối ngay khi lưu.

## B. Plugin "Khai giá Astra" (Tier 2 Wasm, hot-reload, BA/PO viết bằng AI, kế thừa tầng Rule)
- Slot `pricing.customer`, gắn theo **khách** (phase 2: `AppPin` có thêm phạm vi `customer`).
- **Mô hình kế thừa:**
  - Đầu vào plugin: `RuleQuote` = các dòng giá tầng Rule đã tính (`code`, `label`, `amount`, `rule`, `locked`) + ngữ cảnh đơn
    (zone, dịch vụ, cân, thể tích, COD, thời điểm) + hồ sơ khách (sản lượng tháng, tỉ lệ hoàn).
  - Đầu ra plugin: danh sách **thao tác trên dòng**, không phải một con số: `scale(code, factor)`, `set(code, amount)`,
    `remove(code)`, `add(code, label, amount)`, mỗi thao tác kèm `reason` ngắn (dẫn điều khoản hợp đồng).
  - Host áp thao tác lên các dòng của tầng Rule; dòng không bị nhắc tới giữ nguyên. Tổng cuối = tổng các dòng sau khi áp.
  - Rào chắn ở host: dòng `locked` (luật đánh dấu, ví dụ VAT, phí bắt buộc theo luật) không sửa được; tổng không dưới `floor` của tầng Rule;
    mỗi thao tác bị kiểm và ghi lại; thao tác không hợp lệ → bỏ cả kết quả plugin, dùng giá tầng Rule, đếm lỗi cho controller.
- **ABI mới, vẫn không host function:** module export `alloc(len) -> ptr` và `adjust(ptr, len) -> i64` (ptr/len đầu ra gói trong một i64);
  host ghi `RuleQuote` vào bộ nhớ tuyến tính của module và đọc danh sách thao tác ra. `ScriptRuntime` thêm
  `byte[] callBytes(module, function, timeoutMillis, byte[] input)` bên cạnh `callLong` (giữ nguyên cho video 14); vẫn giới hạn trang bộ nhớ,
  deadline, từ chối import. Mã hoá đầu vào/ra do PDK và host lo, BA/AI không phải đụng.
- **PDK Rust** `astra-apps/express/wasm/astra-pricing-pdk/`: macro + kiểu `RuleQuote`, `Line`, `Op`, để module của khách chỉ còn
  một hàm `fn adjust(q: &RuleQuote) -> Vec<Op>` viết đúng các điều khoản. Ghi ABI + PDK vào `docs/overlay-wasm.md` để AI đọc.
- Khách mẫu: **Shop ABC** (TMĐT, segment B2B) với phụ lục hợp đồng viết bằng lời trong `docs/demo-data/shop-abc-contract-v1.md`
  (dữ liệu demo), ví dụ: bậc thang theo sản lượng tháng (≤500 đơn 100 %, 501–2.000 đơn 92 %, >2.000 đơn 85 %); miễn phụ phí COD nếu
  tỉ lệ hoàn < 5 %; hàng cồng kềnh tính theo cân quy đổi `D×R×C/6000` nhưng trần +40 % so với cân thật; tối thiểu 15.000 ₫/đơn.
  Phụ lục v2 thêm: tuần lễ 11.11 giữ giá bậc cao nhất bất kể sản lượng.
  Ánh xạ sang thao tác kế thừa: bậc thang → `scale("base", 0.92 | 0.85)`; miễn COD → `remove("cod_fee")`; cồng kềnh →
  `add("bulky", …)` tính trên cân quy đổi có trần; tối thiểu → `set` trên dòng `base` khi tổng thấp hơn 15.000 ₫. Các dòng khác
  (Express, phụ phí 11.11, VAT) giữ nguyên từ tầng Rule.
  Viết bằng luật Drools thì cần nhiều luật riêng cho một khách đan với luật chung, thứ tự ưu tiên khó đọc; hard code thì mỗi khách một lần
  deploy. Video chiếu số luật Drools tương đương (đếm thật từ bản thử viết) cạnh hàm `adjust` khoảng 30 dòng Rust.
- MCP tools (app Express, implement `McpTool`): `customer_pricing_profile` (read: sản lượng, tỉ lệ hoàn, phân bố zone/cân của khách
  30 ngày), `quote_backtest` (read: chạy module trên đơn tháng trước của khách, trả từng đơn trước/sau và tổng chênh doanh thu).
  `release_propose` / `release_status` dùng chung từ phase 2.
- Phiên Claude Code (Claude chạy, ghi asciinema), người dùng đóng vai BA/PO (không phải dev):
  > "Đây là phụ lục hợp đồng của Shop ABC (docs/demo-data/shop-abc-contract-v1.md). Xem hồ sơ giao hàng của khách qua MCP, viết plugin
  > khai giá bằng Rust với astra-pricing-pdk theo docs/overlay-wasm.md, chỉ điều chỉnh trên giá tầng Rule, backtest trên đơn tháng trước, rồi đề xuất phát hành cho riêng khách này."
  → Claude viết module, build, backtest (bảng chênh lệch), `release_propose` gắn `customer: Shop ABC`; `mai` duyệt → đơn mới của Shop ABC
  có các dòng gốc từ Rule kèm thao tác `wasm:khaigia-shopabc@1.0.0`, khách khác không đổi.
  → **Chứng minh kế thừa:** BA bật luật `peak-1111` ở tầng Rule (cảnh T0b) → đơn mới của Shop ABC tự có dòng phụ phí 11.11, plugin không đổi.
  → Phụ lục v2 (tuần 11.11 giữ bậc giá cao nhất) → Claude chỉ sửa nhánh bậc thang trong `adjust` (diff vài dòng) → duyệt → **hot-reload**
  trong vài giây khi traffic đang chạy, `fail=0`.

## Test
- `standard-tariff.drl` cho đúng số của bảng cũ trên lưới zone × dịch vụ × cân × COD.
- DRL sai cú pháp, import ngoài danh sách, gọi `System`/reflection → bị từ chối khi lưu; luật chạy quá deadline → giữ bản trước.
- Ruleset mới Live trên cả 2 pod sau duyệt, không khởi động lại.
- Module Khai giá mẫu tính đúng các điều khoản v1/v2 trên đơn có nhãn; chỉ áp cho khách được ghim; `price_breakdown` đủ các dòng, mỗi dòng có nguồn gốc.
- Kế thừa: thêm/sửa một luật ở tầng Rule → giá của khách có plugin đổi theo đúng dòng đó mà không đổi plugin; dòng plugin không nhắc tới giữ nguyên.
- Rào chắn: thao tác lên dòng `locked`, tổng dưới `floor`, `code` không tồn tại → bị từ chối, đơn dùng giá tầng Rule và có ghi lý do.
- `callBytes`: vượt trang bộ nhớ, quá deadline, trả ptr/len ngoài bộ nhớ → `ScriptException`. `callLong` giữ nguyên chữ ký; giá trị trả về của `fee` được ghi vào dòng `base`, không còn là cả `shipping_fee`.
- Thứ tự áp, luôn cả ba tầng: Drools (các dòng nền) → `PricingRule.active` nếu khớp segment (thay đúng dòng `base`) → plugin của khách kế thừa các dòng kết quả. `shipping_fee` = tổng các dòng. Cảnh 12 / video 14 quay lại theo tổng này; số trên hình đổi so với bản đang có mỗi khi còn dòng express, COD hoặc phụ phí.

## Rủi ro
- ABI bộ nhớ mới là bề mặt tấn công mới của sandbox: host kiểm ptr/len, giới hạn kích thước đầu ra, fuzz test trên đầu vào lỗi.
- Apache KIE chưa hỗ trợ chính thức Java 25 (khuyến nghị JDK 17/21); 10.1.0 chạy được ở mức cơ bản theo báo cáo cộng đồng. Spike xác nhận
  trên đúng image k3d; không nâng lên 10.2+ cho tới khi KIE công bố hỗ trợ Java 25. Phương án dự phòng là
  executable model biên dịch trong một ClassLoader riêng mỗi ruleset (tái dùng `PluginClassLoader` của phase 1).
- Rò Metaspace khi đổi ruleset nhiều lần: mỗi `KieBase` cũ phải được giải phóng; thêm test như test rò ClassLoader.
- Luật là dữ liệu chạy trong lõi: validator là ranh giới an toàn chính; `ak:code-review` và `ak:security` soát kỹ ở phase 8.
