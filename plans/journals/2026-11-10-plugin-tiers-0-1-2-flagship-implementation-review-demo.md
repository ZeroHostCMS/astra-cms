---
title: "Plugin tiers 0/1/2 flagship: implementation, review, demo"
date: 2026-11-10
summary: "Built Tier 1 isolated-JVM host + per-pod rollout, Drools rules-as-data, layered pricing and the AI anomaly loop; fixed a critical DRL RCE and ran the demo fail=0."
---

# Plugin tiers 0/1/2 flagship: implementation, review, demo

## What happened
Implemented the 9-phase plan `plans/261029-0017-plugin-tiers-flagship-video` on branch
`feat/plugin-tiers-demo` (committed as 9 scoped commits cab0843..b94ba00).

- **Tier 1 host** (`astra-plugin-host/jvm`): class loader per version exposing only the JDK +
  `io.astra.spi`, MethodHandle dispatch, bulkhead (permits + circuit breaker + deadline),
  `PartnerHttp` capability, class-loader leak tracking.
- **Rollout control plane** (`astra-plugin-host/rollout`): `AppRelease`/`AppPin`, per-pod canary
  with auto-rollback under a Postgres advisory lock, `release_propose`/`release_status` MCP tools.
- **Rules as data** (`astra-kernel/rules`): Drools 10.1.0 compiled at run time; `RuleValidator`
  is an allowlist over the DRL text.
- **Layered pricing** (`astra-apps/express`): Drools base lines → active PricingRule base-line
  replace → customer Wasm plugin (new `callBytes` bytes ABI + Rust PDK); full `price_breakdown`.
- **Market integrations**: FastShip + einvoice-vn/kh Tier 1 plugins, booking/e-invoice jobs that
  call partners outside the submit transaction. Third tenant `angkor-express`.
- **AI anomaly loop**: `ops.anomaly` slot, `OpsAlert` routed by role, backtest/snapshot/my_alerts
  tools, `aibot` release-bot token that can propose but not approve.

## Key problems and root causes
- **Drools KieBase looked un-collectable** in plain-JUnit leak tests: the Quarkus delayed log
  handler buffered log records that referenced the session/KieBase. Fix: activate the handler in
  `@BeforeAll`. (Saved to memory `astra-drools-jdk25`.)
- **Critical RCE in RuleValidator** (found by security review): a Java char literal `'"'`
  desynchronised the string-blanking lexer from the compiler, running arbitrary Java in the core
  JVM at save/warm-up. Fixed by refusing single quotes/text blocks/backslashes; regression tests
  use the reviewer's exact payloads.
- **Tier 1 jar ran on draft save** before approval (code review C1). Fixed: save reads the manifest
  only (`IsolatedJvmHost.manifestOf`); the jar loads after approval. Regression test proves a jar's
  static initializer stays dormant until approval + a routed call.
- **Rollout correctness** (review H2/H3/H5): cancelled canary no longer empties a slot (guarded
  `patchIf`), an older canary cannot resurrect, `release-bot` lost `write` so it cannot swap the
  reviewed artifact.
- **Demo harness**: a 30-minute `demo-reset` hang (Redis tcc-done marker accumulation + an
  `astra_series` wipe that caused name collisions), an OOM under load, and pickup-pool exhaustion.
  Reset is now ~1s (keep the counters, one `FLUSHALL`); pods got 2Gi; demo-tiers keeps the pickup
  pool open during load.

## Verification
- Full suite green: 104 tests, BUILD SUCCESS (JDK 25).
- `make demo-tiers` on the 2-pod k3d cluster: every traffic scene fail=0 (T0 52, T0b 60, T1a 129,
  T2a 331, T2b 55). Both carrier versions served during the canary, the bad build rolled back,
  `PartnerHttp` denied the ungranted host, Shop ABC inherited the rule surcharge, alerts routed to
  ops/accountant/care, and the AI token got 403 on approve.

## Decisions
- Keep `DAILY_PICKUP_CAPACITY = 200` (real Video 17 constraint); the tiers demo replenishes the
  pool rather than changing the product constant.
- Pin Drools 10.1.0 (KIE has no official Java 25 support yet).
- Deferred review findings (H4 shared breaker across tenants, duplicate-invoice idempotency, the
  per-customer profile query) documented as known limitations; demo serves one tenant per slot.

## Next steps
- Phase 7 (video): record the two Claude Code sessions, render the ~7-min flagship + 3 clips,
  upload to R2. Gated on a public-upload confirmation and the `agg` tool (its git install was
  blocked by the permission classifier).

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
