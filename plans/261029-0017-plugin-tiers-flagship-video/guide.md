# Guide: Plugin Tier 0 · 1 · 2, vừa an toàn vừa động, sẵn cho phát triển bằng AI

Guide đi kèm [plan.md](plan.md). Plan nói **làm gì**; guide này nói **hệ thống chạy thế nào, tại sao thiết kế như vậy, sample code
và cách tự chạy, tự mở rộng**. Mọi đoạn code có ghi đường dẫn là trích từ nhánh `feat/plugin-tiers-demo` (đã hiện thực, 104 test xanh,
`make demo-tiers` chạy `fail=0`). Đoạn nào ghi **"ví dụ mới"** là mẫu để bạn tự viết, chưa có trong repo.

Đọc cho: dev lõi, dev tích hợp, BA/PO dùng AI để cấu hình phần mềm, người review/duyệt phát hành.

---

## 1. Ý tưởng trong một trang

Phần mềm doanh nghiệp luôn bị kéo về hai phía: **cần đổi nhanh** (giá theo từng khách, thêm hãng vận chuyển, thêm nước, luật mới mỗi
tuần) và **không được hỏng** (sổ cái, tiền, dữ liệu khách). Cách thường gặp là chọn một phía: hoặc hard code rồi deploy (an toàn nhưng chậm),
hoặc cho script tuỳ ý (nhanh nhưng mất kiểm soát).

Astra tách theo một nguyên tắc: **càng ít tin cậy thì càng được động, nhưng càng bị nhốt chặt**. Mọi thứ động đi qua **một cửa phát hành
duy nhất** có duyệt, canary và tự rollback.

| Cách đổi hành vi | Ai viết | Chạy ở đâu | Quyền | Lên vận hành | Chi phí gọi (p50, `TierDispatchBench`) |
| --- | --- | --- | --- | --- | --- |
| **Tier 0** · lõi biên dịch vào image | dev lõi | trong JVM lõi | đầy đủ, **chỉ Tier 0 ghi sổ cái** | build + rolling update, 0 request lỗi | 0,04 µs |
| **Tier 0 · luật Drools là dữ liệu** | BA trên Desk | engine trong lõi, luật bị allowlist | [chỉ thêm dòng giá](#12-chỉ-thêm-dòng-giá-luật-drools-cụ-thể-là-gì) | lưu → duyệt → canary, vài giây | (trong lõi) |
| **Tier 1** · plugin JVM cô lập | dev đối tác / tích hợp (tin cậy) | ClassLoader riêng mỗi phiên bản | [chỉ SPI + mạng được cấp](#11-chỉ-spi--mạng-được-cấp-tier-1-cụ-thể-là-gì) | upload → duyệt → hot-swap theo pod | 5,0 µs |
| **Tier 2** · Wasm sandbox | BA/PO + AI (không tin cậy) | sandbox, không host function | chỉ tính toán | upload → duyệt → hot-reload | 10,5 µs |

Ba tính chất mà mọi tầng động đều có, và là lý do hệ thống "dễ kiểm soát":

1. **Đề xuất ≠ duyệt.** Ai cũng có thể tạo bản nháp (kể cả AI), chỉ `admin` được Submit. Token AI (`release-bot`) submit → 403.
2. **Lỗi không lan.** Plugin lỗi, quá giờ, trả kết quả bậy → hệ thống lùi về tầng trước (bảng cước, giá tầng Rule, bản Live cũ). Không
   bao giờ chặn đơn.
3. **Mọi kết quả có nguồn gốc.** Mỗi dòng giá ghi luật/plugin sinh ra nó; mỗi vận đơn ghi `fastship@1.1.0 on <pod>`; mỗi response có
   header `X-Astra-Pod`; mỗi release ghi SHA-256, `generator` (human/claude-code) và người duyệt.

### 1.1 "Chỉ SPI + mạng được cấp" (Tier 1) cụ thể là gì

Plugin Tier 1 bị giới hạn ở bốn chỗ: thấy được class nào, nhận được đối tượng nào, được gọi lúc nào, và gọi mạng tới đâu.

| Mặt | Plugin được | Plugin không được | Cơ chế (file) |
| --- | --- | --- | --- |
| **Class nhìn thấy** | class của JDK (qua platform loader), `io.astra.spi.*`, class trong chính jar của nó | `io.astra.kernel.*`, `io.astra.doctype.*`, Quarkus, Jackson, Hibernate, class của plugin khác hay của phiên bản khác cùng id → `ClassNotFoundException` | `PluginClassLoader` + parent `SpiOnly`; SPI luôn lấy từ host kể cả khi jar nhét bản sao |
| **Đối tượng nhận được** | đúng bốn thứ trong `PluginContext`: `identity()`, `http()` (`PartnerHttp`), `config()` (khối `config` của manifest), `log()` | `DocumentService`, `GeneralLedger`, `DataSource`, Redis, CDI bean, token người dùng. Không có tham chiếu thì không gọi được: plugin không đọc/ghi chứng từ, không ghi sổ cái | `PluginContext`, `LoadedPlugin.Context` |
| **Lúc được chạy** | khi host gọi một method của SPI đã khai (`CarrierAdapter.book(Shipment)`), dữ liệu vào/ra là `record` của SPI | tự chạy theo ý mình; trả kiểu riêng của plugin qua ranh giới | `MethodHandle` bind lúc nạp; manifest phải khai `spi` là interface `io.astra.spi.*` mà entrypoint implement; jar chỉ nạp **sau khi duyệt** |
| **Mạng** | `http().get(url)` và `http().postJson(url, json)` tới **origin** có trong `capabilities.network`. Origin = scheme + host + port, so khớp chính xác (`http://partner-sandbox:8090`) | origin khác (`https://example.com`, cùng host khác port, khác scheme) → `CapabilityDeniedException`, log `PartnerHttp denied <url> for <id@version>`; lỗi đó lọt ra khỏi lời gọi thì thành `PluginException("denied")` và tính là lỗi cho circuit breaker + health canary | `GrantedPartnerHttp.check`, `PluginManifest.origin` |
| **Tài nguyên** | tối đa `max-concurrency` lời gọi cùng lúc, mỗi lời gọi trong `timeout` (FastShip: 32, 300 ms; HTTP cũng bị cắt theo timeout này) | vượt → từ chối ngay (`rejected`), quá giờ → `timeout`; tỉ lệ lỗi ≥ `failure-rate` trong `window` → `open`, không gọi nữa trong một cửa sổ | `LoadedPlugin.invoke`, `CircuitBreaker` |

Ví dụ trong manifest FastShip 1.1.0: `capabilities.network: ["http://partner-sandbox:8090"]`. Code gọi `https://example.com/geo/hints`
→ bị từ chối, plugin bắt lỗi và đặt vận đơn tiếp bình thường. Test: `IsolatedJvmHostTest` (không nạp được class kernel, reason `denied`).

**Giới hạn cần nói thẳng:** "chỉ SPI" là giới hạn **class của host** mà plugin thấy, không phải giới hạn API của JDK. Platform loader vẫn
cho `java.net.Socket`, `java.net.http.HttpClient`, `java.io.File`, `Thread`… Java 25 không còn SecurityManager, nên một plugin cố ý có thể
tự mở socket hoặc đọc file mà không qua `PartnerHttp`. `PartnerHttp` là **cửa mạng chính thức có kiểm soát và audit**, không phải tường lửa.
Vì vậy Tier 1 chỉ dành cho code tin cậy (có review, có người ký). Muốn chặn ở mức mạng thì cần thêm egress `NetworkPolicy` cho pod (repo
chưa có). Code không tin cậy, kể cả code AI viết, đi Tier 2.

### 1.2 "Chỉ thêm dòng giá" (luật Drools) cụ thể là gì

Một bộ luật chỉ có một đầu vào và một đầu ra:

- **Đầu vào, chỉ đọc:** fact `QuoteRequest` với các thuộc tính `zone`, `service`, `express`, `weightKg`, `lengthCm`, `widthCm`,
  `heightCm`, `volumetricKg`, `codAmount`, `customer`, `segment`, `date`, `day`, `month`. `QuoteRequest` không có setter, còn `modify`,
  `insert`, `retract` không nằm trong allowlist, nên luật không đổi được đơn.
- **Đầu ra:** global `lines` (`QuoteLines`). Validator chỉ cho gọi sáu method:

| Method | Luật làm được gì | Giới hạn |
| --- | --- | --- |
| `lines.add(code, label, amount)` | thêm **một dòng giá mới** | cùng `code` thêm hai lần → lỗi; `amount < 0` → lỗi; nguồn `rule:<tên luật>` do engine gắn, luật không tự khai |
| `lines.has(code)`, `lines.amount(code)`, `lines.total()` | đọc các dòng đã thêm trước đó (theo `salience`), ví dụ hoả tốc = `amount("base") / 2` | chỉ đọc |
| `lines.lock(code)` | khoá một dòng để **tầng sau** (plugin của khách) không sửa/bỏ được, ví dụ phụ phí 11.11 | không đổi số tiền |
| `lines.floor(amount)` | đặt sàn cho tổng sau khi plugin khách áp thao tác | chỉ tăng được (`max`), không hạ |

Thêm `Math.ceil/floor/min/max/round/abs`, phép số học, so sánh, `and/or/not/in`. Hết.

Những gì luật **không** làm được, dù `QuoteLines` là class Java bình thường:
- sửa hay xoá dòng đã có: `QuoteLines` không có method `set`/`remove`; `lines.lines()` trả bản sao, và `lines`, `getFloor` không có trong
  allowlist;
- giả nguồn gốc dòng: `currentRule(...)` là method public (engine dùng để gắn tên luật) nhưng không có trong allowlist → bị từ chối;
- mọi thứ ngoài giá: `System`, `Runtime`, `new`, tên đầy đủ (`java.…`), `import` khác `QuoteRequest`/`QuoteLines`, `global` khác `lines`,
  `eval`, `from`, `function`, `query`, `drools`/`kcontext`, `.class`, regex (`matches`), vòng lặp, dấu nháy đơn, text block, backslash;
- chạy mãi hoặc bùng nổ: mỗi lần báo giá có deadline **50 ms** và tối đa **500** lần fire (`RuleEngine.DEADLINE_MS`, `MAX_FIRED`); quá
  thì báo giá dùng bảng cước chuẩn và ghi lý do.

Vậy "chỉ thêm dòng giá" nói gọn là: luật **thêm** dòng, **đọc** các dòng đã thêm, **khoá** dòng và **đặt sàn**, không có đường nào khác ra
khỏi bài toán giá. Muốn **giảm** giá cho một khách (sửa dòng đã có) thì không viết bằng luật mà viết plugin Tier 2, nơi thao tác
`scale/set/remove` đi qua rào chắn `locked` và `floor` (mục 7.2).

### 1.3 Vì sao điều này cho phép dùng AI sâu

Vì ba tính chất ở đầu mục (đề xuất ≠ duyệt, lỗi không lan, kết quả có nguồn gốc), AI có thể được giao việc **sâu** (viết plugin giá theo hợp đồng, viết bộ phát hiện bất thường) mà không cần tin AI:
AI chỉ làm ra **bản nháp có bằng chứng** (backtest), con người duyệt, hệ thống đo và tự rollback.

---

## 2. Bản đồ code

```
astra-spi/src/main/java/io/astra/spi/
  app/{AstraPlugin,PluginContext}.java          hợp đồng vòng đời plugin Tier 1 (không có DocumentService, GeneralLedger)
  integration/{CarrierAdapter,EInvoiceProvider,PartnerHttp}.java   extension point Lớp 2 + cổng mạng được cấp
  pricing/{QuoteRequest,QuoteLines,PriceLine,RuleQuote,PricingEngine}.java   fact model luật giá
  release/{Releases,SlotContract}.java          "slot nào đang do bản nào phục vụ trên pod này"
  script/ScriptRuntime.java                     Tier 2: callLong + callBytes

astra-plugin-host/src/main/java/io/astra/pluginhost/
  jvm/PluginClassLoader.java      ClassLoader chỉ lộ JDK + io.astra.spi.*
  jvm/LoadedPlugin.java           MethodHandle + bulkhead (semaphore, circuit breaker, deadline)
  jvm/GrantedPartnerHttp.java     HTTP chỉ tới origin trong manifest
  jvm/IsolatedJvmHost.java        nạp theo SHA-256, unload sau drain, theo dõi rò loader
  rollout/RolloutController.java  resolve(slot, customer) theo pod, đếm health
  rollout/RolloutJob.java         promote / rollback dưới advisory lock Postgres
  rollout/AppReleaseHook.java     vet artifact khi lưu, quyền submit, generator
  WasmSandbox.java                Chicory, trần bộ nhớ, deadline, callBytes

astra-kernel/src/main/java/io/astra/kernel/rules/
  RuleEngine.java                 biên dịch DRL lúc chạy, cache theo SHA, deadline, MAX_FIRED = 500
  RuleValidator.java              allowlist trên chữ DRL (ranh giới an toàn chính)

astra-apps/express/
  src/main/java/.../LayeredPricing.java        3 tầng giá: Rule → PricingRule → plugin khách
  src/main/java/.../CustomerPricingPlugin.java rào chắn thao tác của plugin (locked, floor, cap)
  src/main/java/.../CarrierBookingJob.java, EInvoiceJob.java   gọi Tier 1 ngoài transaction
  src/main/java/.../{QuoteBacktestTool,CustomerPricingProfileTool,OpsSnapshotTool,AnomalyBacktestTool,MyAlertsTool}.java  MCP
  src/main/resources/META-INF/astra/rules/{standard-tariff,peak-1111}.drl
  wasm/astra-pricing-pdk/         PDK Rust cho plugin giá
  wasm/khaigia-shopabc/           plugin giá Shop ABC (feature v2 = phụ lục 02)
  wasm/anomaly-ops/               bộ phát hiện bất thường mẫu

astra-apps/express-integrations/{fastship-1.0.0,fastship-1.1.0,fastship-1.2.0-bad,einvoice-vn,einvoice-kh}/
tools/partner-sandbox/partner_sandbox.py      sandbox hãng vận chuyển + cơ quan thuế
scripts/{demo-tiers,demo-traffic,build-plugins,build-wasm,mcp-claude,astra-mcp}.sh
docs/{pricing-rules,overlay-wasm,demo-code-snippets}.md, docs/demo-data/shop-abc-contract-v{1,2}.md
```

---

## 3. Chuẩn bị và chạy nhanh

### 3.1 Công cụ

| Công cụ | Ghi chú |
| --- | --- |
| JDK 25 | các script tự chọn qua `/usr/libexec/java_home -v 25` |
| Docker Desktop | bật trước; giữ volume DB nhỏ (dưới ~10 GB) |
| k3d, kubectl | ở `~/.local/bin` (script tự thêm vào `PATH`) |
| Bash 5+, `jq`, `curl` | script tự chạy lại bằng Bash 5 nếu shell là Bash 3.2 của macOS |
| Rust + target `wasm32-unknown-unknown` | chỉ cần khi sửa module Wasm; `.wasm` đã commit sẵn |
| `claude` (Claude Code) | cho phiên AI qua MCP |

### 3.2 Chạy toàn bộ demo (khoảng 10–15 phút lần đầu)

```bash
cd astra-cms
git switch feat/plugin-tiers-demo

make k3d-up                       # cụm k3d 2 pod astra-server + partner-sandbox + Keycloak (user sophea, aibot)
make demo-reset && make demo-seed # 3 tenant: saigon-express, hanoi-express, angkor-express
make demo-tiers PAUSE=0           # T0 T0b T1a T1b T2a T2b liền một mạch, tự build jar plugin nếu thiếu
```

Chạy từng cảnh, dừng chờ Enter giữa các bước (để giảng giải):

```bash
make demo-tiers SCENES="T0b"      # luật Drools
make demo-tiers SCENES="T1a"      # hãng vận chuyển, canary, rollback
```

Các cảnh nối tiếp nhau (bộ luật của T0b là thứ plugin Shop ABC kế thừa ở T2a), nên khi chạy lẻ hãy chạy theo thứ tự trên một seed mới.

Xem traffic nền và pod nào đang phục vụ, ở terminal khác:

```bash
make demo-traffic MODE=orders RATE=4 DURATION=60
# mỗi dòng: thời điểm · X-Astra-Pod · kết quả · bản plugin; cuối in ok=… fail=…
kubectl get pods -w
```

### 3.3 Chạy test

```bash
./mvnw -pl astra-server -am test                                  # toàn bộ (104 test)
./mvnw -pl astra-plugin-host test -Dtest=IsolatedJvmHostTest      # cô lập, hot-swap, rò loader, bulkhead, capability
./mvnw -pl astra-kernel test -Dtest='RuleValidatorTest,DroolsSpikeTest'
./mvnw -pl astra-server -am test -Dtest='ReleaseRolloutTest,LayeredPricingTest,ExpressIntegrationTest,AnomalyLoopTest' \
       -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl astra-plugin-host test -Dtest=TierDispatchBench        # bench, không chạy trong build thường
```

---

## 4. Tier 0: lõi và luật là dữ liệu

### 4.1 Lõi: chỉ Tier 0 ghi sổ cái

`OrderPostingHook`, `OrderWaybillHook` là bean CDI biên dịch vào image, chạy trong transaction của chứng từ, được inject
`DocumentService` và `GeneralLedger`. Plugin Tier 1 nhận `PluginContext`, và context này **cố ý không có** hai thứ đó:

```java
// astra-spi/.../app/PluginContext.java
/** Deliberately small: no document access, no ledger, no kernel types, so an isolated-JVM plugin cannot write the
 *  books; it can only answer the call it was loaded for. */
public interface PluginContext {
    String identity();
    PartnerHttp http();
    Map<String, String> config();
    System.Logger log();
}
```

**Tại sao:** ranh giới an toàn tốt nhất là ranh giới không thể vượt qua về mặt kiểu. Không có tham chiếu tới `GeneralLedger` thì không
có code nào trong plugin gọi được nó, không cần kiểm tra lúc chạy.

Đổi lõi = image mới + rolling update. `deploy/k8s/base/astra-server.yaml` có readiness `/q/health/ready`, `maxUnavailable: 0`,
`maxSurge: 1`, PDB `minAvailable: 1`, `preStop` để drain và `POD_NAME` qua downward API. Cảnh T0 chạy `kubectl rollout restart` khi
traffic đang chạy và ghi `fail=0`.

### 4.2 Luật Drools: bảng cước hard code thành dữ liệu

Trước đây bảng `TARIFF` nằm trong `ShippingFeeHook`; đổi một con số là build + deploy. Nay bảng đó là
`astra-apps/express/src/main/resources/META-INF/astra/rules/standard-tariff.drl`:

```drl
package astra.express.pricing

import io.astra.spi.pricing.QuoteRequest
global io.astra.spi.pricing.QuoteLines lines

rule "base-domestic"
    salience 100
when
    QuoteRequest(zone not in ("Same city", "Remote"), $kg : weightKg)
then
    lines.add("base", "Cước nền liên tỉnh", 30000 + 5000 * (long) Math.ceil(Math.max(0, $kg - 1) / 0.5));
end

rule "express"
    salience 50
when
    QuoteRequest(express == true)
then
    lines.add("express", "Phụ phí hoả tốc (×1,5)", lines.amount("base") / 2);
end
```

Luật không trả về một con số, mà **thêm dòng giá có `code` ổn định** (`base`, `express`, `cod_fee`, `peak`…). `code` là hợp đồng giữa
tầng Rule và các tầng sau. Luật còn có thể `lock` một dòng (tầng sau không sửa được) và đặt `floor` cho tổng:

```drl
// peak-1111.drl: phụ phí bắt buộc, giá riêng của khách không bỏ được
rule "peak-1111"
    salience 40
when
    QuoteRequest(zone == "Remote", month == 11, day >= 10, day <= 12)
then
    lines.add("peak", "Phụ phí cao điểm 11.11", 5000);
    lines.lock("peak");
end
```

**Ví dụ mới**, phụ phí hàng nặng (qua được validator vì chỉ dùng từ trong allowlist):

```drl
rule "heavy-surcharge"
    salience 40
when
    QuoteRequest(weightKg > 20, $kg : weightKg)
then
    lines.add("heavy", "Phụ phí hàng trên 20 kg", 2000 * (long) Math.ceil($kg - 20));
end
```

Thuộc tính dùng được trong `QuoteRequest(...)`: `zone`, `service`, `express`, `weightKg`, `lengthCm`, `widthCm`, `heightCm`,
`volumetricKg`, `codAmount`, `customer`, `segment`, `date`, `day`, `month`. API của `lines`: `add`, `has`, `amount`, `total`, `lock`,
`floor`. `Math`: `ceil`, `floor`, `min`, `max`, `round`, `abs`.

### 4.3 Tại sao an toàn: validator là allowlist, không phải denylist

Luật chạy **trong JVM lõi**, nên một luật độc có thể làm mọi thứ. `RuleValidator` kiểm tra **từng từ** trong DRL (sau khi xoá chuỗi và
comment) và chỉ cho phép những từ trong danh sách trên. Hệ quả: không cần đuổi theo từng cách viết nguy hiểm.

| Thử | Kết quả |
| --- | --- |
| `System.exit(0)` trong `then` | `line N: 'System' is not allowed in a pricing rule` |
| `java.lang.Runtime.getRuntime()` | `'java' is not allowed` (tên đầy đủ bị chặn từ gốc) |
| `import java.io.File;` | `import java.io.File is not allowed; only [QuoteRequest, QuoteLines]` |
| `eval(...)`, `from`, `insert`, `modify`, `function`, `query`, `drools.`, `.class`, `new` | không có trong allowlist → từ chối |
| `matches "..."` (regex) | bị bỏ khỏi keyword vì regex backtracking không dừng ở deadline |
| `'"'` (char literal) | từ chối mọi dấu nháy đơn |

Dòng cuối là bài học thật: review bảo mật 2026-11-10 tìm ra **RCE**: ký tự `'"'` làm bộ xoá chuỗi của validator và trình biên dịch hiểu
khác nhau chỗ chuỗi kết thúc, giấu được Java tuỳ ý trong "chuỗi". Bản sửa không vá một trường hợp mà **cấm cả lớp**: nháy đơn, text block,
backslash (chặn cả Unicode escape). Test hồi quy dùng đúng payload của reviewer (`RuleValidatorTest`).

Lớp phòng thủ thứ hai lúc chạy (`RuleEngine`): mỗi lần báo giá chạy trên session mới, virtual thread, **deadline**, tối đa `MAX_FIRED = 500`
lần fire; lỗi lúc chạy thì `LayeredPricing` lùi về bảng cước chuẩn và ghi lý do vào `priced_by`. Bộ luật cache theo SHA-256, `KieBase` cũ
được giải phóng (có test rò).

### 4.4 Cách chạy và tự thử

```bash
make demo-tiers SCENES=T0b
```

Cảnh này: lưu bộ luật trên Desk (5 luật, tự kiểm) → Submit tạo `AppRelease` nháp `tier: rules, slot: pricing.base` → admin duyệt →
Live; rồi lưu hai bản sai (cú pháp, `System.exit`) bị từ chối kèm số dòng; bản đúng có `peak-1111` duyệt → canary 1 pod → Live khi
traffic đang chạy.

Tự thử một luật bằng API (người dùng `mai`, tenant `saigon-express`):

```bash
# token: source scripts/lib/demo-common.sh; login mai; TOKEN=${TOKENS[mai]}
curl -sS -X POST http://localhost:8080/api/doc/PricingRuleset \
  -H "Authorization: Bearer $TOKEN" -H "X-Astra-Tenant: saigon-express" -H 'Content-Type: application/json' \
  -d "{\"ruleset_name\":\"standard-tariff\",\"version\":\"1.2.0\",\"rules\":$(jq -Rs . < my-rules.drl)}" | jq '{name, errors, rule_count: .data.rule_count}'
# có errors → sửa theo số dòng; không có → POST /api/doc/PricingRuleset/<name>/submit rồi admin duyệt AppRelease
```

Đọc thêm: [docs/pricing-rules.md](../../docs/pricing-rules.md).

---

## 5. Tier 1: plugin JVM cô lập (tích hợp thị trường)

Dùng cho code **tin cậy nhưng thay đổi theo đối tác**: hãng vận chuyển, hoá đơn điện tử từng nước. Cần JVM đầy đủ (HTTP, JSON, thư viện
của đối tác) nhưng không được thấy lõi, và phải thay được khi đang chạy.

### 5.1 Một plugin trông thế nào

Manifest `META-INF/astra-app.yaml` trong jar (`astra-apps/express-integrations/fastship-1.1.0/`):

```yaml
id: fastship
version: 1.1.0
tier: isolated-jvm
entrypoint: com.fastship.astra.FastShipAdapter
spi: io.astra.spi.integration.CarrierAdapter
provenance: { author: fastship-integrations, generator: human }
capabilities:
  network: [ "http://partner-sandbox:8090" ]      # FastShip's sandbox, nothing else
config:
  base-url: http://partner-sandbox:8090
runtime: { timeout: 300ms, max-concurrency: 32, circuit-breaker: { failure-rate: 0.5, window: 30s } }
```

Entrypoint chỉ phụ thuộc `astra-spi` (scope `provided`) và JDK:

```java
public class FastShipAdapter implements CarrierAdapter, AstraPlugin {
    private PartnerHttp http;
    private String baseUrl;

    @Override public void start(PluginContext context) {
        this.http = context.http();
        this.baseUrl = context.config().getOrDefault("base-url", "http://partner-sandbox:8090");
    }

    @Override public Booking book(Shipment s) {
        String service = "Same city".equals(s.zone()) ? "FAST-2H" : "STD";
        addressHints(s);   // gọi https://example.com: không có trong manifest → CapabilityDeniedException, bỏ qua
        PartnerHttp.Response r = http.postJson(baseUrl + "/fastship/bookings", /* JSON */ body(s, service));
        if (!r.ok()) throw new IllegalStateException("FastShip answered HTTP " + r.status());
        return new Booking("FastShip", field(r.body(), "tracking"), Long.parseLong(field(r.body(), "fee")), service, field(r.body(), "eta"));
    }
}
```

### 5.2 Bốn cơ chế cô lập, và tại sao mỗi cái

| Cơ chế | Code | Tại sao |
| --- | --- | --- |
| **ClassLoader chỉ lộ SPI** | `PluginClassLoader`: parent là `SpiOnly` (platform loader + `io.astra.spi.*`), class của plugin định nghĩa từ bytes trong bộ nhớ | plugin không thấy kernel, Quarkus, Jackson, plugin khác; hai phiên bản cùng id không chung class nào → chạy song song được. SPI luôn lấy từ parent dù jar có nhét bản sao |
| **Capability mạng** | `GrantedPartnerHttp`: so origin với `capabilities.network`, ngoài danh sách → `CapabilityDeniedException` + log `PartnerHttp denied …` | Java 25 không còn SecurityManager. Quyền là **đối tượng được cấp**, không phải kiểm tra socket |
| **Bulkhead** | `LoadedPlugin.invoke`: `Semaphore(max-concurrency)` từ chối ngay khi đầy (không xếp hàng), circuit breaker theo tỉ lệ lỗi, deadline trên virtual thread | một đối tác chậm không ăn hết thread của lõi. Permit do **tác vụ** nhả (không phải người gọi), nên lời gọi quá giờ vẫn giữ chỗ tới khi thread thật dừng |
| **Nạp sau duyệt** | lúc lưu `AppRelease`, `AppReleaseHook` chỉ đọc manifest (`manifestOf`), không định nghĩa class nào | static initializer của jar độc không chạy được chỉ bằng cách upload. Đây là lỗi C1 review tìm ra và đã sửa, có test |

`MethodHandle` được bind một lần lúc nạp; mỗi lời gọi đi qua proxy SPI → bulkhead → handle. `ScopedValue` mang `id@version` để audit
và `PartnerHttp` biết ai gọi. TCCL đặt theo loader plugin trong lúc gọi.

**Nói đúng:** Tier 1 là **cô lập, không phải sandbox**. Plugin vẫn có thể dùng lớp JDK mở socket. Code không tin cậy (kể cả code AI viết)
chạy ở Tier 2.

### 5.3 Gọi Tier 1 ngoài transaction

```java
// CarrierBookingJob.book: claim Pending → Booking (một pod thắng), rồi gọi plugin, ghi booked_by
Optional<Releases.Bound<CarrierAdapter>> carrier = releases.plugin(SLOT, customer, CarrierAdapter.class);
try {
    Booking b = carrier.get().target().book(shipment);
    SystemFields.write(docs, "Waybill", waybill.name(), Map.of("carrier_status", "Booked", /* … */
            "booked_by", carrier.get().label()));            // "fastship@1.1.0 on astra-server-7d9-abc"
} catch (PluginException e) {
    pending(waybill, "pending (" + carrier.get().label() + " failed: " + e.reason() + ")");
}
```

**Tại sao job thay vì hook:** hook `afterSave` chạy trong transaction submit đơn; gọi đối tác 300 ms trong đó là giữ khoá DB và để lỗi đối
tác làm hỏng đơn. Hook chỉ đặt `Pending`; job (2 s, mọi pod) nhận việc và gọi ra ngoài. Đối tác lỗi thì vận đơn vẫn còn, vòng sau bản đang
Live đặt lại.

### 5.4 Viết một plugin Tier 1 mới (ví dụ mới: hãng "QuickShip")

1. Tạo module `astra-apps/express-integrations/quickship-1.0.0/` (copy `fastship-1.0.0`, đổi package), thêm vào `<modules>` của
   `astra-apps/express-integrations/pom.xml`.
2. Manifest: `id: quickship`, `spi: io.astra.spi.integration.CarrierAdapter`, `capabilities.network` đúng origin sandbox của hãng.
3. Implement `CarrierAdapter` + `AstraPlugin`. Chỉ dùng `record` của SPI cho dữ liệu qua ranh giới (tránh rò class), không đăng ký
   handler `java.util.logging` hay static cache toàn cục (giữ loader, gây rò Metaspace).
4. Thêm `quickship-1.0.0` vào vòng lặp trong `scripts/build-plugins.sh`, chạy `scripts/build-plugins.sh`.
5. Upload + duyệt (mục 6.4). Slot `carrier.booking` đã có `SlotContract` (`IntegrationSlots.Carrier`), không cần sửa lõi.

Thêm **slot mới** (một extension point khác) mới cần lõi: SPI mới trong `astra-spi`, một bean `SlotContract` khai `slot`, `tier`, `spi`
và `selfTest`, và chỗ gọi `releases.plugin(...)`.

### 5.5 Cách chạy

```bash
make demo-tiers SCENES=T1a   # FastShip 1.0 → 1.1 canary theo pod → Live; hanoi ghim 1.0; 1.2.0-bad tự rollback; capability từ chối
make demo-tiers SCENES=T1b   # einvoice-vn cho saigon; angkor-express (sophea) chờ "no provider" → duyệt einvoice-kh → có hoá đơn
```

Log cần thấy: `fastship@1.1.0 on <pod A>` cạnh `fastship@1.0.0 on <pod B>`; `rollout … is Rolled back`; `PartnerHttp denied
https://example.com/…`; `classloader fastship@1.0.0 collected`.

---

## 6. Một cửa phát hành: AppRelease, AppPin, canary theo pod

Đây là phần làm cho "động" trở thành "kiểm soát được". Plugin Tier 1, module Wasm và bộ luật Drools **đều** đi qua đây.

### 6.1 `AppRelease` (DocType kernel)

```yaml
# astra-plugin-host/.../doctypes/app-release.doctype.yaml (rút gọn)
submittable: true                 # submit = approve the release; cancel = withdraw it
fields:
  - { name: slot }                # carrier.booking, einvoice.issue, pricing.base, pricing.customer, ops.anomaly
  - { name: tier, options: [isolated-jvm, wasm, rules] }
  - { name: customer }            # rỗng: mọi khách của tenant
  - { name: artifact, type: Attach }
  - { name: artifact_sha256, read-only: true }
  - { name: self_test, read-only: true }
  - { name: generator, options: [human, claude-code], read-only: true }   # lấy từ token, không khai được
  - { name: signed_by, read-only: true }                                  # người duyệt
  - { name: error_budget, default: 0.05 }
  - { name: promote_after, default: 20 }          # số lời gọi khoẻ mỗi bước canary
  - { name: stage, options: [Draft, Canary, Live, Rolled back, Retired], read-only: true }
  - { name: canary_pods, target_pods, calls, errors, p99_ms, stage_reason }   # do controller ghi
permissions:
  - { role: admin,       read: true, create: true, write: true, submit: true, cancel: true }
  - { role: release-bot, read: true, create: true }   # AI đề xuất nháp, không sửa sau đó, không duyệt
```

**Tại sao `release-bot` không có `write`:** nếu có, AI tạo nháp, admin xem, rồi AI tráo artifact trước khi admin bấm Submit. Review H5 đã
tìm ra và gỡ quyền này. Khi lưu, hook tính SHA-256 và tự kiểm artifact (nạp thử Wasm, đọc manifest jar, biên dịch DRL).

### 6.2 `resolve`: bản nào phục vụ lời gọi này, trên pod này

```java
// RolloutController.resolve (rút gọn)
// 1. ghim theo khách  2. ghim theo tenant  3. canary nếu pod này nằm trong canary_pods  4. Live
for (Pin pin : state.pins())
    if (pin.slot().equals(slot) && forCustomer && customer.equals(pin.customer())) return pinned(state, pin);
for (Pin pin : state.pins())
    if (pin.slot().equals(slot) && blank(pin.customer())) { /* … */ return r; }
Optional<Rel> canary = candidates.stream()
        .filter(r -> "Canary".equals(r.stage()) && r.canaryPods().contains(pod.name())).max(byName);
if (canary.isPresent()) return canary;
return candidates.stream().filter(r -> "Live".equals(r.stage())).max(byName);
```

`AppPin` cho phép cá nhân hoá **mà không rẽ nhánh code**: hanoi-express giữ FastShip 1.0 tới khi đối soát xong; mỗi tenant ghim plugin hoá
đơn của nước mình; Shop ABC ghim plugin giá riêng.

```bash
# ví dụ: ghim một tenant vào bản cũ
POST /api/doc/AppPin  {"slot":"carrier.booking","app_id":"fastship","version":"1.0.0","note":"giữ 1.0 tới khi đối soát xong"}
```

### 6.3 Canary theo pod và tự rollback

`RolloutJob` chạy mỗi 2 s, chỉ một pod làm "promoter" nhờ **advisory lock Postgres**:

- canary đủ `promote_after` lời gọi khoẻ trên mỗi pod canary → thêm pod tiếp theo; đủ mọi pod → `Live`, bản cũ `Retired`, drain + unload;
- tỉ lệ lỗi vượt `error_budget` → `Rolled back`, xoá `canary_pods`, pod canary về bản trước ngay.

Pod không nói chuyện với nhau: mỗi pod **đọc lại trạng thái từ Postgres mỗi 2 s** và lúc boot, nạp artifact lười theo SHA-256.

**Tại sao Postgres poll, không Redis pub/sub:** Postgres đã là nguồn sự thật của release; poll 2 s đủ nhanh cho demo và production, không
có thông điệp bị mất khi pod khởi động lại, pod mới boot tự đúng trạng thái. `Counters` (Redis) gắn prefix tenant nên không dùng làm khoá cụm.

**Tại sao theo pod, không theo % request:** canary theo pod cho ranh giới lỗi rõ (một pod hỏng, không phải 10 % request rải khắp cụm),
dễ quan sát qua `X-Astra-Pod`, không cần sticky routing. Cụm demo 2 pod nên các bước là 1/2 → 2/2; cụm production nhiều pod cấu hình
được 10 → 50 → 100 %.

Các lỗi review đã sửa ở đây đáng nhớ khi bạn sửa controller: canary bị huỷ không để slot trống (patch có điều kiện), canary cũ không lật lại
canary mới.

### 6.4 Phát hành bằng API

```bash
# lưu nháp (artifact base64), xem self_test và SHA
curl -sS -X POST $BASE/api/doc/AppRelease -H "Authorization: Bearer $TOKEN" -H "X-Astra-Tenant: saigon-express" \
  -H 'Content-Type: application/json' \
  -d "{\"slot\":\"carrier.booking\",\"tier\":\"isolated-jvm\",\"promote_after\":10,\"artifact\":\"$(base64 < plugins/fastship-1.1.0.jar | tr -d '\n')\"}"
# admin duyệt
curl -sS -X POST $BASE/api/doc/AppRelease/REL-00012/submit -H "Authorization: Bearer $TOKEN" -H "X-Astra-Tenant: saigon-express"
# theo dõi: stage, canary_pods, target_pods, calls, errors, p99_ms, stage_reason
curl -sS $BASE/api/doc/AppRelease/REL-00012 -H "Authorization: Bearer $TOKEN" -H "X-Astra-Tenant: saigon-express" | jq .data
```

Trên Desk: list `AppRelease` có badge màu theo stage, nút Submit có sẵn từ lifecycle.

---

## 7. Tier 2: Wasm, plugin giá kế thừa ("Khai giá Astra")

### 7.1 Vấn đề: giá riêng từng khách

Phụ lục hợp đồng Shop ABC ([shop-abc-contract-v1.md](../../docs/demo-data/shop-abc-contract-v1.md)): giảm cước nền theo bậc sản lượng,
miễn COD nếu hoàn dưới 5 %, phụ phí cồng kềnh có trần, tối thiểu 15.000 ₫, phụ phí bắt buộc áp nguyên. Viết bằng Drools cần **7 luật, 52
dòng** đan với luật chung và phải mở rộng fact model (`docs/demo-data/shop-abc-as-rules.drl`); hard code thì mỗi khách một lần deploy.
Bằng plugin Wasm: **một hàm 33 dòng**.

### 7.2 Định giá xếp tầng: plugin không tính lại từ đầu

```
Tầng 1  Drools (pricing.base)        → dòng base, express, cod_fee, peak(locked)…, floor
Tầng 2  PricingRule.active (video 14) → nếu khớp segment, thay đúng dòng base
Tầng 3  plugin khách (pricing.customer, Wasm) → trả THAO TÁC trên dòng: scale / set / remove / add
shipping_fee = tổng các dòng (làm tròn lên nghìn); price_breakdown lưu nguồn gốc từng dòng
```

`LayeredPricing.price` hiện thực đúng thứ tự này. Mỗi tầng lỗi thì bị bỏ qua và ghi lý do vào `priced_by`, không chặn đơn.

**Tại sao trả thao tác thay vì một con số:**
- **Kế thừa tự động.** BA thêm phụ phí 11.11 ở tầng Rule → đơn của Shop ABC tự có dòng đó, không ai sửa plugin. Plugin chỉ nói điều
  khác biệt; dòng nó không nhắc tới giữ nguyên.
- **Rào chắn kiểm được.** Host thấy từng thao tác nên chặn được: dòng `locked` không sửa được, tổng không dưới `floor`, `code` không tồn
  tại bị từ chối, `scale` ngoài 0–2000 ‰ bị từ chối, mỗi dòng tối đa 1 tỉ đồng (không tràn `long` lách qua sàn). Một thao tác sai → bỏ **cả**
  kết quả plugin, dùng giá tầng Rule (`CustomerPricingPlugin.apply`).
- **Giải thích được cho khách.** Mỗi dòng trong `price_breakdown` có `source`, `op`, `before`, `reason` dẫn điều khoản.

Ví dụ `price_breakdown` của một đơn Shop ABC:

```json
[
  {"code":"base","amount":27600,"source":"rule:base-domestic","op":"scale 0.92 by wasm:khaigia-shopabc@1.0.0","before":30000,
   "reason":"Phụ lục §2: 501–2.000 đơn/tháng, −8 % cước nền"},
  {"code":"cod_fee","amount":0,"source":"rule:cod-fee","op":"remove by wasm:khaigia-shopabc@1.0.0","before":5000,
   "reason":"Phụ lục §3: miễn phí COD khi tỉ lệ hoàn dưới 5 %"},
  {"code":"peak","amount":5000,"source":"rule:peak-1111","locked":true}
]
```

(Số minh hoạ; số thật xem output cảnh T2a.)

### 7.3 Sample code: plugin Shop ABC

`astra-apps/express/wasm/khaigia-shopabc/src/lib.rs` (rút gọn):

```rust
#![no_std]
use astra_pricing_pdk::{export_adjust, vec, Op, Quote};
use astra_pricing_pdk::vec::Vec;

const MINIMUM: i64 = 15_000;                 // §5
const BULKY_PER_HALF_KG: i64 = 4_000;        // §4

fn adjust(q: &Quote) -> Vec<Op> {
    let mut ops = vec![];
    let mut base = q.amount("base");

    // §2 (+ §2a của phụ lục v2, bật bằng feature "v2")
    let eleven_eleven_week = q.month == 11 && (9..=15).contains(&q.day);
    if cfg!(feature = "v2") && eleven_eleven_week {
        ops.push(Op::scale("base", 850, "Phụ lục v2 §2a: tuần 11.11 giữ bậc giá cao nhất (−15 %)"));
        base = base * 850 / 1000;
    } else if q.monthly_orders > 2_000 {
        ops.push(Op::scale("base", 850, "Phụ lục §2: trên 2.000 đơn/tháng, −15 % cước nền"));
        base = base * 850 / 1000;
    } else if q.monthly_orders > 500 {
        ops.push(Op::scale("base", 920, "Phụ lục §2: 501–2.000 đơn/tháng, −8 % cước nền"));
        base = base * 920 / 1000;
    }

    // §3
    let mut cod_fee = q.amount("cod_fee");
    if q.has("cod_fee") && q.return_rate_bp < 500 {
        ops.push(Op::remove("cod_fee", "Phụ lục §3: miễn phí COD khi tỉ lệ hoàn dưới 5 %"));
        cod_fee = 0;
    }

    // §4: cân quy đổi, trần +40 %
    let mut bulky = 0;
    if q.volumetric_g > q.weight_g {
        let chargeable = q.volumetric_g.min(q.weight_g * 140 / 100);
        bulky = (chargeable - q.weight_g + 499) / 500 * BULKY_PER_HALF_KG;
        if bulky > 0 { ops.push(Op::add("bulky", "Phụ phí hàng cồng kềnh", bulky, "Phụ lục §4")); }
    }

    // §5
    let total = q.total() - q.amount("base") + base - q.amount("cod_fee") + cod_fee + bulky;
    if total < MINIMUM { ops.push(Op::set("base", base + MINIMUM - total, "Phụ lục §5: tối thiểu 15.000 ₫/đơn")); }
    ops
}

export_adjust!(adjust);
```

Không có dòng nào nhắc `express` hay `peak`: chúng giữ nguyên từ tầng Rule. Phụ lục v2 là diff vài dòng ở nhánh bậc thang.

**Ví dụ mới**, plugin cho một khách khác "Shop XYZ": giảm 5 % cước nền cho đơn nội thành, thêm phí đóng gói 3.000 ₫:

```rust
#![no_std]
use astra_pricing_pdk::{export_adjust, vec, Op, Quote};
use astra_pricing_pdk::vec::Vec;

fn adjust(q: &Quote) -> Vec<Op> {
    let mut ops = vec![];
    if q.zone == "Same city" {
        ops.push(Op::scale("base", 950, "HĐ XYZ §1: nội thành −5 % cước nền"));
    }
    ops.push(Op::add("packing", "Phí đóng gói", 3_000, "HĐ XYZ §2: đóng gói chống sốc"));
    ops
}

export_adjust!(adjust);
```

Trường có trong `Quote`: `zone`, `service`, `weight_g`, `volumetric_g`, `cod`, `month`, `day`, `monthly_orders`, `return_rate_bp`
(310 = 3,10 %), `floor`, `lines`; hàm `has`, `amount`, `total`, `express`.

### 7.4 Sandbox và ABI

`ScriptRuntime` có hai kiểu gọi, đều **không có host function** (module chỉ tính toán, không I/O, không mạng, không đồng hồ):

- `callLong(module, fn, timeout, i64...)`: cho `PricingRule` (video 14) và `check(kind, a, b, c, d)` của bộ phát hiện bất thường.
- `callBytes(module, fn, timeout, input)`: host gọi `alloc(len)`, ghi input vào bộ nhớ tuyến tính, gọi `adjust(ptr, len) -> i64`
  (ptr ở 32 bit cao, len ở 32 bit thấp), đọc kết quả. Host kiểm ptr/len, giới hạn kích thước kết quả, trần trang bộ nhớ, deadline; mỗi lời
  gọi một instance mới.

Định dạng dây (dòng `ctx`/`line` phân tách bằng TAB) do PDK lo; người viết plugin chỉ viết `fn adjust(&Quote) -> Vec<Op>`. Chi tiết:
[docs/overlay-wasm.md](../../docs/overlay-wasm.md).

**Tại sao Wasm cho code AI viết:** sandbox bảo đảm bằng cấu trúc (không có import nào để gọi ra ngoài), không phụ thuộc người viết có
cẩn thận hay không. Code sai tệ nhất là tính sai giá, và tầng host đã chặn các kiểu sai nguy hiểm (sửa dòng khoá, xuống dưới sàn).

### 7.5 Build và tự thử

```bash
# thêm crate mới vào members trong astra-apps/express/wasm/Cargo.toml, crate-type = ["cdylib"], phụ thuộc astra-pricing-pdk
scripts/build-wasm.sh                       # build mọi crate, copy vào astra-apps/express/wasm/modules/
# hoặc chỉ một crate:
(cd astra-apps/express/wasm && cargo build --release --target wasm32-unknown-unknown -p khaigia-shopxyz)

# backtest trên đơn tháng trước của khách, qua MCP (sau khi chạy scripts/mcp-claude.sh)
scripts/astra-mcp.sh quote_backtest customer=XYZ module_base64@=astra-apps/express/wasm/modules/khaigia-shopxyz-1.0.0.wasm samples:=8

make demo-tiers SCENES=T2a                  # phát lại phần sau phiên AI của Shop ABC
```

---

## 8. AI trong vòng phát triển: đề xuất có bằng chứng, người duyệt, hệ thống đo

### 8.1 AI được gì, không được gì

| AI (token `aibot`, role `release-bot`) | Con người (`mai`, admin) | Hệ thống |
| --- | --- | --- |
| đọc dữ liệu qua MCP (đã che PII, theo quyền DocType) | đọc bản nháp, backtest, ghi chú | SHA-256, self-test khi lưu |
| viết code Rust → Wasm, build | **Submit = duyệt** | canary theo pod, đo calls/errors/p99 |
| backtest trên dữ liệu thật của tenant | ghim theo tenant/khách | tự rollback khi vượt ngân sách lỗi |
| `release_propose` tạo nháp, `generator: claude-code` tự ghi | cancel = rút bản | audit: ai đề xuất, ai duyệt, pod nào chạy bản gì |
| thử submit → **403** | | |

**Tại sao thiết kế như vậy:** không cần tin AI đúng, chỉ cần (1) AI không vượt được quyền (role, không phải lời dặn), (2) mọi đề xuất kèm
bằng chứng con người đọc được (backtest trước/sau), (3) sai thì giới hạn thiệt hại (sandbox + canary + rollback). Đó là lý do có thể giao cho
AI việc tuỳ biến **sâu**, theo từng khách, mà không mở rộng rủi ro.

### 8.2 MCP tools

| Tool | Loại | Dùng cho |
| --- | --- | --- |
| `list_doctypes`, `search_documents`, `get_document` | read (kernel) | khám phá dữ liệu |
| `customer_pricing_profile` | read | sản lượng 30 ngày, tỉ lệ hoàn, phân bố zone/cân của một khách |
| `quote_backtest` | read | chạy module nháp trên đơn tháng trước, trả từng đơn trước/sau + tổng chênh doanh thu |
| `ops_snapshot` | read | 30 ngày cân lại ở hub, đối soát COD, giao thất bại theo bưu tá |
| `anomaly_backtest` | read | chạy bộ phát hiện trên tối đa 2.000 sự kiện, so với nhãn seed |
| `my_alerts` | read | cảnh báo giao cho người gọi |
| `release_propose` | **write** | tạo `AppRelease` nháp (`slot`, `tier`, `app_id`, `version`, `artifact_base64`, `customer`, `notes`) |
| `release_status` | read | stage, pod, calls, errors, p99, tỉ lệ bị bác |

### 8.3 Chạy phiên AI

```bash
scripts/mcp-claude.sh aibot saigon-express     # đăng ký MCP server với token aibot (hạn ~1 giờ, chạy lại để gia hạn)
claude
```

Đề bài mẫu, Khai giá (người dùng đóng vai BA/PO, không phải dev):

> Đây là phụ lục hợp đồng của Shop ABC (docs/demo-data/shop-abc-contract-v1.md). Xem hồ sơ giao hàng của khách qua MCP, viết plugin
> khai giá bằng Rust với astra-pricing-pdk theo docs/overlay-wasm.md, chỉ điều chỉnh trên giá tầng Rule, backtest trên đơn tháng trước,
> rồi đề xuất phát hành cho riêng khách này.

Đề bài mẫu, cảnh báo bất thường:

> Xem dữ liệu vận hành 30 ngày của Saigon Express qua MCP. Có bất thường nào lặp lại ở cân kiện, COD hay giao hàng không? Viết bộ phát
> hiện bằng Rust theo ABI trong docs/overlay-wasm.md, build ra Wasm, backtest trên dữ liệu thật, rồi đề xuất phát hành. Không tự phát hành.

Vòng thứ hai sau khi người dùng xử lý cảnh báo: "Đọc kết quả bản vừa phát hành và đề xuất cải tiến." AI đọc `release_status` (tỉ lệ bị
bác theo loại, ghi chú của `quang`) và đề xuất v2, ví dụ bỏ qua lệch cân ở kiện dưới 0,5 kg.

Bộ phát hiện mẫu (`wasm/anomaly-ops/src/lib.rs`) cho thấy ABI đơn giản đến mức nào:

```rust
#[unsafe(no_mangle)]
pub extern "C" fn check(kind: i64, a: i64, b: i64, c: i64, _d: i64) -> i64 {
    match kind { 1 => weight(a, b), 2 => cod(a, b, c), 3 => delivery(a, b, c), _ => 0 }
}
// trả 0 nếu bình thường, ngược lại severity * 100 + reason
fn weight(declared_g: i64, measured_g: i64) -> i64 {
    if declared_g <= 0 || measured_g <= declared_g { return 0; }
    if cfg!(feature = "v2") && declared_g < 500 { return 0; }   // v2: kiện nhẹ là nhiễu cân
    let over_pct = (measured_g - declared_g) * 100 / declared_g;
    if over_pct > 60 { 301 } else if over_pct > 30 { 201 } else { 0 }
}
```

Cảnh báo (`OpsAlert`) giao theo vai trò: lệch cân → `quang` (OPS), COD → `khoa` (kế toán), giao thất bại → `ha` (CSKH). Module lỗi
hoặc quá giờ không chặn lời quét kiện, chỉ đếm lỗi cho controller.

### 8.4 Giữ phiên AI ổn định

- Đề bài cố định, tài liệu AI đọc nằm trong repo (`docs/overlay-wasm.md`, `docs/pricing-rules.md`, phụ lục hợp đồng). Muốn AI viết đúng hơn
  thì sửa tài liệu, không sửa prompt.
- `scripts/astra-mcp.sh <tool> key@=file` gửi file `.wasm` base64 để AI không phải gõ lại bytes.
- Ghi `asciinema rec` và giữ `.cast` + transcript làm bằng chứng (`docs/video/v22-tiers/assets/`).

---

## 9. Quy trình cho một yêu cầu tuỳ biến mới

### 9.1 Chọn tầng nào

```
Yêu cầu chạm sổ cái, transaction, quyền, dữ liệu lõi?        → Tier 0 (code lõi, PR, review, rolling update)
Quy tắc chung cho mọi khách, BA đọc được, khai báo được?      → Luật Drools (pricing.base)
Tích hợp đối tác cần JVM đầy đủ, code tin cậy, đổi theo đối tác? → Tier 1 (jar + manifest, capability)
Riêng từng khách / thử nghiệm / AI viết / không tin cậy?        → Tier 2 Wasm (plugin kế thừa, sandbox)
```

Quy tắc ngón tay cái: đẩy thay đổi về **tầng có ít quyền nhất mà vẫn làm được việc**. Nếu một điều khoản riêng của khách bắt đầu cần
thêm vào fact model hay thêm luật đan với luật chung, đó là dấu hiệu nó thuộc về plugin khách.

### 9.2 Vòng lặp chuẩn (ví dụ: khách mới Shop XYZ ký phụ lục giá)

1. **BA/PO** đưa phụ lục vào `docs/demo-data/` (hoặc nguồn tài liệu của tenant).
2. **AI** (token `release-bot`): đọc hồ sơ khách qua `customer_pricing_profile`, viết `fn adjust` bằng PDK, build, chạy `quote_backtest`,
   gọi `release_propose` với `customer: XYZ` và `notes` gồm bảng chênh doanh thu.
3. **Người có thẩm quyền** đọc nháp trên Desk (generator, SHA, self-test, backtest), Submit.
4. **Hệ thống**: canary 1 pod → cả cụm; chỉ đơn của XYZ đổi; đơn khác giữ nguyên; vượt ngân sách lỗi thì rollback.
5. **Đo**: `price_breakdown` từng đơn, `release_status`; phụ lục đổi → quay lại bước 2 với diff nhỏ.

Thời gian từ phụ lục tới giá chạy thật là phút, không phải sprint; không ai deploy lõi.

### 9.3 Làm việc với lõi (Tier 0) bằng AI

Code lõi vẫn theo quy trình thường: nhánh, test, `ak:code-review`, `ak:security` cho phần nhạy cảm (validator, ABI, quyền), commit
conventional. Plan này là ví dụ: review sau hiện thực tìm ra RCE validator, jar chạy trước duyệt, quyền `write` thừa của `release-bot`;
cả ba được sửa kèm test hồi quy trước khi quay video.

---

## 10. Kiểm soát và quan sát: nhìn vào đâu

| Câu hỏi | Nhìn vào |
| --- | --- |
| Pod nào trả lời request này? | header `X-Astra-Pod` |
| Bản plugin nào đặt vận đơn này? | `Waybill.booked_by` = `fastship@1.1.0 on <pod>` |
| Giá đơn này từ đâu ra? | `CustomerOrder.price_breakdown` (source, op, before, reason) và `priced_by` |
| Release đang ở đâu, khoẻ không? | `AppRelease`: stage, canary_pods, target_pods, calls, errors, p99_ms, stage_reason; MCP `release_status` |
| Ai đề xuất, ai duyệt? | `generator`, `signed_by`, lịch sử version của `AppRelease` |
| Plugin có bị chặn mạng không? | log `PartnerHttp denied <url> for <id@version>` |
| Plugin cũ đã được thu hồi chưa? | log `classloader <id@version> collected` |
| Stage đổi lúc nào trên pod nào? | log `rollout <tenant>: REL-… <app>@<ver> slot … is <stage> on pods […]` |

---

## 11. Giới hạn đã biết (nói đúng)

- Hãng vận chuyển và cơ quan thuế là **sandbox** (`tools/partner-sandbox`), định dạng hoá đơn không đúng chuẩn pháp lý từng nước.
- Tier 1 là **cô lập, không phải sandbox** (Java 25 không có SecurityManager). Code không tin cậy chạy ở Tier 2.
- Bulkhead và circuit breaker Tier 1 theo SHA jar nên **dùng chung giữa các tenant** (review H4). Demo mỗi slot một tenant nên không lộ.
- Sandbox partner trả mã mới mỗi lần nên re-issue vô hại; tích hợp thật cần **idempotency key** (M2).
- `CustomerProfileJob` truy vấn mỗi khách một lần (M7), cần gộp khi số khách lớn.
- Cảnh báo lọc **theo vai trò/người được giao**, chưa phải quyền theo dòng.
- Chưa có Sigstore/SBOM/OCI registry: release chỉ ghim SHA-256 + `generator` + `signed_by`.
- Drools **ghim 10.1.0** trên Java 25 dù KIE chưa hỗ trợ chính thức; không nâng lên tới khi có hỗ trợ.
- Lịch sử 30 ngày là **dữ liệu seed** ghi bằng SQL; cụm demo 2 pod.

---

## 12. Xử lý sự cố

| Triệu chứng | Nguyên nhân thường gặp | Cách xử lý |
| --- | --- | --- |
| `UnsupportedOperationException` trong `DummyConstraintBuilder` khi biên dịch DRL | thiếu `drools-engine-classic` hoặc `drools-mvel` | khai báo đủ ba artifact Drools 10.1.0 (đã có trong `astra-kernel/pom.xml`) |
| Test rò `KieBase` không xanh trong JUnit thuần | delayed log handler của Quarkus giữ log record tham chiếu session | kích hoạt handler trong `@BeforeAll` (xem `DroolsSpikeTest`) |
| Test rò ClassLoader plugin không về `null` | plugin giữ static cache, đăng ký JUL handler, thread nền | bỏ trạng thái toàn cục; dọn trong `stop()` |
| Cả hai bản không cùng xuất hiện trong log canary | load balancer k3d dồn về một pod | dùng `make demo-traffic`; script gọi thẳng từng pod khi cần |
| `demo-reset` treo lâu | (đã sửa) marker Redis tích tụ | dùng script mới (~1 s); nếu vẫn chậm kiểm tra Redis |
| Pod OOM khi tải | bộ nhớ pod thấp | pod đã đặt 2Gi; giữ volume DB nhỏ |
| `claude` không gọi được tool | token hết hạn (~1 giờ) | chạy lại `scripts/mcp-claude.sh aibot saigon-express` |
| `release_propose` OK nhưng submit 403 | đúng thiết kế: token AI không duyệt được | đăng nhập `mai` (admin) để Submit |

---

## 13. Đọc tiếp

- [plan.md](plan.md) và các phase 00–08: quyết định, acceptance, validation log.
- `plans/reports/code-review-261110-1500-plugin-tiers.md`, `plans/reports/security-261110-1500-drl-validator.md`: review sau hiện thực.
- [docs/demo-video-series.md](../../docs/demo-video-series.md) mục Video 22; [docs/demo-code-snippets.md](../../docs/demo-code-snippets.md) mục S13.
- [docs/pricing-rules.md](../../docs/pricing-rules.md), [docs/overlay-wasm.md](../../docs/overlay-wasm.md).
- [visuals/astra-plugin-tiers-layered-pricing.html](visuals/astra-plugin-tiers-layered-pricing.html): sơ đồ tầng và giá xếp tầng.
