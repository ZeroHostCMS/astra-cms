# Astra Express: demo and video script for introducing Astra

Vietnamese original: [demo-video-series.md](demo-video-series.md).

This document is used to record a series of marketing videos that introduce Astra. The storyline comes from
[`use-case-01.md`](use-case-01.md): a courier company that takes orders (**OMS**), runs parcel operations
(**OPS**), handles customer care (**CARE**) and manages receivables and the ledger (**BMS**). Everything shown in the videos
is **code that actually runs** in the repository, as a *thin slice*: enough to prove the mechanism, not yet
the full scope of the use case. The platform mechanism videos (9, 11 to 17) and the new business videos (19 to 21) have a **"To be accurate"** section that states the current limits; each video links to the code to show developers in
[`demo-code-snippets.md`](demo-code-snippets.md) (code excerpted from the repo, with flow diagrams).

Background music: "Corporate Tower" by Mare Production ([freesound.org/s/612264](https://freesound.org/s/612264/)), CC BY 4.0, edited.

Vietnamese voice-over: the male voice "Xuân Vĩnh" from [VieNeu-TTS](https://github.com/pnnbao97/VieNeu-TTS) v3 Turbo (Apache-2.0), synthesized by `scripts/video-voiceover.py` from the `narration.json` file in each video folder.
The English versions use the Kokoro-82M voice `am_michael` (Apache-2.0) via `hyperframes tts`, synthesized by `scripts/video-voiceover.py --lang en` from `narration.en.json`. Fonts: Be Vietnam Pro (SIL OFL 1.1) for text, JetBrains Mono for code.

---

## 1. Preparing the recording environment

Requirements: JDK 25, Docker Desktop (the whole stack runs with 8 GB of RAM allocated to Docker), `kubectl`, `k3d`, `jq`, **Bash 5**
(`brew install bash`; the scripts re-run themselves under Bash 5 if the default shell is macOS Bash 3.2),
Rust (only needed when changing the Wasm modules or the CDC worker).

```bash
make k3d-up          # k3d cluster: 2 kernel pods, PostgreSQL (wal_level=logical), Redis, Keycloak, Seata, Rust CDC worker
make demo-seed       # 2 tenants: saigon-express, hanoi-express; data via the REST API, Keycloak login
open http://localhost:8080/desk/          # Desk (Keycloak login, PKCE)

make demo-reset && make demo-seed         # clean up before each recording session (documents, ledger, overlays, BMS, Redis)
make demo-express SCENES="10 11" PAUSE=0  # run terminal scenes; drop PAUSE=0 to press Enter through each step
```

`make k3d-up` rebuilds the images, syncs the Keycloak realm (`scripts/keycloak-sync-realm.sh`), prepares the database
(`scripts/db-setup.sh`: BMS schema, CDC role/publication) and deploys everything.

Video 8 (the kernel refuses a superuser) and Video 9 (live reload) also need the Docker Compose stack. k3d and dev mode
both default to port 8080, so run dev mode on another port and point every script at it:

```bash
make infra-up && make build                          # PostgreSQL :5432, Redis, Keycloak :8180 (Video 8 only needs this far)
                                                     # infra-up runs scripts/keycloak-compose-dev.sh: dev Keycloak accepts HTTP from the host + realm sync
make dev DEV_ARGS=-Dquarkus.http.port=8081           # Video 9
BASE=http://localhost:8081 make demo-seed            # seed into dev mode; reset with: scripts/demo-reset.sh compose
```

### Demo users (shared password: `Astra@123`, realm `deploy/keycloak/realm/astra-realm.json`)

| User | Tenant | Role | Used for |
| --- | --- | --- | --- |
| `mai` | saigon-express | admin | tenant administration, overlays |
| `tuan` | saigon-express | sales | creating / submitting orders |
| `quang` | saigon-express | ops | scanning parcels |
| `ha` | saigon-express | care | customer care tickets, reading the replica |
| `khoa`, `thu` | saigon-express | accountant | ledger, COD remittance (maker / checker) |
| `nam` | hanoi-express | admin | second tenant |
| `demo` | both | admin | seeding data, two-tenant comparison screen |
| `hanh` | saigon-express | hr | HR and payroll (Video 19) |
| `linh` | both | dispatcher | vehicle dispatch (Video 20) |

### Recording tools

| Tool | Used for | Command / URL |
| --- | --- | --- |
| **Astra Desk** | UI generated from metadata: list, form, lifecycle, ledger, CDC replica, overlays | `/desk/` |
| **Two-tenant comparison** | Two tenants side by side (log in as `demo` first) | `/desk/compare.html` |
| **API inspector** | The real requests/responses Desk makes, including `X-Tx-LSN` | "API inspector" button |
| **Scene script** | 15 terminal scenes, one per video | `scripts/demo-express.sh` |
| **Claude Code + MCP** | A real AI agent calling Astra | `scripts/mcp-claude.sh tuan saigon-express` |

Tip: 1440×900 window; terminal at 16 to 18 pt; record right after `demo-reset` + `demo-seed` so document numbers match
the script (`ORD-<date>-00007`…). The date in the order number is the date the seed ran.

---

## 2. Feature map → where it is proven

| Feature (actually running) | Video | Scene | Code |
| --- | --- | --- | --- |
| An app is a Tier 0 plugin that uses only `astra-spi` | 1, 9 | 1 | `astra-apps/express`, `astra-apps/cms` |
| DocType YAML → generated REST + Desk; JSONB, no ALTER TABLE | 2, 9 | 2 | `*.doctype.yaml`, `DocResource`, Desk |
| `projected: true` → online expression index | 2, 5 | 2, 5 | `ProjectionIndexer` |
| Metadata-driven validation, business hooks | 3 | 3 | `DocumentValidator`, `ShippingFeeHook` |
| Lifecycle Draft → Submitted → Cancelled → Amend | 4 | 4 | `DocumentStore.submit/cancel/amend` |
| State machine + linked documents in 1 transaction | 5 | 5 | `WaybillScanHook`, `OrderWaybillHook` |
| CARE sees OPS, triage, SLA | 6 | 6 | `TicketTriageHook` |
| Multi-tenant RLS, `ScopedValue` | 7 | 7 | `TenantContext`, `TenantResolverFilter` |
| Fail-closed when connecting as a superuser | 8 | 8 | `RowLevelSecurityGuard` |
| **Keycloak OIDC, per-tenant roles, DocType permissions, SoD** | 11 | 9 | `astra-iam`, `DocPermissions`, realm |
| **Append-only ledger, monthly partitions, reversing entries** | 12 | 10 | `V3__ledger.sql`, `LedgerStore`, `GeneralLedger` SPI |
| **Per-tenant overlays, applied without a restart** | 13 | 11 | `Overlays`, `DocTypeOverlays`, `tenants/*/overlays` |
| **Tier 2 Wasm (Chicory 1.7.5), tenant Rust scripts** | 14 | 12 | `astra-plugin-host`, `astra-apps/express/wasm` |
| **MCP server, PII masking, app-contributed tools** | 15 | 13 | `astra-ai`, `McpTool` SPI |
| **Rust CDC (pgoutput) + Sync-Aware LSN** | 16 | 14 | `astra-cdc-rust`, `SyncAwareLsnFilter` |
| **Seata 2.6 AT + TCC: global transaction** | 17 | 15 | `astra-tx-seata`, `OrderCreditHook`, `PickupCapacity` |
| Kubernetes: 2 pods, series safe across pods | 10 | (none) | `deploy/k8s`, `SeriesService` |
| **HRM: attendance, Payroll Entry, salary slips, Wasm KPI bonus, accrual journal entry** | 19 | `demo-hrm` 1 to 8 | `astra-apps/hrm`, `PayrollEntryHook`, `SalaryCalculator` |
| **STMS: Timefold VRP, replanning, MCP tools, copilot (simulated LLM)** | 20 | `demo-stms` 1 to 8 | `astra-apps/stms`, `RoutePlanner`, `Replanner`, `DispatchCopilot` |
| **SAML SSO through Keycloak, per-tenant SCIM 2.0** | 21 | `demo-sso` 1 to 8 | `deploy/keycloak/realm`, `ScimResource`, `ScimDirectory` |

---

## 3. Video list

Suggested length: teaser 60 to 90 seconds, feature videos 3 to 6 minutes, developer videos 6 to 8 minutes.

### Video 0: Teaser "One platform, every business process" (90 seconds)

**Developer code:** [S0](demo-code-snippets.md#s0-big-picture)

Cut quickly from footage of the later videos:
1. The `customer-order.doctype.yaml` file → the "Customer Order" Desk appears.
2. Submit an order → the waybill is generated, the journal entry Debit 131 / Credit 511 appears under the form.
3. Two tenants side by side: Hanoi has an extra VAT field (overlay), Saigon does not.
4. An infinite-loop Wasm script: "stopped after 50 ms".
5. Claude asks "where is parcel WB-…?", the phone number shows as `*******567`.
6. The "sync pending" badge → "in sync" after a few tens of ms (Rust CDC).
7. Seata: an order over the credit limit, everything rolls back, the undo log is clean.
8. Closing: "Astra: Java 25, Quarkus, PostgreSQL, Rust. Open source."

### Video 1: What Astra is & running it in 5 minutes

**Developer code:** [S0](demo-code-snippets.md#s0-big-picture)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Architecture diagram in `README.md` | Kernel, DocType Engine, Plugin Host; today we look at the parts that actually run. |
| 2 | `make k3d-up` (fast-forward), `kubectl -n astra get pods` | 2 kernel pods, PostgreSQL, Redis, Keycloak, Seata, Rust CDC worker. |
| 3 | `make demo-seed` | All data is loaded through the REST API, logging in with Keycloak. |
| 4 | `make demo-express SCENES=1` | `/api/app`: 2 apps; `/api/doctype`: 8 DocTypes. |
| 5 | Desk → log in as `mai` → Installed apps | The Express app contributes OMS, OPS, CARE, BMS. |

### Video 2: DocType, from one YAML file to API, UI and index

**Developer code:** [S1](demo-code-snippets.md#s1-doctype-is-the-source-of-truth), [S12](demo-code-snippets.md#s12-desk-generated-from-metadata)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `SCENES=2`: the `CustomerOrder` YAML | Fields, Select, Link, read-only, `pii`, `submittable`, `transaction: global`. |
| 2 | Desk → DocType metadata → CustomerOrder | The field table is read from `GET /api/doctype` (the same definition as `GET /api/doctype/CustomerOrder`, with the tenant overlay applied). |
| 3 | Projections section | `projected: true` → `CREATE INDEX CONCURRENTLY`, no write lock. |
| 4 | Terminal: `\d astra_doc` | Every DocType shares one JSONB table. |

### Video 3: Business rules, hooks & validation

**Developer code:** [S2](demo-code-snippets.md#s2-an-app-and-a-hook)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Desk (`tuan`) → New Customer Order: C001, Domestic, Express, 2.2 kg, COD 500,000 → Create | Number from a date-based series; **fee 73,000 ₫** computed by `ShippingFeeHook`; "Priced by: Standard tariff". |
| 2 | Explaining the fee | 30,000 + 3 × 5,000 (per 0.5 kg above the first kg) = 45,000; ×1.5 (Express) = 67,500; COD fee max(5,000; 1%) = 5,000 → 72,500; rounded up to the thousand → 73,000. |
| 3 | Change the phone to `123`, weight to `45` → Save | The hook returns 2 errors at once (422). |
| 4 | Fix them, customer `C999` → Save | The kernel checks the Link. |
| 5 | `SCENES=3` + API inspector | Read-only fields, unknown fields and invalid Select values are all rejected. |

### Video 4: Document lifecycle, Submit, Cancel, Amend

**Developer code:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Desk (`mai`) → New Customer Order for C001 → Create | Status bar 0 → 1 → 2 → Amend. (The draft order `ORD-…-00005` for C002 is reserved for Video 17: it exceeds the credit limit.) |
| 2 | **Submit** | The waybill is created and the freight journal entry shows in the "Ledger entries" card, in the same transaction. |
| 3 | The form is locked | A submitted document is immutable. |
| 4 | **Cancel** | The waybill is cancelled with it; the ledger records a reversing entry (old rows are not edited). |
| 5 | **Amend**, then Amend again on the cancelled copy | Amended copy `-1`; the second attempt is rejected. |

### Video 5: Operations, the parcel state machine

**Developer code:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Desk (`quang`) → Waybill, filter `Ops status = At Hub` | Filter on a projected field (⚡). |
| 2 | `WB-…-000001` | Full scan timeline through Delivered. |
| 3 | Waybill `Created` → choose `Delivered` | No skipping states. |
| 4 | `Picked Up`; then `At Hub` with the hub empty; then fill in the hub | An error, then success; the timeline gets a new entry. |
| 5 | Desk (`mai`) → cancel a delivered order | Rejected; no data changes. |

### Video 6: Customer care sees operations

**Developer code:** [S3](demo-code-snippets.md#s3-lifecycle-in-one-transaction) (the `TicketTriageHook` hook follows the same pattern as the hooks in S2/S3)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Desk (`ha`) → New Support Ticket "Hàng bị vỡ, khách yêu cầu đền bù" (goods broken, customer asks for compensation), choose the waybill | Priority goes up to High automatically, SLA +24h, waybill status. |
| 2 | Bottom of the form: the "CARE replica (CDC)" card | This card reads the copy kept by the CDC worker, not the operations table directly (details in Video 16). The ticket's `waybill_status` field is read from the Waybill by the hook at save time. |
| 3 | `SCENES=6` | The API version. |

### Video 7: Multi-tenancy

**Developer code:** [S4](demo-code-snippets.md#s4-tenants-and-row-level-security)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Log in as `demo`, open `compare.html`, Screen `#/list/Customer` | Same URL, same DocType, two tenants. |
| 2 | Screen `#/form/Customer/C001` | Same ID, different data. |
| 3 | `SCENES=7` | Links do not cross tenants; `nam` on saigon → 403; missing tenant → 400; SQL: no tenant bound → 0 rows. |

### Video 8: Fail-closed security

**Developer code:** [S4](demo-code-snippets.md#s4-tenants-and-row-level-security)

`make infra-up && make build && make demo-express SCENES=8`: a second kernel connects as a superuser and refuses
to start. Open `deploy/docker/init/01-app-role.sql`: the application role is `NOSUPERUSER NOBYPASSRLS`.

### Video 9: For developers, writing an Astra app

**Developer code:** [S2](demo-code-snippets.md#s2-an-app-and-a-hook), [S12](demo-code-snippets.md#s12-desk-generated-from-metadata)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `make infra-up && make dev` | Dev mode with live reload (the Compose Keycloak is at `localhost:8180`). |
| 2 | The `astra-apps/express` tree | App = `ExpressApp` + YAML + hooks + MCP tools + TCC participant; depends only on `astra-spi`. |
| 3 | `OrderWaybillHook`, `OrderPostingHook` | `DocumentService` and `GeneralLedger` join the source document's transaction. |
| 4 | **Live**: add `- { name: zalo_id, type: Data, in-list: true }` to `customer.doctype.yaml` | No migration, no restart: the new column appears right away. |
| 5 | `ExpressFlowTest`, `LedgerTest`, `SeataTest` | Integration tests against a real PostgreSQL; `SeataTest` also runs a real Seata Server and Redis. |

**To be accurate:** after step 4, run `make demo-reset && make demo-seed`, because removing a field from a DocType makes
older documents that still have that field impossible to edit; safe schema evolution is still on the roadmap.

### Video 10: Running on Kubernetes

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `kubectl -n astra get pods` | 2 kernel replicas + infrastructure (Keycloak, Seata, CDC, PostgreSQL, Redis). |
| 2 | Desk → Customer Order | Order numbers stay sequential even with 2 pods writing: the counter uses a row lock. |
| 3 | `kubectl -n astra rollout restart deployment/astra-server` while Desk is open | Rolling restart, `maxUnavailable: 0`: Desk stays usable. **To be accurate:** there is no preStop/drain yet, so requests that land exactly when an old pod stops can fail (measured run: 2/47). |

### Video 11: Keycloak, one identity, per-tenant roles, per-DocType permissions (scene 9)

**Developer code:** [S5](demo-code-snippets.md#s5-keycloak-permissions-and-sod)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Desk → Sign in → Astra-themed login page (vi/en) → `tuan` | Authorization Code + PKCE in the browser; no password passes through Astra. |
| 2 | Top right: "Tuấn Lê · sales"; `tuan` creates an order and reopens it: the Submit button is there, the Cancel button is not (other people's orders are read-only) | Desk reads the effective permissions from `/api/iam/me` and hides buttons that are not allowed. |
| 3 | `SCENES=9` | `tuan` (sales) cannot scan or cancel orders; `quang` (ops) can scan; `ha` (care) opens tickets; `khoa` (accountant) can cancel. |
| 4 | Keycloak group `/tenants/saigon-express/ops` | Roles are Keycloak groups; the kernel reads them from the token and does not trust headers. |
| 5 | Desk (`khoa`) → CODRemittance `CODR-…-0002` (350,000, created by `khoa` during the seed) → Submit; then log in as `thu` → Submit | **Segregation of duties**: the maker cannot approve their own document (403); `thu` can. Record right after `demo-seed`: scene 10 pays out another 100,000 to C001, after which 350,000 would exceed the amount owed. |

**To be accurate:** row-level permission conditions and SAML SSO are still on the roadmap.

### Video 12: Append-only ledger (scene 10)

**Developer code:** [S6](demo-code-snippets.md#s6-append-only-ledger)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Desk (`khoa`) → General ledger | Trial balance on the Vietnamese chart of accounts (TT200: 112, 131, 1388, 3388, 511). |
| 2 | `tuan` submits an order → the form shows the "Ledger entries" card | Debit 131 / Credit 511 in the same transaction as the order. |
| 3 | Cancel the order | Two new **reversing** rows (`reversal`); the old rows stay unchanged. |
| 4 | `quang` scans Delivered | Debit 1388 / Credit 3388: COD cash collected by the courier, owed to the shop. |
| 5 | Terminal: `UPDATE astra_ledger …` | `ERROR: astra_ledger is append-only`, blocked in the database, even for the table owner. |
| 6 | Partitions section | Monthly partitions, created ahead of time by the kernel. |
| 7 | CODRemittance above what the shop is owed → 422; maker/checker | No paying out more than is owed; maker ≠ checker. |

**To be accurate:** a trigger blocks UPDATE/DELETE; the role that owns the table can still DROP/ALTER, so production should run
the kernel with a role that does not own the tables. An audit hash chain is still on the roadmap.

### Video 13: Per-tenant overlays (scene 11)

**Developer code:** [S7](demo-code-snippets.md#s7-tenant-overlays)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `tenants/hanoi-express/overlays/customer-order.overlay.yaml` | Hanoi adds a VAT field, an "Island" zone and a required note; the Express app does not change a single line. |
| 2 | `compare.html`, Screen `#/new/CustomerOrder` | The Hanoi side has fields labelled "✚ tenant overlay". |
| 3 | Desk (`mai`) → DocType metadata → CustomerOrder → Tenant overlay → add `gift_wrap` → Save overlay | Applied immediately, no restart; other tenants are not affected. When done, click Remove overlay so scene 11 still shows saigon-express without an overlay. |
| 4 | An overlay that changes a field type → 422; `tuan` edits the overlay → 403 | An overlay cannot break the app; only the tenant admin can edit it. |

**To be accurate:** overlays add/modify fields, they do not add hooks or permissions yet; they cannot remove an app's fields.

### Video 14: Tier 2 Wasm, a tenant's own freight formula (scene 12)

**Developer code:** [S8](demo-code-snippets.md#s8-wasm-tier-2)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `astra-apps/express/wasm/tet-promo/src/lib.rs` | A Tết promotion written in Rust, compiled to 421 bytes of WebAssembly. |
| 2 | Desk (`mai`) → Pricing Rule → open the rule → `self_test` | The kernel test-runs the module in the sandbox on save. |
| 3 | Turn on `active` → create a B2B order (C001, Domestic, Express, 2.2 kg, COD 500,000) | Fee 73,000 → **51,000 ₫**, "Priced by: PRL-001 … (Wasm)". Turn `active` off again afterwards, otherwise B2B orders in the other videos will no longer be 73,000. |
| 4 | New Pricing Rule → choose the file `astra-apps/express/wasm/modules/runaway.wasm` (infinite loop) → Create | "module rejected (timeout): script exceeded its 50 ms deadline and was stopped"; the kernel keeps running normally. |
| 5 | Talk about the limits | 1 MiB maximum memory, no host functions, a new instance per call. |

**To be accurate:** Chicory has no instruction counting (fuel) yet, so the CPU limit is a time deadline. Tier 1
(JVM hot-swap) is still on the roadmap.

### Video 15: MCP, an AI agent uses Astra with the user's permissions (scene 13)

**Developer code:** [S9](demo-code-snippets.md#s9-mcp-for-ai-agents)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `SCENES=13` | MCP 2025-11-25 handshake, 7 tools (5 from the kernel, 2 contributed by the Express app). |
| 2 | `search_documents` | `pii: true` fields are masked before they reach the model: phone numbers keep only the last 3 digits (`*******567`), names and addresses keep only the first letter of each word (`L*** V*** D***`); `shipping_fee` is unchanged. |
| 3 | `scripts/mcp-claude.sh ha saigon-express` → `claude` → "Bưu kiện WB-2610-000002 đang ở đâu, có khiếu nại không?" (where is parcel WB-2610-000002, are there complaints?) | Claude calls `track_waybill` and answers with the route history and the ticket. |
| 4 | Ask "tạo đơn hàng mới cho C001" (create a new order for C001) | Rejected: `ha` (care) is not allowed to create orders, so the agent cannot exceed the user's permissions. |
| 5 | `scripts/mcp-claude.sh khoa` → "Khách nào đang chờ trả COD?" (which customers are waiting for COD payouts?) | `cod_outstanding` reads from the ledger. |

**To be accurate:** the client uses a static bearer token (the dev client's 1-hour token); OAuth discovery for MCP,
autonomous AI agents and vector search are still on the roadmap. PII masking applies to the MCP channel, not to Desk.

### Video 16: Rust CDC + Sync-Aware LSN (scene 14)

**Developer code:** [S10](demo-code-snippets.md#s10-rust-cdc-and-sync-aware-lsn)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | Desk → Replication (CDC) | The Rust worker decodes the WAL (`pgoutput`), lag of a few ms, bytes remaining. |
| 2 | `SCENES=14`: pause the worker, `quang` scans a parcel | The response carries `X-Tx-LSN`. |
| 3 | `ha` reads the replica with `X-Required-LSN` | **202 Sync Pending**, no stale data returned. |
| 4 | Restart the worker | 200 after a few tens of ms; steady state: the replica catches up a few tens to over a hundred ms after the write (varies between runs). |
| 5 | Desk: open the Waybill just scanned → the "CARE replica (CDC)" card | "sync pending" → "in sync" automatically (read-your-writes) if the worker catches up within the 10 s that Desk keeps polling; if the worker is off longer, reopen the form. |
| 6 | `pg_replication_slots` | Logical slot `astra_cdc` (`active = f` is normal: the worker reads the slot with SQL peek/advance and does not hold a streaming connection); the worker writes the replica **under RLS**, without BYPASSRLS. |

**To be accurate:** the worker reads the slot with `pg_logical_slot_peek_binary_changes` every 50 ms (at least once,
idempotent upsert) and does not use the streaming protocol yet; nothing is pushed to Apache Doris yet.

### Video 17: Seata, one submit across three resources (scene 15)

**Developer code:** [S11](demo-code-snippets.md#s11-seata-at-and-tcc)

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `customer-order.doctype.yaml`: `transaction: global` | The kernel opens a global transaction around submit/cancel. |
| 2 | `SCENES=15`: submit an order for C001 | The **AT** branch deducts the credit limit in the `bms` schema (its own role); the **TCC** branch reserves a pickup slot in Redis; the kernel transaction commits last. |
| 3 | An order for C002 over the credit limit | 422; the order stays a draft, no waybill, no journal entry, no pickup slot taken (BMS rejects it before the TCC branch is reached). |
| 4 | A fully booked day: TCC rejects **after** AT has committed | Seata restores the credit limit from the undo log; `undo_log` is clean; Redis reserved goes back to 0. |
| 5 | Log `Global transaction … rolled back` | A real Seata Server 2.6.0 (JDK 25) coordinates. |

**To be accurate:** Seata Server uses the file store (enough for the demo; production uses DB/Raft); TCC handles
idempotency/empty rollback/suspension per branch itself and does not use Seata's fence table yet; the kernel commits its own
transaction last (last resource) and is not yet a Seata branch.

### Video 19: HRM, HR and payroll

**Developer code:** `astra-apps/hrm` (`PayrollEntryHook`, `SalaryCalculator`, `astra-apps/hrm/wasm/courier-kpi`); test `HrmPayrollTest`.

Goal: an HR and payroll app written on the public SPI, following the Frappe HRMS concepts (Employee, Salary
Component, Salary Structure, Salary Structure Assignment, Attendance, Additional Salary, Payroll Entry, Salary
Slip). Attendance determines working days, the courier bonus is a tenant Wasm module, and each Payroll Entry writes exactly **one**
accrual journal entry to the ledger. Only the concepts are taken; no code, schema or UI is copied from ERPNext/HRMS.

Setup: `make demo-seed` loads September 2026 (26 working days) for saigon-express: NV001 has full attendance, delivered 1,067
parcels; NV002 absent on 15/9, half day on 22/9, delivered 1,007 parcels; NV003 started on 14/9, has a 500,000 bonus; NV004 on unpaid
leave on 29/9. The script logs in as `hanh` (hr). If the realm just changed, run `scripts/keycloak-sync-realm.sh` to get `hanh`.

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `make demo-hrm SCENES=1` | App `io.astra.hrm`, 8 DocTypes in module `hrm`, depends only on `astra-spi`. 8 Salary Components, including social insurance (BHXH) 8 %, health insurance (BHYT) 1.5 %, unemployment insurance (BHTN) 1 % (accounts 3383, 3384, 3386); Salary Structure COURIER and 4 assignments. |
| 2 | `SCENES=2`: the Rust code in `astra-apps/hrm/wasm/courier-kpi` | Tiered bonus: 1,500 ₫/parcel up to parcel 600, 2,000 ₫ up to parcel 1,000, 2,500 ₫ from parcel 1,001, plus a 500,000 attendance bonus for full attendance. On save, the kernel test-runs it: `kpi(1000 pieces, 26/26 days) = 2200000 VND`. An infinite-loop module (LOOP) → 422 "module rejected (timeout)". |
| 3 | `SCENES=3` | NV002: Absent 15/9, Half Day 22/9; NV004: On Leave / Leave Without Pay 29/9. `quang` (ops) can create and submit today's attendance, gets 403 on Salary Slip; `tuan` (sales) gets 403 on Payroll Entry. |
| 4 | `SCENES=4` | Save a draft Payroll Entry `PAY-2610-001` (series by creation date): 4 draft salary slips, computed in parallel with one virtual thread per slip, in the same transaction as the entry. |
| 5 | `SCENES=5` | NV002's slip line by line: `100% of 6500000 (base) × 24.5/26 days` = 6,125,000; KPI `Wasm kpi(1007 pieces, 24.5/26 days)` = 1,717,500; BHXH `8% of 6500000 (base)` = 520,000. NV003: 15 working days, BONUS 500,000. Editing `net_pay` → 422 read-only. |
| 6 | `SCENES=6`: submit the entry | Salary slips and the ledger in one transaction. Entry: 4 employees, gross 35,837,501, deductions 3,517,500, net 32,320,001. One 5-line accrual journal entry: Debit 642 35,837,501; Credit 3383 2,680,000; Credit 3384 502,500; Credit 3386 335,000; Credit 334 32,320,001. Each slip has `accrual_voucher` set to the entry name. Cancelling NV001's slip on its own → 422 "cancel the payroll entry instead"; running September payroll again → 422 "NV001 already has submitted salary slip …". |
| 7 | `SCENES=7` | The MCP tool `payroll_summary` returns totals and slip lines with masked employee names (`N*** V*** A***`); `tuan` calls it → `isError: true`, not permitted. |
| 8 | `SCENES=8`: cancel the entry | The slips move to docstatus 2; the entry's journal entry gets 5 more lines with `is_reversal: true`; the payroll account balances return to 0. No row is deleted. |

**To be accurate:**
- No personal income tax is computed; net is gross minus the employee share of insurance. The employer share
  (BHXH, BHYT, BHTN, KPCĐ) is not computed. Insurance rates and caps are seed configuration for the demo and have not been checked against current
  regulations; the rule that 14 or more days of leave means no BHXH contribution is not handled.
- Minimal attendance: no holiday calendar, shifts, check-in or leave requests with leave balances; the working week
  is fixed Monday to Saturday; days without an attendance record count as worked. Parcel counts are entered by ops with attendance, not yet pulled
  automatically from Waybill or STMS through CDC as the use case describes.
- Only 4 calculation types (Fixed, % base, % gross, Wasm), no formula editor yet. No salary payout journal entry
  (Debit 334 / Credit 112), no bank file export. The accrual journal entry records totals per account, not
  per employee on 334; all cost goes to 642, with no cost-center allocation.
- The batch runs inside the request that saves the entry (up to 1,000 employees per run); there is no month-end background job or
  leader election yet. Salary slips and the ledger share one PostgreSQL transaction, without Seata.
- Employees cannot view their own salary slips yet: the `if-owner` permission applies to writes only, not to reads.
- There is no dedicated payroll Desk yet: the generic Desk shows slip lines as "N item(s)".
- The figures in the table above are the expected results computed from the hook's logic; use the on-screen numbers from the actual recording.

### Video 20: STMS, vehicle dispatch with Timefold + AI copilot

**Developer code:** `astra-apps/stms` (`solver/RoutePlanner`, `Replanner`, `copilot/DispatchCopilot`, `copilot/MockLlmClient`); tests `RoutePlannerTest`, `StmsDispatchTest`, `StmsMcpTest`.

Goal: the vehicle routing problem (VRP with capacity and time windows) solved by Timefold Solver inside the kernel; when an
incident happens, the plan is re-solved from the current routes; the dispatcher gives commands in Vietnamese through the copilot, and the copilot
calls those same MCP tools. **The solver, replanning and the tools are real; the copilot's language model is
a simulated script for the demo** (`llm: "mock"`). Every frame showing the copilot must show the label "LLM giả lập (kịch bản demo)" (simulated LLM, demo script).

Setup: `make demo-seed` creates, for each tenant, a fictional hub (saigon-express: HCM-TB Kho Tân Bình), 3 vehicles and 20
delivery/pickup stops for the seed date (`STMS_DATE`, defaults to today in Vietnam time; use the same value for seed
and demo). The script logs in as `linh` (dispatcher). Run the scenes in order: scenes 3 to 8 use the plan created by scene 2.

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `make demo-stms SCENES=1` | 5 DocTypes: Hub, Vehicle, DeliveryStop, DispatchPlan, DispatchEvent. Hub HCM-TB, 3 vehicles (300/250/250 kg), 20 stops with time windows and Normal/VIP/Urgent priority. |
| 2 | `SCENES=2` | Save a DispatchPlan with `solve: true`: Timefold solves within a 5-second budget. Hard constraints: capacity, time windows; soft: distance, serving priority stops early, load balancing. Result Solved, `0hard/…soft` (feasible), about 99 km, 2 vehicles, about 1 second; prints the route table. |
| 3 | `SCENES=3`: copilot "Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác" (vehicle 51C-12345 broke down in Thủ Đức, move all its orders to other vehicles) | The response has `llm: "mock"` and `llm_note`; tool calls `stms_vehicle_unavailable` then `stms_explain_plan`. The explanation starts with `[LLM giả lập – kịch bản demo]`, states how many stops changed vehicle and the score before → after, still 0hard. The solver re-solves from the current routes (warm start). |
| 4 | `SCENES=4`: "Ưu tiên khách VIP trước 10h, cân bằng tải giữa các xe" (prioritize VIP customers before 10:00, balance load across vehicles) | The copilot calls `stms_set_weights` (priority 500, balance 10). VIP/urgent stops are served before 10:00, at the cost of a longer distance. The soft score changes scale when the weights change, so only compare plans with the same weights. |
| 5 | `SCENES=5`: "Thêm đơn gấp 30kg ở Bến Thành trước 11h" (add an urgent 30 kg order at Bến Thành before 11:00) | `stms_add_urgent_stop` with coordinates taken from the simulation's fixed list of place names; the order is inserted into one vehicle, VIP/urgent stops are still on time. |
| 6 | `SCENES=6` | Without the copilot: `stms_traffic_delay` (Thủ Đức, 4 km radius, 2.5 times slower) and `stms_change_time_window` (Linh kiện Tân Tiến → 08:00 to 09:00). Each call prints the score before/after, km, `solve_ms` and the moved stops; still 0hard. |
| 7 | Desk (`linh`) → the dispatch copilot page, type one of the commands from scenes 3 to 5 | The page sends the command to `POST /api/stms/copilot`, the same copilot as the `stms_copilot` tool; it shows the simulated LLM label, the tool calls that ran and the explanation. Open the DispatchPlan: `route_summary` (text route table) and `last_change` (moved stops). |
| 8 | `SCENES=7` | `quang` (ops) calls `stms_vehicle_unavailable` → "not permitted: read DispatchEvent"; `ha` (care) calls `stms_explain_plan` → not permitted; the same plan name in hanoi-express → "not found". |
| 9 | `SCENES=8` | DispatchEvent log: source (Copilot or MCP), score before → after, moved stops, `solve_ms`. Submit → docstatus 1, Dispatched; PUT `{solve: true}` → 422 "submitted documents are immutable"; `stms_set_weights` → "is dispatched and frozen; amend it to replan". |

**To be accurate:**
- The copilot does not call any language model: `MockLlmClient` matches commands against fixed Vietnamese patterns and a
  small list of place names; commands outside the script produce no tool call. The explanation is written from the solver's
  real results. The plug-in point for a real LLM provider is the `LlmClient` interface; there is no implementation yet.
- No map, real road network or live traffic: distance is straight-line distance × 1.3, at a fixed speed
  of 25 km/h, and a "traffic jam" is a slow zone declared by the dispatcher. No geocoding: stops come with coordinates.
- Replanning means re-solving from the current routes, without keeping the solver running all day; time does not advance, so
  delivered stops and vehicles on the road are not locked. Adding an urgent order and re-solving are two transactions: if the re-solve fails,
  the new order is still saved.
- Capacity is one combined total per vehicle (deliveries and pickups added together); there are no pickup-before-delivery constraints, multiple trips,
  driver shifts and breaks, return-to-hub deadlines, vehicle types or vehicle costs. One hub per plan, no transfers between hubs.
- No AI load forecasting (Tier 1), driver app, GPS or field ETA updates yet. No route map yet:
  Desk shows a text route table and JSON.
- The demo has about 20 stops and solves in 1 to 2 seconds; larger problems have not been measured. On-screen numbers come from the actual
  recording; numbers in the phase report were measured on test data and will differ slightly from the seed data.
- DispatchEvents and urgent orders created by tools have the owner `stms-mcp` / `stms-copilot`; the real caller is recorded
  in the MCP server log.

### Video 21: SAML SSO and SCIM

**Developer code:** `deploy/keycloak/realm/acme-corp-idp-realm.json`, `deploy/keycloak/realm/astra-realm.json` (identity provider `acme-corp`), `astra-iam` (`ScimResource`, `ScimDirectory`, `ScimPatch`); tests `SamlBrokerTest`, `ScimTest`.

Goal: a company's employees log in to Astra with their company's IdP over SAML 2.0, and IdP groups
become tenant roles; the company's identity management system creates, assigns roles to, disables and deletes accounts over
SCIM 2.0, only within its own tenant.

Setup: Keycloak must have the realm `acme-corp-idp`, the identity provider `acme-corp` and the SCIM clients.
`make infra-up` (runs `scripts/keycloak-compose-dev.sh`) or `make k3d-up` syncs the realm; if Keycloak was already
running, re-run `scripts/keycloak-sync-realm.sh`, then restart astra-server to get `/scim/v2`. The video's
tenant is `acme`. The users `linh` and `bao` here belong to the IdP realm `acme-corp-idp`; this is not the dispatcher `linh`
from Video 20.

| Step | Action | Narration |
| --- | --- | --- |
| 1 | `make demo-sso SCENES=1` | The SP-initiated flow replayed with curl: Desk → Keycloak (`kc_idp_hint=acme-corp`) → AuthnRequest to the IdP → `linh` logs in at the IdP → signed SAMLResponse back to the broker → OIDC code. Assertion: NameID `linh`, Audience `urn:astra:sp`, `memberOf astra-editors`, RSA-SHA256 signature on both assertion and response. Token: `preferred_username: acme-corp.linh`, `groups: ["/tenants/acme/editor"]`; `/api/iam/me` → acme `[editor]`; create an Article → 201. |
| 2 | `SCENES=2` | `bao`'s SAMLResponse has `astra-readers` changed to `astra-admins` in transit → Keycloak returns 400 "Chữ ký không hợp lệ…" (invalid signature), no code is issued. |
| 3 | `SCENES=3` | Discovery: `ServiceProviderConfig` (`patch: true`, filter maximum 200, `bulk: false`, `etag: false`), ResourceTypes User and Group. Client-credentials token for `scim-acme`: `scim_tenant: acme`, role `scim`; the tenant is taken only from this claim. |
| 4 | `SCENES=4` | `POST /scim/v2/Users` → 201, `application/scim+json`; filter `userName eq "…"` → 1 result; creating a duplicate → 409 `uniqueness`. The new user has no role yet: `/api/doc/Article` → 403 "not a member of tenant acme". |
| 5 | `SCENES=5` | Groups are the tenant roles: admin, editor, author, reader. PATCH adds a member to editor → after a new login the token has `/tenants/acme/editor`, create an Article → 201. |
| 6 | `SCENES=6` | PATCH `{"op":"Replace","path":"active","value":"False"}` (the form Entra sends) → the account is disabled: password grant → `invalid_grant` "Account disabled", refresh token → "Session not active". An access token already issued stays usable until it expires; the scene prints the expiry time. |
| 7 | `SCENES=7` | globex's token: an acme user → 404; filter → 0 results; header `X-Astra-Tenant: acme` → 403; adding an acme user to a globex role → 400 `invalidValue`; filter `co` → 400 `invalidFilter`. Errors follow the RFC 7644 format. |
| 8 | `SCENES=8` | `DELETE` → 204, GET again → 404. The demo's SCIM user is deleted when the script ends; the linked accounts `acme-corp.linh`, `acme-corp.bao` are kept, like real linked accounts. |

**To be accurate:**
- The IdP is a Keycloak realm (`acme-corp-idp`) in the same container, not Okta, Entra or ADFS; the SAML protocol
  between the two sides is real. The demo replays the browser steps with curl and does not follow the redirect back to Desk.
- Assertion and response are signed and verified; the assertion is **not encrypted**, the AuthnRequest is **not signed**. Only
  SP-initiated; IdP-initiated is not configured; **Single Logout is not wired up and not tested**.
- On first login Keycloak creates the account `acme-corp.<username>`; if the email matches a local account,
  Keycloak asks to link them, and the demo does not show this step. The group → role mapping is fixed in the realm configuration and only for
  tenant `acme`; each other company needs its own IdP and mapper.
- SCIM has no Bulk, ETag/`meta.version`, sort, `/Me`, `meta.lastModified` or `attributes` parameter yet;
  `excludedAttributes` only applies to Groups `members`. Filters support only `userName eq` and `displayName eq`.
  `externalId` is ignored; only one email is kept; `userName` cannot be changed.
- Groups are fixed because they are the tenant roles: creating or deleting a group returns 501, only membership can change. A tenant's SCIM
  client only sees and edits users it provisioned. Deleting through SCIM also deletes the Keycloak account.
- Role changes take effect from the next login or refresh. After `active=false`, an access token already issued stays valid
  until it expires, because Astra verifies the JWT offline (5 minutes for regular clients, 1 hour for the demo client
  `astra-dev-cli`).
- Listing users fetches members from Keycloak and paginates in memory, with one extra group read call per user: enough
  for the demo, not meant for hundreds of thousands of users. The SCIM client secrets in the realm JSON are for local environments only.

### Video 18: Roadmap, the rest of use-case-01 *(In development)*

Every frame is labelled **"Roadmap / In development"**.

| Done (Videos 1 to 17, 19 to 21) | Still on the roadmap |
| --- | --- |
| JSONB + projection index, ledger, separate schema for BMS | Multi-schema for every module, materialised hot columns |
| Seata AT + TCC | AT for the kernel itself, TCC fence table, Seata DB/Raft store |
| Keycloak, DocType permissions, SoD | Row-level permission conditions |
| Tier 0, Tier 2 Wasm | Tier 1 Isolated JVM (hot-swap), Wasm instruction counting |
| Rust CDC + LSN | Streaming protocol, Apache Doris, NOC dashboards |
| MCP + PII masking | Autonomous agents, pgvector RAG, OAuth for MCP |
| Video 19: HRM (HR, payroll), accrual journal entries through `GeneralLedger` | Personal income tax, employer insurance share, salary payout, month-end background payroll job, employee self-service salary slips |
| Video 20: STMS (Timefold), replanning, dispatch copilot | A real LLM for the copilot, real maps and traffic, load forecasting (Tier 1), driver app |
| Video 21: SAML SSO, per-tenant SCIM 2.0 | Single Logout, SCIM Bulk/ETag, full SCIM filters |
| (not yet) | Audit hash chain, field encryption |

---

## 4. Pre-publication checklist

- [ ] Each video only claims what is in the table in section 2; read the "To be accurate" sections before recording the voice-over.
- [ ] Video 18 and every roadmap frame carry the "In development" label.
- [ ] Video 20: every frame with the copilot clearly shows "LLM giả lập (kịch bản demo)" (simulated LLM, demo script); never say or imply there is a real language model.
- [ ] Performance figures (CDC lag, sandbox time) come from the actual recording, not rounded to look better than reality.
- [ ] Customer data is fictional seed data (`scripts/demo-seed.sh`).
- [ ] Demo passwords appear only on the local stack; do not record screens that show tokens.
- [ ] Videos with CC BY music: the video description includes a credit line (Video 0: see `docs/video/teaser/CREDITS.txt`).
