# Platform feature demos: Keycloak, Ledger, Overlay, Wasm, MCP, CDC + LSN, Seata

Status: in progress · Branch: `feat/express-demo` (rebased on `origin/main`, which includes `feat/iam-keycloak`)
Builds on: [Astra Express demo](../261021-1621-express-demo/plan.md)

## Outcome
Each roadmap feature named in `docs/use-case-01.md` gets a **real, runnable thin slice** in the Astra
Express story, a terminal scene, a Desk surface where useful, tests, and a video script in
`docs/demo-video-series.md`. Nothing is mocked; what a slice does not do is stated in its video.

## Decisions (user, 2026-10-21)
- CDC: Rust worker (`astra-cdc-rust`). It decodes the `pgoutput` logical-replication stream by LSN, keeps
  `replica_*` tables and `_cdc_sync_watermark` up to date, and backs the Sync-Aware LSN filter.
- Seata: a real Seata Server 2.x, with **AT** mode (undo_log, DataSourceProxy) and **TCC** mode (Redis).
- Keycloak: already merged to `main`; integrate the Express demo with it (roles, Desk login, scripts).
- Execution: work through the phases below in order; each phase ends with tests and a demo scene.

## Phases
| # | Phase | Key deliverables | Status |
|---|-------|------------------|--------|
| 0 | Keycloak × Express | tenant roles (`admin, sales, ops, care, accountant`), Express DocType permissions, permission checks on submit/cancel/amend, realm users + `astra-desk` PKCE client, Desk sign-in, token-based seed/demo scripts, tests on `@TestSecurity` | done |
| 1 | Ledger | `astra_ledger` append-only (trigger blocks UPDATE/DELETE), partitioned by month, RLS; `GeneralLedger` SPI (balanced BigDecimal postings, reversal); order submit/cancel posts/reverses; `/api/ledger`; Desk "General Ledger" | done |
| 2 | Overlay | per-tenant `*.overlay.yaml` (add fields, modify field props); tenant-aware registry used by store, API, Desk, projections | done |
| 3 | Wasm Tier 2 | `astra-plugin-host` on Chicory; `ScriptRuntime` SPI; `PricingRule` DocType holding a tenant's module; Rust-built pricing module; memory limit + 50 ms deadline; runaway script is stopped | done |
| 4 | MCP | `/mcp` JSON-RPC (Streamable HTTP): tools `doctype_list`, `doc_search`, `doc_get`, `doc_create`, `waybill_track`, `ledger_balance`; same token, tenant, RLS and permissions; PII fields redacted | todo |
| 5 | CDC + LSN | `wal_level=logical`, publication, replication role; Rust worker; `replica_waybill`; `X-Tx-LSN` on writes, `X-Required-LSN` → `202 Sync Pending`; Compose + k3d deployment | todo |
| 6 | Seata | Seata Server in Compose + k3d; TM/RM bootstrap in Quarkus; AT branch on the BMS credit table; TCC branch reserving COD capacity in Redis; global rollback demo | todo |
| 7 | Docs | videos 12–19 in `docs/demo-video-series.md`; README thin-slice tables; feature map | todo |

## Acceptance
- `./mvnw -pl astra-server -am test` stays green, with new tests for each phase.
- `make demo-seed` + every scene of `scripts/demo-express.sh` run on the k3d stack.
- Each new video lists only behaviour that its scene demonstrates.

## Risks
- Seata AT parses SQL: the kernel's JSONB SQL (`RETURNING`, `ON CONFLICT`, `::jsonb`) is not proxied.
  AT runs only on the BMS credit datasource, which uses plain SQL.
- Java 25 / Quarkus compatibility of `seata-all` and Chicory; spike each before wiring.
- Logical replication needs `wal_level=logical` (restart) and a `REPLICATION` role; existing Compose
  volumes need `scripts/cdc-setup.sh` (backed-up first).
