# Code review: plugin tiers (feat/plugin-tiers-demo, uncommitted)

Date: 2026-11-10 (Asia/Saigon). Plan: plans/261029-0017-plugin-tiers-flagship-video/.
Snapshot note: someone else edited the tree while this review ran (LayeredPricing, QuoteBacktestTool, AnomalyBacktestTool, RuleEngine, RuleValidator(+Test), 14:45–14:57). The rollout/jvm files did not change after they were read.
Tests: not run. `mvn -o -pl astra-kernel,astra-plugin-host -am test` failed because the local `java` is 21 (class file 65) and the classes are built for Java 25 (class file 69). This is a local setup problem, not a code result.

## Critical

### C1. A Tier 1 jar runs inside the server JVM when a draft is saved, before anyone approves it
- AppReleaseHook.java:62 → IsolatedJvmHost.load → LoadedPlugin ctor (:57 `Class.forName(entry, true, …)`, :61 `newInstance`, :73 `plugin.start`).
- The `release-bot` role may create AppRelease (app-release.doctype.yaml permissions; ReleaseProposeTool:54,60; also generic MCP `create_document`).
- How it fails: the AI token proposes a jar whose static initializer reads env/DB credentials or other tenants' data, or opens a socket. That code runs on save. PartnerHttp's own javadoc says Tier 1 is for trusted code only, and Java 25 has no SecurityManager. Admin approval is the trust gate, and it comes too late.
- Fix: at save time, read only `META-INF/astra-app.yaml` from the jar (no class define/init) and check spi/entrypoint names. Load and start the plugin only after submit (admin). Or vet it in a separate JVM/process.

### C2. RuleValidator could be bypassed with single quotes, giving arbitrary Java in the server (fixed in the working tree at 14:57; check that the fix stays)
- RuleValidator.blankStringsAndComments only knew double quotes. `QuoteRequest( zone == '"' )` followed by RHS `System.setProperty(...); // "` passed the validator. Drools compiled it, and it ran during the warm-up in RuleEngine.compiled (:123). I proved this end to end against the snapshot with Drools 10.1 and ECJ: the property was set.
- Who could reach it: `accountant` (PricingRuleset create/write → PricingRulesetHook.beforeSave → selfTest) and `release-bot` (AppRelease tier=rules → AppReleaseHook.beforeSave → selfTest).
- Current tree: single quotes and text blocks are now refused. My probe is rejected, and `#` is not a comment in Drools 10, so that variant is closed too. Keep the regression test. Defense in depth: compile and warm up draft rule sets for non-admin callers in a separate process, or at least not in the request JVM. An allowlist that depends on matching the lexer exactly is fragile.

## High

### H1. Unload race leaves a dead plugin view cached for good (outage of that slot on that pod)
- RolloutController.forgetUnreferenced:146-157 computes `live`/`shas` from a snapshot, then unloads every loaded plugin not in `shas`. Meanwhile `plugin()`:63 can re-create the view through `jvm.load(bytes)`, which still returns the old LoadedPlugin because it has not been removed yet. Then `unload` stops it (handles cleared, instance null).
- After that, the view key `tenant/REL` is live, so it is never evicted, and its sha is referenced, so nothing reloads it. Every call fails with `has no method …` (LoadedPlugin:111). Carrier bookings stay Pending forever on that pod. A Canary gets rolled back for errors that are not its fault. A Live release is never judged, so nothing recovers.
- This is made much more likely because `RolloutJob.promote`:83 → `invalidate()`:131 *removes* the tenant from `tenants`. On the promoter pod, every tenant with an active canary drops out of `knownTenants` until its next request, so the next tick drops its views and unloads its plugins (repeated class-loader churn, plugin restarts, full GCs from checkLeaks).
- Fix: have the view check `plugin.isStopped()` and reload on use. Unload only plugins that no `views` value references, and run view creation and unload under one lock or generation counter. Make `invalidate` mark the state stale (loadedAt = 0) instead of removing it.

### H2. Promoter retires the old Live release even when the canary was cancelled meanwhile → slot has nothing serving
- RolloutJob.judge:112-119 calls `store.patch(...)` (ReleaseStore:65-77, which ignores `executeUpdate` count and has `WHERE docstatus = 1` only), then retires every Live release in the scope.
- How it fails: the promoter reads the canaries, an admin cancels the canary (docstatus 2, stage Retired), the patch updates 0 rows, and the old Live is still retired. Nothing serves the slot.
- Fix: return the row count and add `AND data->>'stage' = 'Canary'` to the patch. Retire the old release only when the patch hit 1 row, ideally in the same transaction.

### H3. Two canaries in one scope: the older one comes back after the newer goes Live and can retire it
- AppReleaseHook.onSubmit:96-106 always adds a new Canary. RolloutController.resolve:99-103 prefers any Canary on this pod over Live and takes the max name. judge:113-118 retires only *Live* releases.
- How it fails: v1.1 is Canary on pod-a and v1.2 is approved, also Canary on pod-a. v1.1 gets no calls (shadowed), so it is never judged. v1.2 goes Live. pod-a now resolves v1.1, the canary still listing pod-a, and serves the old version. v1.1 then gets calls, is promoted and retires v1.2.
- Fix: on submit, mark existing Canary releases in the same slot/scope as superseded, or refuse the submit while a canary is running.

### H4. LoadedPlugin (instance, circuit breaker, bulkhead) is shared across tenants
- IsolatedJvmHost.load:46 keys by jar SHA only. RolloutController:63 gives each tenant its own proxy over the same LoadedPlugin.
- How it fails: tenant A's traffic fills max-concurrency or opens the breaker. Tenant B's calls then fail `rejected`/`open`, which counts against B's release health and can roll back B's canary. Any per-instance cache in the plugin is shared between tenants without the plugin knowing which tenant is calling.
- Fix: keep the breaker and permits per tenant+release (in the view/handler), or load one instance per tenant.

### H5. Approval is not tied to the artifact the admin reviewed
- release-bot has `write, if-owner` on its own drafts. Submit (DocResource:96-104, DocumentStore.submit:242) takes no version or sha.
- How it fails: the admin reviews the self_test/sha, the bot swaps the artifact, and the admin clicks Submit. An unreviewed artifact gets approved, so the bot effectively decides what ships.
- Fix: submit sends the expected `version` (or `artifact_sha256`) and the server rejects it on mismatch. Or freeze bot writes once a draft is marked for review.

## Medium

- M1. LoadedPlugin.invoke:116-143: on timeout the permit is released and `inFlight` decremented while the plugin thread may still run (a non-interruptible loop or blocking call). The bulkhead no longer bounds concurrency, and `unload`'s drain (IsolatedJvmHost:70-72) sees 0 and calls `stop()` while the plugin is still running. Fix: release the permit when the task actually ends (in the task's finally), and track running tasks, not callers.
- M2. EInvoiceJob:39-47 never picks up `Issuing` again. A pod crash, or `SystemFields.write` throwing after 5 attempts, leaves the order stuck forever (CarrierBookingJob does have a stale-claim rule). Separately (PLAUSIBLE, depends on the partner): a plugin timeout after the partner already issued sets the order back to Pending and reissues it, giving a duplicate tax invoice. CarrierBookingJob:42-44,63-70 can double-book the same way. Send an idempotency key (order/waybill name) and add stale-Issuing recovery.
- M3. LayeredPricing.base catches only ScriptException, but RuleEngine.quote:83-89 rethrows any RuntimeException. `compiled()` throws DocValidationException when the live rule set no longer validates or compile fails. The order save then fails, which contradicts "never blocks the order". The same applies to non-ScriptException errors from `callBytes`. Catch RuntimeException there and fall back to the standard tariff.
- M4. CustomerPricingPlugin.apply:66-92: amounts are unbounded `long`, so the sum can overflow (e.g. set base=2^63-1, add a=2^63-1, add b=1002 → total wraps to 1000) and get past the floor check. Use `Math.addExact` and a per-line cap.
- M5. Plugin calls run on virtual threads that inherit the caller's context class loader (LoadedPlugin:118, IsolatedJvmHost:38). Plugin code calling `ServiceLoader`, Jackson etc. through the TCCL sees kernel/Quarkus classes, so the isolation the PluginClassLoader javadoc describes is not there in practice, and kernel classes can end up pinned by the plugin. Set the TCCL to the plugin loader around the call and around start/stop.
- M6. WasmSandbox `parsed` (map by sha) is unbounded. quote_backtest/anomaly_backtest/release_propose feed it arbitrary modules, so it grows without limit. RuleEngine's 16-entry LRU can also be filled by drafts, pushing the live rule set out and forcing a recompile plus 5 s warm-up inside a user's order transaction. Bound the cache (by size/weight), or keep drafts out of it.
- M7. CustomerProfileJob:47-57 issues one query per customer (up to 500 customers × 20k orders) on every pod. It runs serially in the same TenantJobRunner tick as booking/e-invoice (TenantJobRunner:38-46, SKIP concurrency), so it delays them. Customers past 500 are never updated. Fix: one aggregate query, and run it on one pod (advisory lock).
- M8. RolloutJob.flushHealth:60-69 drains the counters before writing. A DB error loses that window's calls/errors and aborts the tick, so promote is skipped too. Error counts can be lost while a canary is being judged. Fix: put the counts back on failure, and catch per entry.

## Low

- L1. RolloutController.resolve:88: a customer pin to a version that is missing or rolled back returns empty, so the slot is off for that customer. Tenant pins fall through to canary/live, customer pins do not.
- L2. LoadedPlugin.handles is a plain HashMap cleared in `stop()` while other threads may call `get` (data race). Use a volatile immutable map.
- L3. RolloutJob.checkLeaks:131-140: after the one warning, `leakChecks` never resets while a leaked loader stays in the list, so later leaks are neither GC-nudged nor reported. It also calls System.gc() in production.
- L4. LoadedPlugin.stop:161 swallows a plugin's stop failure with no log.
- L5. GrantedPartnerHttp:52 logs every denial at WARN (FastShip 1.1 triggers this on every booking), which floods the log. `check` trims the URL for the origin but not for `URI.create`.
- L6. ReleaseStore.serving:45 picks a pinned release with `findFirst` among several approved releases that share an app_id/version (allowed for wasm/rules), so which one is picked is not deterministic. Enforce unique (slot, app_id, version, customer) at submit.

## Checked, no issue found
- Cache keys: `views`/`health` keyed `tenant/release`. `artifacts`, WasmSandbox `parsed` and RuleEngine `bases` are content-addressed (sha). No cross-tenant data mixing apart from H4.
- release-bot cannot submit: there is no `submit` permission in the yaml, DocResource and DocumentStore.requireCaller check it, there is no MCP submit tool, and read-only fields (generator, stage, signed_by) are rejected on insert (DocumentValidator.rejectReadOnly).
- Promoter lock: `pg_try_advisory_xact_lock` is held for the whole promote. Cancel vs promoter patch is guarded by cancel's optimistic version check (cancel fails instead of overwriting); see H2 for the remaining hole.
- Outside calls: carrier and e-invoice calls run in TenantJob after the claim commits, not in a user's transaction. Anomaly checks and Wasm/rules run in the user's transaction but make no network calls.
- PII: the new tools return custom maps, which PiiMasking does not inspect (it masks only `{doctype,data}` shapes). Today they expose customer_name, customer codes and courier names, none of which is flagged `pii`. Any future pii field added to those outputs would leak unmasked, so add field-level masking there.

---

## Resolution (2026-11-10, applied on feat/plugin-tiers-demo)

Fixed in code, with tests:
- **C1** — A Tier 1 jar no longer runs on draft save. `AppReleaseHook.beforeSave` now calls `IsolatedJvmHost.manifestOf` (reads `META-INF/astra-app.yaml` only, defines no class); the jar is loaded and started by `RolloutController` after an admin approves it and a call is routed. Test: `ReleaseRolloutTest.aTier1JarDoesNotRunUntilItIsApproved` (a jar whose static initializer sets a sentinel; the sentinel stays unset through save and is set only after approval + a call).
- **C2** — kept. `RuleValidator` refuses single quotes and text blocks; `RuleValidatorTest` has the reviewer's char-literal RCE, the `Runtime.exec` variant, and the hidden `while` loop as regression cases.
- **H2** — `ReleaseStore.patchIf(release, "Canary", …)` makes the go-Live transition conditional; `RolloutJob.judge` retires the old Live release only when that patch changed a row, so a cancel between read and promote never leaves the slot empty.
- **H3** — `AppReleaseHook.onSubmit` supersedes any earlier canary of the same slot and scope, so an older canary cannot come back and retire the newer release.
- **H5** — `release-bot` lost `write` on `AppRelease` (it only creates via `release_propose`), so it cannot swap the artifact after an admin reviews it.
- **H1 (root cause + resilience)** — `RolloutController.invalidate` now marks the tenant's state stale instead of removing it, so a promote no longer drops the tenant from the cache and churns its plugins; a cached view whose plugin was unloaded (`LoadedPlugin.stopped()`) is rebuilt on next use.
- **M1** — the bulkhead permit and in-flight count are released by the plugin task's own `finally`, so a timed-out call keeps its slot while its thread runs; the drain before unload sees it as in flight.
- **M3** — `LayeredPricing` falls back to the standard tariff on any rule-layer or customer-plugin failure (not only `ScriptException`), so a live rule set that no longer compiles never blocks an order.
- **M4** — `CustomerPricingPlugin` caps each line at 1e9 đồng and sums with `Math.addExact`, so a plugin cannot overflow a long past the floor check.
- **M5** — plugin calls, start and stop run with the plugin's class loader as the thread context class loader, so `ServiceLoader`/Jackson in plugin code see only the plugin's loader.
- **M6** — `WasmSandbox` bounds its parsed-module cache (256, LRU); backtests of arbitrary modules can no longer grow it without limit.
- **M2 (stuck-Issuing half)** — `EInvoiceJob` retries orders left in `Issuing` for over 30 s, matching `CarrierBookingJob`'s stale-claim recovery.

Deferred (known limitations; recorded in the plan's risks, not blocking the demo, which runs one tenant per slot):
- **H4** — the Tier 1 bulkhead and circuit breaker are per jar-SHA, shared across tenants. A full fix (breaker/permits per tenant+release) is a larger refactor; the demo serves one tenant per slot, so cross-tenant interference does not arise there.
- **M2 (duplicate invoice/booking)** — the sandbox tax/carrier services return a fresh code per call, so a re-issue is harmless in the demo; a real integration needs an idempotency key.
- **M7** (CustomerProfileJob per-customer queries, >500 customers), **M8** (flushHealth loses a window on DB error), and the **Low** items (L1–L6) are left as noted.
