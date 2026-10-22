# STMS dispatch copilot: REST endpoint, Desk screen, X-Tx-LSN for app tools that write

Plan: plans/261022-0914-roadmap-business-apps/plan.md (STMS = Timefold + AI). The LLM is still the agreed scripted `MockLlmClient`.
Worktree: /Users/bwork/Documents/AstraCMS/astra-cms/.claude/worktrees/agent-a3097ebfb7e621168
Branch: worktree-agent-a3097ebfb7e621168, based on feat/roadmap-business-apps @ edd98be ("Merge SAML SSO and SCIM provisioning branch")
Commits:
- 654514d feat(stms): REST copilot endpoint and X-Tx-LSN after app tools that write
- 7cd5243 feat(desk): dispatch copilot screen for STMS plans
- 22061ff chore(demo): STMS copilot scenes call the REST endpoint, one stays on MCP
- this report

## What was built

### Shared pipeline (no duplicated logic)

The new `astra-ai/.../AppToolInvoker` is the one place where an app's `McpTool` runs for the authenticated caller. It does the following:
- binds the caller's tenant (`TenantRequest` into `TenantContext`);
- requires read permission on every DocType in `reads()`;
- writes the audit log line ("`<channel>` tool X by user in tenant T");
- masks PII in the result;
- reports whether the tool writes.

`McpServer` now sends every app tool through it. The kernel tools (list/search/get/create_document, trial balance) keep their own switch.

### X-Tx-LSN after any write

- The `McpTool` SPI gains `default boolean writes() { return false; }`.
- These tools return true: the six STMS replanning tools (optimize_route, vehicle_unavailable, add_urgent_stop, traffic_delay, change_time_window, set_weights) and `stms_copilot`.
- `stms_explain_plan` stays read-only.
- `McpServer` sends `X-Tx-LSN` after a successful call of `create_document` or of any app tool that writes.
- Failed calls (`isError`) and reads get no header.

This resolves concern 4 of the earlier STMS report.

### REST endpoints

Both endpoints are in `astra-server/.../StmsCopilotResource`. The server module already composes the apps; astra-ai stays app-agnostic.

**`POST /api/stms/copilot`**, body `{plan, instruction}`:
- It uses the same bearer auth and tenant filter as the rest of `/api/*`.
- It checks the tool's `reads()` first. Ops gets 403 "not permitted: read DispatchEvent" before the plan's existence is revealed.
- A plan that is not visible in the caller's tenant returns 404.
- It then runs `stms_copilot` through `AppToolInvoker` (audit line channel `REST`) and sends `X-Tx-LSN`.
- A missing or blank field returns 422. The kernel mapper also handles the copilot's own 2000-character limit.

**`GET /api/stms/plans/{name}/events`** returns the plan's DispatchEvent log, newest first, with PII masked. It needs read on DispatchPlan and DispatchEvent (403 otherwise), and returns 404 for a plan in another tenant or a missing plan.

### Copilot response

The response keeps `llm: "mock"`, `llm_note`, `tool_calls` (Claude `tool_use` blocks), per-call `results`, `score_before` and `score_after`, `feasible`, the distances and the Vietnamese `explanation`.

`DispatchCopilot` now also adds the net effect of all the calls. This applies to both REST and MCP:
- `moved_stops`: stop, label, from, to, eta and start, computed with the existing `PlanDiff`;
- `resequenced`;
- `routes_before` and `routes_after`.

### Desk: "Điều phối (Copilot)"

The new file `desk/dispatch.js` is loaded before desk.js and routed at `#/dispatch?plan=…`. The sidebar link sits under the new "Vận tải · STMS" group and appears only for roles that can read DispatchPlan.

The screen has:
- **Plan picker**, labelled. It defaults to the newest draft.
- **Plan summary:** hub, date, status, score, km and vehicles used.
- **Route table per vehicle:** #, stop, priority badge (VIP, or Gấp for Urgent), ETA, start, window, demand, load and leg km. Late rows are flagged.
- **Copilot form:**
  - a labelled textarea, with Ctrl/⌘+Enter to send;
  - three suggestion chips (buttons) built from the plan: the first used vehicle breaks down, "Ưu tiên khách VIP trước 10h, cân bằng tải giữa các xe", and an urgent 30 kg order at Bến Thành, or Hoàn Kiếm for a Hanoi hub;
  - a busy state and an `aria-live` status;
  - error banners for 403, 404 and 422;
  - the form is disabled, with an explanation, for read-only roles and frozen plans.
- **Result** (focus moves to its heading):
  - the "LLM giả lập (kịch bản demo)" badge whenever `llm=mock`, also shown on the copilot card;
  - the mock note and the explanation;
  - score and km before → after, and the X-Tx-LSN;
  - a timeline of the tool calls: label, tool name, inputs, ok or error, and per-call score, moved stops and solve time;
  - a side-by-side before/after diff. Moved stops are highlighted on both sides and labelled in text ("→ xe", "từ xe", "mới"), so colour is not the only cue.
- **Event log** (Nhật ký điều phối), from the new events endpoint.

The styles in desk.css reuse the existing variables, work in dark and light mode, and add `:focus-visible` outlines.

### Demo script

`scripts/demo-stms.sh` changes by scene:
- Scenes 3 and 4 call `POST /api/stms/copilot` through a new `copilot` helper. They print the net moved stops and the `X-Tx-LSN`.
- Scene 5 stays on MCP (`tools/call stms_copilot`) and also prints the LSN.
- Scene 6 still uses the MCP replanning tools directly.
- Scene 7 adds quang (ops) receiving a REST 403 from the copilot.
- Scene 8 reads the log from `/api/stms/plans/{plan}/events`.

`bash -n` passes under both bash 3.2 and bash 5. The new jq filter was checked against a real copilot response.

## Tests

New file: `astra-server/src/test/java/io/astra/server/StmsCopilotRestTest.java`, 4 tests.
1. **Dispatcher happy path:**
   - The response is 200 with `X-Tx-LSN`, `llm=mock`, `llm_note`, tool calls [vehicle_unavailable, set_weights, explain_plan], all results ok, feasible, score `0hard/…` and an explanation starting "[LLM giả lập".
   - `moved_stops` is not empty and none of the stops goes to 51C-12345.
   - `routes_after` has no 51C-12345, 20 rows, and no late rows.
   - The events endpoint lists Copilot, Vehicle unavailable and Weights, with source Copilot and no LSN.
   - An off-script instruction gives no tool calls, and a missing instruction gives 422.
2. **Ops:** the copilot returns 403 "not permitted: read DispatchEvent" with no LSN, the events endpoint returns 403, and the plan is unchanged.
3. **Cross-tenant:** a member of two tenants posting a plan of tenant T under tenant U gets 404 from both endpoints, with no LSN. The plan in T is untouched.
4. **MCP:** `stms_vehicle_unavailable` returns `X-Tx-LSN`. `stms_explain_plan` and a failed replan (unknown vehicle, `isError`) return none.

Results:
- **Focused run** (StmsCopilotRestTest, McpTest, StmsMcpTest): 15/15 green.
- **Full run** (`./mvnw -pl astra-server -am test -Dquarkus.http.test-port=0`): 71 tests, 70 green.
  - All of these pass: STMS (StmsDispatchTest, StmsMcpTest, StmsCopilotRestTest), McpTest, SyncTest, the express, HRM and ledger tests, and the module tests.
  - **The one error, `SamlBrokerTest`, was already on the base branch.** Its Keycloak Testcontainer exits with code 1, so it is not Ryuk. It failed again on a rerun.
  - Running the same image by hand shows the cause: `Duplicate resource error … USER_ENTITY(REALM_ID, USERNAME) … 'lan'`. `deploy/keycloak/realm/astra-realm.json` defines user `lan` twice. Line 440 is lan@acme.test from the SAML branch; line 506 is lan@express.test from the HRM branch. The merge in edd98be kept both.
  - My branch does not touch `deploy/`. That also explains why ScimTest shows 0 tests.

The Desk screen was checked in headless Chrome with a harness that stubs `api()` using real JSON captured from the test run. I checked three states: initial, after a copilot call (timeline, diff, event log, mock badge) and the ops read-only view. It was not run against a live stack, because Compose and k3d are off-limits.

## Limits

- The LLM is still the scripted mock (the user's decision). The badge, `llm_note` and the explanation prefix say so.
- `writes()` is declared per tool, so it means "may write". A copilot call whose instruction matched no script still returns `X-Tx-LSN`: the position is already committed, so it is harmless, just not strictly needed. `stms_optimize_route` in proposal mode (hub and date) is the same.
- Before the first call, the Desk cannot know which LLM is configured. The help text says the assistant is a mock, and the badge appears as soon as a response says `llm=mock`.
- The demo script was syntax-checked and its jq filter tested on real output, but it was not run against Compose.

Status: DONE_WITH_CONCERNS
