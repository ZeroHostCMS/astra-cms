# Astra Express demo (use-case-01, marketing video series)

Status: done (tests green; seed + scenes 1-8 verified on k3d and dev mode; Desk checked in Chrome)

## Outcome
A runnable, honest demo of everything Astra really does today, told through the courier story
of `docs/use-case-01.md` (OMS → OPS → CARE), plus a video-series script for marketing.

## Scope
- Kernel (real, small): submittable lifecycle (submit / cancel / amend) with hooks inside one
  transaction; Link validation inside the tenant; read-only field enforcement; list filters on
  declared fields; online expression indexes for `projected: true`; date tokens in naming series.
- App `astra-apps/express`: Customer, CustomerOrder, Waybill, SupportTicket + Tier 0 hooks
  (pricing, waybill creation, scan state machine, SLA/triage).
- Desk UI generated from DocType metadata (`/desk/`) + two-tenant compare view.
- Scene-by-scene terminal script + seed script; `docs/demo-video-series.md`.

## Non-goals
Everything else in use-case-01 (CDC, LSN, Seata, ledger storage, Wasm, Keycloak, Doris, MCP,
overlays, PII encryption). These appear only in a clearly labelled "Roadmap" video.

## Acceptance
- `./mvnw -pl astra-server -am test` green, including new lifecycle/link/filter tests.
- Seed + every scene runs against a live server; Desk renders and drives all DocTypes.
