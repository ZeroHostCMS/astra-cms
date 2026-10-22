# Roadmap wave D: HRM (HR + payroll), STMS dispatch, SAML + SCIM

Status: in progress · Builds on: [platform feature demos](../261021-1723-astra-platform-feature-demos/plan.md)

## Outcome
The roadmap items of group D in Video 18 ("Còn trên roadmap") become real, runnable thin slices in the Astra
Express story, each with tests, a terminal demo scene, a Desk surface where useful, a video script and a
rendered video (19, 20, 21). Nothing is mocked; what a slice does not do is stated in its video ("Nói đúng").

## Decisions (user, 2026-10-22)
- Scope: group D only — HRM (HR + payroll; renamed from SCS on 2026-10-22), STMS (Timefold VRP), SAML + SCIM through Keycloak.
- HRM follows the Frappe HRMS domain model (Employee, Salary Component, Salary Structure, Salary Structure
  Assignment, Attendance, Additional Salary, Payroll Entry, Salary Slip). Concepts only: no code, schema,
  fixtures or UI copied from ERPNext/HRMS (GPL-3.0).
- STMS = Timefold + AI: dynamic replanning (vehicle breakdown, urgent stop, traffic, weight tuning) driven by a dispatch copilot that turns Vietnamese instructions into tool calls. The LLM is a scripted mock for the demo (user decision, labelled `llm: mock`); solver and tool execution are real.
- One video per feature, HyperFrames, same style as videos 12–17, **font Be Vietnam Pro**.
- Untracked `docs/video/{teaser,v01..v18}` stay untouched; feature work happens in isolated worktrees.

## Phases
| # | Phase | Owner | Status |
|---|-------|-------|--------|
| 1a | HRM: `astra-apps/hrm` module, HRMS DocTypes, Tier 2 Wasm KPI formula, payroll batch, Salary Slip submit posts GL via `GeneralLedger`, `scripts/demo-hrm.sh`, tests | agent | done |
| 1b | STMS: `astra-apps/stms` module, Timefold VRP (time windows + capacity), `stms_optimize_route` MCP tool, `scripts/demo-stms.sh`, tests | agent | done |
| 1c | IAM: SAML IdP brokering in the Keycloak realm, SCIM 2.0 `/scim/v2` Users/Groups per tenant mapped to tenant groups, `scripts/demo-sso.sh`, tests | agent | done |
| 2 | Merge to `feat/roadmap-business-apps`, full test suite, run all three scenes on one stack, capture output | controller | done (76/76 tests green; live run pending backup approval) |
| 2b | STMS copilot REST endpoint + Desk copilot page (kernel side) | agent | done |
| 3 | Videos 19–21 (HyperFrames, Be Vietnam Pro), scripts in `docs/demo-video-series.md`, Video 18 table + README.vi/README updates | agents + controller | in progress (scripts done; recording pending live run) |

## Shared-file contract (parallel safety)
- Flyway in `astra-doctype/.../db/migration`: HRM may use `V6__*`, STMS `V7__*`, IAM `V8__*`. Skip if not needed.
- Root `pom.xml` / `astra-server/pom.xml`: each agent adds only its own module/dependency lines.
- Keycloak realm roles: HRM adds `hr`, STMS adds `dispatcher`; IAM owns SAML/SCIM realm config.
- Each feature gets its own `scripts/demo-*.sh` and Makefile target; `scripts/demo-express.sh` is not edited.
- Docs (`README*`, `docs/demo-video-series.md`) are edited only in phase 3 by the controller.

## Acceptance
- `./mvnw -pl astra-server -am test` green with new tests per feature.
- `make demo-seed` then `make demo-hrm demo-stms demo-sso` run on the Compose stack.
- Videos 19–21 rendered to `docs/video/out`, Vietnamese text in Be Vietnam Pro.
