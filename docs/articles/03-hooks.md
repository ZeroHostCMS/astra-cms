# Video 3 · Business rules: hooks & validation

<a href="https://media.zerohostcms.com/astra-cms/astra-video-3-hooks-en.mp4"><img src="../video-thumbs/video-03-en.jpg" width="480" alt="Watch video 3: Business rules: hooks & validation"></a>

▶ [English](https://media.zerohostcms.com/astra-cms/astra-video-3-hooks-en.mp4) · [Tiếng Việt](https://media.zerohostcms.com/astra-cms/astra-video-3-hooks-vi.mp4) · 2:53 · Script: [demo-video-series.en.md](../demo-video-series.en.md#video-3-business-rules-hooks--validation)

> **In one sentence:** A business rule is a plain Java CDI bean that the kernel calls before every write of its DocType, inside the write's transaction, so the 73,000 ₫ shipping fee and the validation errors are identical whether the order comes from Desk, REST or an AI agent over MCP.
> **Read this if:** you need to write a hook and want to know exactly when it runs and what it may do (developer angle) / you want to know where business rules live, how they are enforced across channels, and what that costs (tech-lead angle).

## The problem

Astra Express charges for delivery by zone, weight and service, adds a fee for cash on delivery, and
refuses parcels over 30 kg or phone numbers that are not Vietnamese mobile format. The rule is simple;
keeping it in one place is not. A typical system ends up with the fee formula in the web form (for the
preview), again in the API controller, again in the mobile app, and sometimes in a nightly job. They
drift. The first time someone notices is when a customer is quoted one price on the website and charged
another.

Validation has a second, quieter problem: errors that arrive one at a time. The clerk fixes the phone
number, saves, and only then learns the weight is wrong. A typical Frappe controller behaves this way
too, because `frappe.throw` stops at the first problem unless the developer collects messages by hand.

Astra's answer is two layers that every channel passes through:

- the **DocType** enforces the structural rules it declares: required fields, Select options, unknown
  fields, read-only fields, and Links that must exist in the tenant;
- **hooks** enforce the business rules the YAML cannot express, and may compute fields such as the fee.

Both run in the kernel, below Desk, REST and MCP, so no client can skip them.

## What the video shows

| Scene | What you see | What it proves |
| --- | --- | --- |
| Create | `tuan` (sales) creates an order for `C001`: Domestic, Express, 2.2 kg, COD 500,000 ₫ | The number comes from a daily series; `ShippingFeeHook` sets the fee in the same transaction as the write |
| Fee | 30,000 + 3 × 5,000 = 45,000; × 1.5 = 67,500; + 5,000 COD = 72,500; rounded up to 73,000 ₫ | The rule is visible arithmetic, not a black box; "Priced by: Standard tariff" |
| Errors | Phone `123`, weight `45` → Save | Both errors in a single 422, before anything is written |
| Link | Valid values, customer `C999` | Not a hook: the kernel checks the Link target exists; the draft is unchanged |
| Channels | Same data through REST | The same two messages, in the same order |
| Code | `ExpressApp` and `ShippingFeeHook` | A hook is a CDI bean; no registration, no configuration |
| API rules | `shipping_fee` sent by the client, unknown field `discount`, Select value `Mars` | All rejected from the YAML alone, data unchanged |

## How it works

### The hook

*`astra-spi/src/main/java/io/astra/spi/hook/DocLifecycleHook.java`*

```java
public interface DocLifecycleHook {

    /** The DocType this hook applies to, e.g. {@code "Article"}. */
    String doctype();

    /** Runs before a document is inserted or updated, after kernel validation of declared fields. */
    default void beforeSave(Document doc) { }

    /** Runs after the document has been persisted in the same transaction. */
    default void afterSave(Document doc) { }

    // … onSubmit and onCancel (Video 4)
}
```

The Express app's rule implements it. The structure is the important part: collect every error, throw
once, then compute.

*`astra-apps/express/src/main/java/io/astra/apps/express/ShippingFeeHook.java`*

```java
@ApplicationScoped
public class ShippingFeeHook implements DocLifecycleHook {

    static final double MAX_WEIGHT_KG = 30;
    static final long MAX_COD = 50_000_000;

    /** Zone → {price for the first kg, price per additional 0.5 kg}, in VND. */
    private static final Map<String, long[]> TARIFF = Map.of(
            "Same city", new long[] {18_000, 2_500},
            "Domestic", new long[] {30_000, 5_000},
            "Remote", new long[] {45_000, 7_000});
    // …

    @Override
    public String doctype() { return "CustomerOrder"; }

    @Override
    public void beforeSave(Document doc) {
        List<String> errors = new ArrayList<>();
        String phone = doc.getString("receiver_phone");
        if (phone != null && !phone.matches("0\\d{9}")) errors.add("receiver_phone must be 10 digits starting with 0");
        double weight = Numbers.toDouble(doc.get("weight_kg"));
        if (weight <= 0 || weight > MAX_WEIGHT_KG) errors.add("weight_kg must be between 0 and " + (int) MAX_WEIGHT_KG);
        long cod = Math.round(Numbers.toDouble(doc.get("cod_amount")));
        if (cod < 0 || cod > MAX_COD) errors.add("cod_amount must be between 0 and " + MAX_COD);
        if (!errors.isEmpty()) throw new DocValidationException(errors);

        String zone = doc.getString("zone");
        // … a tenant's active Wasm PricingRule takes precedence (Video 14)
        doc.set("shipping_fee", fee(zone, doc.getString("service"), weight, cod));
        doc.set("priced_by", "Standard tariff");
    }

    // …
    static long fee(String zone, String service, double weightKg, long cod) {
        long[] t = TARIFF.getOrDefault(zone, TARIFF.get("Domestic"));
        long extraSteps = (long) Math.ceil(Math.max(0, weightKg - 1) / 0.5);
        double fee = t[0] + extraSteps * t[1];
        if ("Express".equals(service)) fee *= 1.5;
        if (cod > 0) fee += Math.max(5_000, cod * 0.01);
        return (long) Math.ceil(fee / 1_000) * 1_000;
    }
}
```

The fee for the demo order, step by step against `fee(...)`:

| Step | Value |
| --- | --- |
| Domestic, first kg | 30,000 |
| 2.2 kg − 1 = 1.2 kg → `ceil(1.2 / 0.5)` = 3 steps × 5,000 | + 15,000 = 45,000 |
| Express × 1.5 | 67,500 |
| COD: `max(5,000; 1% × 500,000)` | + 5,000 = 72,500 |
| Round up to the thousand | **73,000 ₫** |

### Discovery: no registration

The app class lists only DocTypes. Hooks are found because they are CDI beans implementing the SPI. At
startup the kernel groups them by `doctype()` once, so dispatch on the write path is a list iteration:

*`astra-kernel/src/main/java/io/astra/kernel/extension/HookRegistry.java`*

```java
    @Inject
    public HookRegistry(Instance<DocLifecycleHook> hooks) {
        this.chains = hooks.stream().collect(Collectors.groupingBy(DocLifecycleHook::doctype));
        // … logs "DocType %s: %d lifecycle hook(s) [%s]"
    }

    public void beforeSave(Document doc) {
        for (DocLifecycleHook hook : chains.getOrDefault(doc.doctype(), List.of())) {
            hook.beforeSave(doc);
        }
    }
```

Why CDI and not a hooks file or annotations scanned by name: the hook is ordinary Java, injectable
(`ShippingFeeHook` injects `DocumentService` and `ScriptRuntime`), and testable like any other bean.
The binding itself is still a string (`doctype()` returns `"CustomerOrder"`) and the registry does not
check it against the loaded DocTypes, so a misspelt name means the hook never runs. The startup log
line per chain is where to catch that.

### The write path, in order

Every create and update goes through `DocumentStore`, whatever the channel. `DocResource` (REST, which
Desk also calls) and `McpServer` (AI agents) both call `store.insert` / `store.update`.

*`astra-doctype/src/main/java/io/astra/doctype/store/DocumentStore.java`*

```java
    public Document insert(String doctype, Map<String, Object> data, String owner) {
        DocTypeDefinition def = overlays.effective(doctype);
        DocumentValidator.rejectReadOnly(def, data);
        return inTenantTx(conn -> persistNew(conn, def, new Document(doctype, data).owner(owner)));
    }
    // …
    private Document persistNew(Connection conn, DocTypeDefinition def, Document doc) throws SQLException {
        DocumentValidator.applyDefaultsAndValidate(def, doc);
        hooks.beforeSave(doc);
        validateLinks(conn, def, doc);
        if (doc.name() == null) doc.name(assignName(conn, def, doc));
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO astra_doc (id, tenant_id, doctype, name, owner, data, amended_from) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING *")) {
            // …
                hooks.afterSave(saved);
            // …
        }
    }
```

```mermaid
sequenceDiagram
    autonumber
    actor U as tuan (Desk / curl / MCP)
    participant API as DocResource / McpServer
    participant S as DocumentStore
    participant H as ShippingFeeHook
    participant PG as PostgreSQL (RLS)
    U->>API: POST /api/doc/CustomerOrder
    API->>API: DocPermissions: sales may create
    API->>S: insert(CustomerOrder, data)
    S->>S: rejectReadOnly (shipping_fee sent? → 422)
    S->>PG: BEGIN; set_config('astra.tenant', …)
    S->>S: applyDefaultsAndValidate (unknown, required, Select)
    S->>H: beforeSave(doc)
    H-->>S: errors → 422, or shipping_fee = 73000
    S->>PG: validateLinks: Customer C001 exists? (RLS: this tenant only)
    S->>PG: astra_series +1 → ORD-<date>-0000n
    S->>PG: INSERT astra_doc … RETURNING *
    S->>PG: COMMIT
    API-->>U: 201 + document
```

Design choices worth noticing:

- **The hook runs after the kernel's own validation.** Defaults are already filled and Select values
  already checked, so a hook never sees `zone: "Mars"`. Hook code stays about business, not shape.
- **Errors are reported per stage, all at once within a stage.** `DocValidationException` carries a list,
  and the exception mapper returns it whole:

  *`astra-doctype/src/main/java/io/astra/doctype/api/ApiExceptionMappers.java`*

  ```java
      @ServerExceptionMapper
      public Response validation(DocValidationException e) {
          return Response.status(422).entity(Map.of("error", "validation", "errors", e.errors())).build();
      }
  ```

- **Link checks run inside the tenant's transaction.** The query is
  `SELECT 1 FROM astra_doc WHERE doctype = ? AND name = ?` with no tenant filter in the SQL; row-level
  security adds it. A customer that exists only in `hanoi-express` is "not found" in `saigon-express`
  (Video 7).
- **The number is taken last.** The series counter increments only after validation, hook and Links pass,
  and in the same transaction as the insert. A rejected order never consumes a number, so the daily
  series has no gaps from validation failures.
- **Read-only means "owned by hooks".** The client cannot send `shipping_fee`, but the hook sets it. The
  fee therefore always comes from the rule, never from a client that computed it differently.
- **Same transaction for reads inside the hook.** `ShippingFeeHook` looks up the customer's segment and
  the tenant's active pricing rule through `DocumentService`. `TenantTransactions` carries the open
  connection in a `ScopedValue`, so those reads join the write's transaction and see the same tenant.

## Real-world example

A last-mile courier in Vietnam (hypothetical, numbers from the real standard tariff in `ShippingFeeHook`,
no active `PricingRule` for the tenant) receives five orders from a marketplace integration in the
morning batch:

| Order | Input | What Astra does | Output |
| --- | --- | --- | --- |
| A | Same city, Standard, 0.8 kg, no COD | 18,000; `ceil(max(0, −0.2) / 0.5)` = 0 extra steps; no COD fee | 201, fee **18,000 ₫** |
| B | Domestic, Standard, 3 kg, COD 1,200,000 | 30,000 + 4 × 5,000 = 50,000; COD `max(5,000; 12,000)` = 12,000 | 201, fee **62,000 ₫** |
| C | Remote, Express, 5 kg, COD 2,000,000 | 45,000 + 8 × 7,000 = 101,000; × 1.5 = 151,500; + 20,000 COD = 171,500; round up | 201, fee **172,000 ₫** |
| D | Domestic, phone `84901234567`, 31 kg | Hook collects two errors, throws once | 422, `["receiver_phone must be 10 digits starting with 0", "weight_kg must be between 0 and 30"]`, nothing written, no number consumed |
| E | Valid parcel, `"shipping_fee": 0` sent by the integration | Rejected before the transaction opens | 422, `["field 'shipping_fee' is read-only"]` |

Order E is the one that matters to finance: an integration partner cannot set its own price, however the
client was written. If the courier's sales team later gives a B2B segment a promotional formula, it is
deployed as a tenant Wasm rule and the same hook picks it up, with "Priced by" recording which rule
applied (Video 14).

## For technical leaders

- **Where rules live.** Structural rules in DocType YAML; business rules in Java hooks in the app;
  tenant-specific formulas in Wasm (Video 14) or overlays (Video 13). That split is the main design
  decision to review with your team.
- **Enforcement point.** Hooks run in `DocumentStore`, below every channel. Anything that writes through
  the kernel gets the rule. Anything that writes to PostgreSQL directly does not; keep the database role
  locked down (Video 8).
- **Cost on the write path.** Each hook runs synchronously inside the database transaction. A slow hook
  holds a connection and the row locks the transaction has taken. `ShippingFeeHook` adds reads (customer
  segment, active pricing rules) to every save of an order. Measure p95 save latency per DocType and
  hook count per chain (logged at startup).
- **Ordering.** The SPI has no priority attribute; hooks of one DocType run in the order CDI provides
  them. If two hooks on the same DocType depend on each other's output, merge them or make the
  dependency explicit in one hook.
- **Error contract.** 422 with `{"error":"validation","errors":[…]}` is stable and the same on every
  channel, which makes client error handling and AI agent retries straightforward. Errors arrive per
  stage: kernel structural errors first, then hook errors, then Link errors. A client that fixes one
  stage may see the next.
- **When not to use a hook.** Long-running work, calls to external services that can hang, or anything
  that must survive a rollback (sending an SMS). Those belong after commit in a job or event, which is
  not part of the thin slice yet.
- **Compared with Frappe.** Same idea as `validate` / `before_save` controller methods and `doc_events`.
  Differences: a typed Java interface instead of Python methods resolved by name, discovery through CDI,
  and an error list returned in one response.

## Try it yourself

Prerequisites: the stack from [Video 1](./01-quickstart.md) running and seeded.

```bash
make demo-express SCENES=3 PAUSE=0
```

The scene creates a real draft order in `saigon-express`, so run `make demo-reset && make demo-seed`
afterwards if you want order numbers to match the video. What it does, in order, with the expected result:

| Call | Expected |
| --- | --- |
| `POST /api/doc/CustomerOrder` (C001, Domestic, Express, 2.2 kg, COD 500,000) | `HTTP 201`, `name` `ORD-<today>-0000n`, `shipping_fee` `73000` |
| Same with phone `123`, weight `45` | `HTTP 422`, both hook errors |
| Customer `C999` | `HTTP 422`, `field 'customer': Customer 'C999' not found` |
| Client sends `shipping_fee` | `HTTP 422`, `field 'shipping_fee' is read-only` |
| `PUT` the draft with `zone: Mars`, `discount: 10` | `HTTP 422`, `unknown field 'discount' for DocType CustomerOrder` and `field 'zone' must be one of [Same city, Domestic, Remote]` |

In Desk, sign in as `tuan` / `Astra@123`, open the Customer Order list, click **New**, and use the **API inspector**
button to see the same requests and 422 bodies.

The integration test [`ExpressFlowTest`](../../astra-server/src/test/java/io/astra/server/ExpressFlowTest.java)
asserts the 73,000 fee, the dated name and the read-only, phone and Link rejections against a real
PostgreSQL; `make test` runs the whole suite.

## To be accurate

- **"One round of fixes" holds within a stage.** The hook returns all of its errors at once, and the
  validator returns all structural errors at once, but a request that fails the structural stage never
  reaches the hook. In the video both errors come from the hook, so one round is enough.
- **The Link check is the kernel, not the hook.** The video says so; the hook does not look at `customer`
  except to find the segment for pricing.
- **Hook discovery is build-time.** Hooks are CDI beans in Tier 0 apps compiled into the image. Changing a
  hook means a new image. Runtime-loaded Java hooks (Tier 1) are not built; tenant-specific logic at run
  time goes through Wasm (Video 14).
- **No hook ordering or after-commit hooks.** `afterSave` runs inside the same transaction, before commit.
- **Fee rounding uses floating point** (`double`) before rounding up to the thousand. For the tariff's
  values this is exact enough; a production billing rule would use `BigDecimal` or integer minor units.

## Visualize it: sketch prompts

### Figure 1 · Three channels, one rule
*Where it goes:* The problem

```text
Hand-drawn whiteboard sketch, black ink line art on off-white paper, one accent color (#6b8cff blue) for the key path, simple boxes and arrows, short handwritten labels in English, generous white space, no logos, no photorealism, 16:9. Three small client doodles stacked on the left: a browser labelled "Desk", a terminal labelled "curl / REST", a robot head labelled "MCP agent". Three arrows converge to the right into a single funnel-shaped box labelled "DocumentStore". Inside the funnel, two stacked layers: top "DocType rules (YAML)", bottom "ShippingFeeHook (Java)" in blue (the highlight). From the funnel, one arrow right to a cylinder labelled "astra_doc". Next to the cylinder a small tag "shipping_fee = 73,000". A crossed-out dashed arrow from "curl / REST" going around the funnel directly to the cylinder, labelled "no bypass".
```

### Figure 2 · The fee, step by step
*Where it goes:* How it works, after the fee table

```text
Hand-drawn whiteboard sketch, black ink line art on off-white paper, one accent color (#6b8cff blue) for the key path, simple boxes and arrows, short handwritten labels in English, generous white space, no logos, no photorealism, 16:9. A parcel doodle on the left with a tag "Domestic · Express · 2.2 kg · COD 500,000". Five boxes left to right connected by arrows: "first kg 30,000", "+ 3 x 5,000 = 45,000", "x 1.5 Express = 67,500", "+ COD max(5,000; 1%) = 72,500", "round up to 1,000". The final box on the far right is larger, outlined in blue: "73,000 ₫". Under the chain, a small note "ShippingFeeHook.fee(zone, service, weight, cod)".
```

### Figure 3 · The write path and where each rejection happens
*Where it goes:* How it works, after the sequence diagram

```text
Hand-drawn whiteboard sketch, black ink line art on off-white paper, one accent color (#6b8cff blue) for the key path, simple boxes and arrows, short handwritten labels in English, generous white space, no logos, no photorealism, 16:9. A horizontal pipeline of six gates left to right, each a small box: "rejectReadOnly", "defaults + Select + unknown", "ShippingFeeHook.beforeSave", "validateLinks (RLS)", "series ORD-...-0000n", "INSERT astra_doc". The main arrow through all gates is blue. Below the first four gates, a short black arrow drops down to a shared tray labelled "422 errors[]", with small example tags: under gate 1 "shipping_fee is read-only", under gate 2 "zone must be one of [...]", under gate 3 "phone + weight", under gate 4 "Customer 'C999' not found". A bracket over gates 2 to 6 labelled "one transaction, rolled back on any 422". A note under gate 5: "number taken only after all checks pass".
```

## Related

- Previous: [Video 2 · DocType: from one YAML file to API, UI and indexes](./02-doctype.md)
- Next: [Video 4 · Document lifecycle: Submit, Cancel, Amend](./04-lifecycle.md)
- First article: [Video 1 · What is Astra & running it locally](./01-quickstart.md)
- Script: [demo-video-series.en.md](../demo-video-series.en.md); developer excerpts:
  [demo-code-snippets.md](../demo-code-snippets.md) (S2)
- Source: [`ShippingFeeHook.java`](../../astra-apps/express/src/main/java/io/astra/apps/express/ShippingFeeHook.java),
  [`DocLifecycleHook.java`](../../astra-spi/src/main/java/io/astra/spi/hook/DocLifecycleHook.java),
  [`HookRegistry.java`](../../astra-kernel/src/main/java/io/astra/kernel/extension/HookRegistry.java),
  [`DocumentStore.java`](../../astra-doctype/src/main/java/io/astra/doctype/store/DocumentStore.java),
  [`DocumentValidator.java`](../../astra-doctype/src/main/java/io/astra/doctype/store/DocumentValidator.java),
  [`TenantTransactions.java`](../../astra-doctype/src/main/java/io/astra/doctype/store/TenantTransactions.java),
  [`ExpressFlowTest.java`](../../astra-server/src/test/java/io/astra/server/ExpressFlowTest.java)
