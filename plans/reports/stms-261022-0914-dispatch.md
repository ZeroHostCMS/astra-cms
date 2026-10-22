# STMS phase 1b report: Timefold routing, live replanning, dispatch copilot

Plan: plans/261022-0914-roadmap-business-apps/plan.md, phase 1b
Worktree: /Users/bwork/Documents/AstraCMS/astra-cms/.claude/worktrees/agent-acdc76e5f528948e5
Branch: worktree-agent-acdc76e5f528948e5
Commits: 5d2d5c5 feat(stms), a46e256 chore(demo), then this report

## What was built

The new Tier 0 module `astra-apps/stms` (artifact `astra-app-stms`) uses only astra-spi plus Timefold Solver.

- **Timefold:** 2.7.1, the latest stable release. It is built against Quarkus 3.39.4 and runs on JDK 21+. The spike confirmed that `timefold-solver-quarkus` works with Quarkus 3.39.5 on JDK 25 without conflicts, so the extension is used and no fallback was needed. The version is pinned in astra-bom (`timefold.version`, which imports `timefold-solver-bom`), and the stms pom imports astra-bom.
- **DocTypes:**
  - Hub: code, name, lat/lon, opening hours. It was added because a depot needs coordinates.
  - Vehicle: plate, depot hub, capacity kg, active.
  - DeliveryStop: hub, date, Delivery/Pickup, Normal/VIP/Urgent priority, an optional Waybill link, lat/lon, demand kg, a time window, and service minutes.
  - DispatchPlan: submittable, with date, hub, status, score, distance, solve_ms, `routes` child rows (vehicle, sequence, stop, eta, start, window, demand, load, leg km, late min), a text `route_summary`, and `last_change`.
  - DispatchEvent: the log of live changes.
- **VRP model:** a list variable per vehicle, with shadow vehicle, previous visit and arrival time (declarative `@ShadowVariable` with `@ShadowSources`).
  - Hard constraints: capacity (1 point per kg over) and time window (1 point per minute late).
  - Soft constraints, tunable per plan with `ConstraintWeightOverrides`: distance per metre, priority stops early (per minute after departure), and load balance (kg² per vehicle).
  - Distance is haversine × road factor 1.3. Travel time uses 25 km/h, scaled by any traffic areas. There is no map API.
- **Solve action:** saving a draft plan with `solve: true` makes `DispatchPlanHook` load the hub's active vehicles (minus `unavailable_vehicles`) and the day's stops, then solve.
  - Solving runs on a virtual thread with a time budget: `solve_seconds`, default `astra.stms.solve-seconds=5`, capped at 30.
  - It stops early after the score has not improved for max(0.5 s, budget/5).
  - Submit is allowed only for a Solved, feasible plan whose `solved_for` still matches its hub and date. Submit sets the status to Dispatched, and the kernel then makes the plan immutable.
- **Dynamic replanning:** each live event patches the plan and re-solves it, warm-started from the current routes. Stops of a removed vehicle and new stops are unassigned; the construction heuristic inserts them and local search repairs the rest. Each event writes a DispatchEvent with the score before and after, the stops that moved, and the solve time. The events are: vehicle unavailable, urgent stop, traffic delay area, time-window change, and weights.
- **MCP tools** (McpTool SPI, using the caller's tenant and permissions):
  - `stms_optimize_route`: re-solve a plan, show a frozen one, or propose routes for a hub and date without saving.
  - `stms_vehicle_unavailable`, `stms_add_urgent_stop`, `stms_traffic_delay`, `stms_change_time_window`, `stms_set_weights`, `stms_explain_plan`, and `stms_copilot`.
  - The replanning tools declare `DispatchEvent` in `reads()`. Only admin and dispatcher can read it, so the MCP server's read check limits replanning to those roles.
- **Copilot:** `DispatchCopilot` uses the `LlmClient` interface, which returns `ToolCall`s shaped like Claude `tool_use` blocks.
  - The only implementation is `MockLlmClient` (`astra.stms.llm`, default `mock`). It maps each clause of the instruction with Vietnamese patterns and uses a small built-in gazetteer for places.
  - The copilot runs the returned calls against the real tools and writes the Vietnamese explanation from the real results: score delta, moved stops, VIP stops served by the deadline, distance and solve time.
  - The mock is flagged in a WARN log line, in `"llm": "mock"`, in `llm_note`, and with the prefix `[LLM giả lập – kịch bản demo]`.
- **Role and data:** the `dispatcher` group was added to saigon-express and hanoi-express, with demo user `linh` (password Astra@123) in both. `demo-seed.sh` creates one fictional hub per tenant (HCM-TB Kho Tân Bình, HN-LB Kho Long Biên), 3 vehicles and 20 stops each.
- **Desk:** the generated Desk lists and edits all five DocTypes. A plan shows `route_summary` as a fixed-width route table, and `routes` and `last_change` as JSON. desk.js was not changed, so there is no copilot input box (it is outside my file ownership).
- **Migration:** none needed, so there is no V7.

## Files

- New:
  - astra-apps/stms/pom.xml and META-INF/beans.xml
  - doctypes/{hub,vehicle,delivery-stop,dispatch-plan,dispatch-event}.doctype.yaml
  - io/astra/apps/stms/{StmsApp, Values, DispatchProblems, DispatchPlanHook, MasterDataHooks, Replanner, ReplanTools, ExplainPlanTool}.java
  - solver/{Location, PlanVehicle, PlanVisit, RoutePlan, RoutingConstraints, RoutingProblem, PlanResult, PlanDiff, RoutePlanner}.java
  - copilot/{ToolCall, LlmClient, MockLlmClient, DispatchCopilot, CopilotTool}.java
- New tests:
  - astra-apps/stms/src/test/.../solver/RoutePlannerTest.java and copilot/MockLlmClientTest.java
  - astra-server/src/test/.../StmsFixtures.java, StmsDispatchTest.java, StmsMcpTest.java
- New scripts: scripts/demo-stms.sh
- Modified:
  - pom.xml (module line)
  - astra-bom/pom.xml (Timefold version, BOM import, stms artifact)
  - astra-server/pom.xml (dependency)
  - deploy/keycloak/realm/astra-realm.json (dispatcher groups, user linh)
  - scripts/demo-seed.sh (STMS seed appended)
  - Makefile (`demo-stms`)

## Test results

`./mvnw -pl astra-server -am test -Dquarkus.http.test-port=0` reports BUILD SUCCESS. The 54 astra-server tests include 10 STMS tests, and the 11 stms module tests are run by `-am`.

- RoutePlannerTest (plain Timefold, no Quarkus):
  - feasible plan respecting capacity and windows
  - overload reported through the hard score (≤ −352hard)
  - breakdown, urgent stop, traffic and time-window replans all stay at 0hard
  - priority weight brings VIP stops forward
  - balance weight narrows the load spread
- MockLlmClientTest: the demo prompts map to the expected tool calls, and an off-script prompt yields none.
- StmsDispatchTest:
  - REST solve gives 20 rows within capacity and windows, and solve_ms stays within budget
  - submit gives status Dispatched, and a later PUT returns 422 "immutable"
  - an overloaded fleet is Infeasible and its submit returns 422
  - ops can read a plan but gets 403 when it tries to solve
  - malformed stops and settings return 422
- StmsMcpTest:
  - every live event is replanned feasibly and logged
  - the copilot returns `llm=mock`, the expected tool_use calls, and a real 0hard result
  - the demo storyline stays feasible at every step with the 5 s budget
  - tenant isolation: another tenant's plan or hub is "not found"
  - ops, and care, get "not permitted" from the replanning tools and nothing changes

Note: the first full run failed only because the fixed Quarkus test port 8081 was briefly taken by a parallel agent's test run. With `-Dquarkus.http.test-port=0` the suite is green. The controller may want that flag (or `%test.quarkus.http.test-port: 0`) for parallel runs.

## Measured solve times

These come from the Quarkus test log: Apple Silicon, JDK 25, Timefold Community 2.7.1, PHASE_ASSERT, about 200k–250k moves/s.

| Step | Stops / vehicles | Budget | Score | km | Solve |
|---|---|---|---|---|---|
| Baseline (cold) | 20 / 3 (2 used) | 2–5 s | 0hard/-106703soft | 98.8 | 0.98–1.46 s |
| Overloaded 100 kg vehicle | 20 / 1 | 2 s | -352hard/-111261soft | 95.7 | 0.77 s |
| Copilot: 51C-12345 breaks down (warm) | 20 / 2 | 5 s | 0hard/-105727soft | 98.6 | 0.50–1.00 s |
| Copilot: VIP priority 500 + balance 10 | 20 / 2 | 5 s | 0hard/-1255617soft | 111.6 | 1.73 s |
| Copilot: urgent 30 kg at Bến Thành by 11:00 | 21 / 2 | 5 s | 0hard/-1432716soft | 112.1 | 1.00 s |
| Traffic Thủ Đức ×2.5, r 4 km | 21 / 2 | 5 s | 0hard/-1454869soft | 117.5 | 1.91 s |
| Window change Shop 15 → 08:00–09:00 | 21 / 2 | 5 s | 0hard/-1479600soft | 137.0 | 1.61 s |

The soft score jumps after the weights step because the new weights change its scale. Compare soft scores only between plans solved with the same weights.

## Demo scenes (scripts/demo-stms.sh, make demo-stms)

The demo signs in as `linh` (dispatcher) in tenant saigon-express, with STMS_DATE defaulting to today in Vietnam. It was **not run against the Compose stack**, because I was told not to start Compose. Its syntax is checked (`bash -n`). The expected output below comes from the identical storyline in `StmsMcpTest.theDemoStorylineStaysFeasibleAtEveryStep`, which uses the same fleet and demand profile as the seed. Live numbers will differ slightly because the seeded coordinates and labels differ from the test fixture.

1. **Master data:** the 5 STMS DocTypes, hub HCM-TB, 3 vehicles (300/250/250 kg), and 20 stops with windows and priorities.
2. **Baseline:** POST DispatchPlan `{solve:true, solve_seconds:5}` returns Solved, feasible, about `0hard/-106703soft`, about 99 km, 2 vehicles used, solve_ms about 1000. The route table is printed.
3. **Copilot, "Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác":**
   - The response has `llm: "mock"` and `llm_note`.
   - tool_calls: `stms_vehicle_unavailable`, then `stms_explain_plan`.
   - The explanation reads "[LLM giả lập – kịch bản demo] Xe 51C-12345 ngừng chạy: 10 điểm chuyển sang xe khác (51C-67890: 9, 51D-24680: 1) … Điểm số 0hard/-106703soft → 0hard/-105727soft (khả thi) …".
   - The moved stops are listed, followed by the new route table.
4. **Copilot, "Ưu tiên khách VIP trước 10h, cân bằng tải giữa các xe":**
   - tool_calls: `stms_set_weights` {priority 500, balance 10}, then `stms_explain_plan`.
   - Result: "19 điểm đổi xe … 3/3 điểm VIP/gấp được phục vụ trước 10:00", 98.6 → 111.6 km.
   - The scene then runs explain_plan, which shows the weights, the VIP stops and the per-vehicle loads.
5. **Copilot, "Thêm đơn gấp 30kg ở Bến Thành trước 11h":**
   - tool_calls: `stms_add_urgent_stop` (gazetteer 10.7725, 106.698; window 08:00–11:00), then `stms_explain_plan`.
   - Result: "Đã chèn đơn gấp DS-… vào xe 51D-24680, phục vụ lúc 08:00 … 4/4 điểm VIP/gấp …".
6. **MCP tools without the copilot:**
   - `stms_traffic_delay` (Thủ Đức ×2.5) and `stms_change_time_window` (Linh kiện Tân Tiến → 08:00–09:00).
   - Each prints the score before and after, km, solve_ms and the moved stops, and stays at 0hard.
7. **Permissions:**
   - quang (ops) calling `stms_vehicle_unavailable` gets `isError`, "not permitted: read DispatchEvent".
   - ha (care) calling `stms_explain_plan` gets "not permitted".
   - The same plan name in hanoi-express returns "not found" (or a different document).
8. **Dispatch:**
   - The DispatchEvent log lists each event with its source (Copilot or MCP), score before → after, moved_stops and solve_ms.
   - Submit returns docstatus 1, status Dispatched.
   - PUT `{solve:true}` returns 422 "submitted documents are immutable".
   - `stms_set_weights` returns the error "is dispatched and frozen; amend it to replan".

## Nói đúng (what this slice does NOT do)

- The copilot's LLM responses are scripted mocks for the demo: regex rules plus a small built-in place list. No language model is called. The solver, the replanning and the tool execution are real. A real provider would plug into `LlmClient`; none is implemented.
- There is no live traffic, map routing or real road network. Distance is straight-line × 1.3, speed is a flat 25 km/h, and "traffic" is a dispatcher-declared slowdown area.
- There is no geocoding: stops carry lat/lon, and the copilot's place names come from a fixed gazetteer.
- There is no AI load or demand forecasting (Tier 1), and no driver app, GPS tracking or ETA updates from the field.
- Replanning re-solves warm-started from the current routes. It does not keep a solver running for the whole day with Timefold `ProblemChange`, and time does not advance: it does not freeze stops already served or vehicles already en route.
- Capacity is a single conservative total per vehicle (deliveries and pickups summed). There is no pickup-and-delivery precedence, no multi-trip, no driver shifts or breaks, no return-to-depot deadline, and no vehicle types or costs.
- Only one hub per plan is supported, with no cross-hub transfers.
- The demo runs about 20 stops and solves in about 1–2 s. Larger instances have not been benchmarked.
- No route map is shown. The Desk shows a text route table and JSON.

## Concerns / for the controller at merge

1. **The `dispatcher` role must be added to `astra.iam.tenant-roles`** in astra-server/src/main/resources/application.yml (not in my file ownership), so the IAM membership API can grant it. Tokens and permissions already work without it, through the realm groups.
2. **There is no `POST /api/stms/copilot` REST endpoint.** An app module cannot authenticate a caller or bind tenant and roles through the SPI without importing astra-kernel. The copilot is therefore exposed as the MCP tool `stms_copilot` on the existing authenticated `/mcp` path, which is what the demo uses. A REST facade would belong in the kernel or astra-ai.
3. **There is no copilot input or route diff view in the Desk:** desk.js is outside my file ownership. The plan form already shows `route_summary` (text table) and `last_change` (moved stops).
4. **App MCP tools that write** (the replanning tools) do not get the `X-Tx-LSN` header, because McpServer only sets it for `create_document`. The writes still commit.
5. **Event owner:** DispatchEvent and urgent DeliveryStop documents are owned by `stms-mcp` / `stms-copilot`, because the SPI does not give app tools the caller's identity. McpServer logs the real user for every call.
6. **Non-atomic urgent stop:** the solve runs inside the plan's save transaction, so it holds one DB connection for up to the budget (5 s by default, 30 s at most). Inserting an urgent stop and then replanning are two transactions, so a failed replan leaves the new stop saved.
7. **Coordinator note received:** the SCS agent adds `submit` and `cancel` to DocumentService. STMS does not need them.
8. **Report location:** this report was written inside the worktree. The agent is not allowed to write to the shared checkout path. It is committed on the branch at plans/reports/stms-261022-0914-dispatch.md.

Status: DONE_WITH_CONCERNS

Summary: STMS is a real Timefold 2.7.1 VRP (capacity and time windows hard; distance, priority and balance soft with runtime weights), with warm-start live replanning, eight MCP tools and a mock-LLM copilot. The full astra-server test suite is green, including 21 new STMS tests. The demo script and seed are ready but were not run on a live stack.

Concerns: `dispatcher` must be added to `astra.iam.tenant-roles` in application.yml at merge; the copilot is MCP-only (no REST endpoint, no Desk input); the demo was not run on Compose; parallel test runs need a random test port.

Worktree: /Users/bwork/Documents/AstraCMS/astra-cms/.claude/worktrees/agent-acdc76e5f528948e5 · Branch: worktree-agent-acdc76e5f528948e5
