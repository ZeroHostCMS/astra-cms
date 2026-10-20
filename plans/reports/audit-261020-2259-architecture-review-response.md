# Architecture audit response — 2026-10-20

External audit of the README/spec, verified claim by claim. Applied to README.md and docs/explain.md in the same change.

## Verified and accepted

| # | Audit finding | Verification | Action |
| --- | --- | --- | --- |
| 1 | Stored generated columns rewrite the table; `ADD COLUMN` has no `CONCURRENTLY`, so "projections are online" was wrong | PostgreSQL docs: adding a `GENERATED ... STORED` column rewrites; only `CREATE/DROP INDEX CONCURRENTLY` is online. PG 18 virtual generated columns are metadata-only but cannot be indexed | Projection = expression index (online). `projected: materialised` = nullable column + batch backfill + kernel dual-write + concurrent index. Generated columns removed from spec |
| 2 | p99 < 40 ms for a Seata global TX with Raft TC is not credible | Critical path: begin + 2 branch registers + global commit, each a Raft-replicated write, plus undo-image SQL and Redis Lua. Tens of ms median, p99 well above 40 under load | Split targets: single-store save p99 < 15 ms; global TX p50 < 40 ms, p99 < 150 ms |
| 3 | Tier 1 "< 1 µs" is dispatch only | Agreed | Added end-to-end hook target (< 50 µs incl. capability check, context, audit span) |
| 4 | Tier 2 "< 5 ms" ignores Chicory compile cost | Chicory runtime compiler compiles Wasm→bytecode; cold compile is tens to hundreds of ms | Warm target kept; cold compile target < 500 ms, performed at rollout warm-up, off request path |
| 5 | "Hot-swap < 500 ms" conflates propagation, cutover and drain | Agreed | Three targets: propagation < 2 s (50 pods), cutover < 10 ms, drain bounded by request timeout |
| 6 | Pod ready < 2 s unrealistic on JVM | Agreed | JVM < 5 s, native < 1 s |
| 7 | JSONB write amplification for hot transactional DocTypes understated | PostgreSQL UPDATE rewrites the row; HOT updates need unindexed changed columns and free space (`fillfactor`); large JSONB stays in TOAST when unchanged | Added "hot DocTypes are not JSONB-first" rules: hot fields materialised, unindexed where possible, fillfactor < 100, counters/balances in ledger or Redis, narrow side DocType for extreme churn |
| 8 | No projection/index budget; flexibility eats performance | Agreed | Quotas per DocType (8 projections / 12 indexes default) and per tenant; approval step for materialised projections on large tables; metadata quotas listed under tenancy |
| 9 | Seata TC is a central choke point | Seata supports multiple TC clusters mapped by transaction service group | Documented sharding by service group, smart bypass keeps TC off most paths |
| 10 | No memory budget for plugin host | JVM cannot enforce heap per ClassLoader; Metaspace per loader is observable; Wasm memory is a hard cap | Stated honestly: Metaspace tracked and bounded via `keep-previous`; heap per Tier 1 app observable not enforceable; Wasm hard-capped |
| 11 | "Thousands of tenants per pod" unproven | Agreed | Reworded as a hypothesis to benchmark, in thesis and performance sections |
| 12 | Wasm "hard sandbox" overstated without fuel metering | Chicory has interrupt-driven cancellation and memory limits, no instruction counting | Sandbox note now says memory-hard, time-bounded; instruction counting is a Phase 2 requirement before "hard" is claimed; deadline verified in interpreter and compiled modes |
| 13 | "Small typed SPI" contradicts 20+ extension points | Agreed | Reworded: SPI is large, each task is narrow; AGENTS.md per extension point; test kit is the trust mechanism |
| 14 | MCP tool explosion | MCP `tools/list` supports cursor pagination | Namespacing, tenant expose lists, tool packs by role, paginated listing |
| 15 | AI surface too late (Phase 7) | Agreed | Thin MCP (`doc.search/get/create`) moved to Phase 2 |
| 16 | Agent analyses need grounding/citations | Agreed | `AgentContributor` responses carry `sources`; gateway rejects unsourced answers for financial DocTypes |
| 17 | Scope is a multi-year programme, not a project; cut an MVP | Agreed | Roadmap preamble reframes as programme; thin slice defined (CMS DocType + tenancy + REST now; then minimal GL, Seata spike, one Tier 2 script, one canary rollout, thin MCP); compliance as profiles over time |
| 18 | Seata incubating, Raft TC maturity unknown, Seata on virtual threads unproven | Seata 2.6.0 is `incubator-seata`; no public evidence of vthread deployments | Seata spike moved to Phase 2 with explicit go/no-go; Phase 4 names the fallback (outbox + idempotent TCC without central coordinator) |

## Corrections to the audit

* Chicory is maintained under the Dylibso organisation with Red Hat contributors, not a Red Hat project.
* "Hundreds of ms" for the global transaction is pessimistic for an in-region median; the spec now gives p50 and p99 separately rather than one number.

## Not changed

* Typed extension points reduce "free-form validate" flexibility relative to Frappe Python scripts. This is a deliberate trade; Tier 2 scripts give the free-form escape hatch with a sandbox. Noted in explain.md risks already.

## Unresolved

* Whether Chicory's compiled mode honours interrupt-driven deadlines reliably; to be verified in the Phase 2 test kit.
* Real tenant density per pod; first benchmark in Phase 2.
