# Astra CMS deep dives

One article per demo video, written for developers and technical leaders. Each article explains the problem, what the
video proves, how the mechanism works with code quoted from this repository, a real-world example, the trade-offs a
technical leader should weigh, how to run it yourself, what is not built yet, and diagrams that explain the idea.

The videos are listed in the [README](../../README.md#demo-videos); the scene-by-scene scripts are in
[demo-video-series.en.md](../demo-video-series.en.md). Bản tiếng Việt: [README.vi.md](./README.vi.md).

## Figures

Every article embeds its figures next to the section they explain, and collects them in a *Figures* section at the end,
where the sketch prompts used to live.
They share one style: clean vector line art on off-white paper (`#fbfbfa`), one accent color (`#6b8cff`) for the key
path, simple boxes and arrows, short labels in English, 16:9 at 960×540.

The files live in [`figures/`](./figures) as `video-NN-figure-M.png`, where `NN` is the video number; the generated
catalogue with titles and placement is in [`FIGURES_MANIFEST.md`](./FIGURES_MANIFEST.md). English and Vietnamese
articles embed the same image set, so labels are in English.

Check every figure against the article before publishing: generated diagrams misspell labels, clip text or invent arrows.

## Start here

| Video | Article | What you learn |
| --- | --- | --- |
| [▶ 1](https://media.zerohostcms.com/astra-cms/astra-video-1-quickstart-en.mp4) <sub>2:32</sub> | [What is Astra &amp; running it locally](./01-quickstart.md) | Kernel + DocType engine + plugin host, then the whole k3d cluster with <code>make k3d-up</code>, data seeded through the REST API, and a Desk login. |
| [▶ 2](https://media.zerohostcms.com/astra-cms/astra-video-2-doctype-en.mp4) <sub>2:38</sub> | [DocType: from one YAML file to API, UI and indexes](./02-doctype.md) | One YAML file generates the form, REST API, validation and an online expression index; every DocType shares one JSONB table, no ALTER TABLE. |
| [▶ 3](https://media.zerohostcms.com/astra-cms/astra-video-3-hooks-en.mp4) <sub>2:53</sub> | [Business rules: hooks &amp; validation](./03-hooks.md) | A 73,000 ₫ shipping fee computed by a hook, all validation errors returned at once, Links checked inside the tenant; the Desk and the API hit the same rules. |

## Business documents and processes

| Video | Article | What you learn |
| --- | --- | --- |
| [▶ 4](https://media.zerohostcms.com/astra-cms/astra-video-4-lifecycle-en.mp4) <sub>3:10</sub> | [Document lifecycle: Submit, Cancel, Amend](./04-lifecycle.md) | Submit creates the waybill and the journal entry in one transaction; submitted documents are immutable; cancel posts a reversing entry; amend creates a new revision. |
| [▶ 5](https://media.zerohostcms.com/astra-cms/astra-video-5-ops-en.mp4) <sub>3:00</sub> | [Operations: the parcel state machine](./05-ops.md) | Filtering on a projected field, the scan timeline, no skipped states, an error then a success on the same form, and no cancelling a delivered order. |
| [▶ 6](https://media.zerohostcms.com/astra-cms/astra-video-6-care-en.mp4) <sub>2:30</sub> | [Customer care sees operations](./06-care.md) | Tickets jump to High with a +24h SLA based on keywords; the CARE replica card reads a copy kept by the CDC worker. |

## Platform, tenancy and operations

| Video | Article | What you learn |
| --- | --- | --- |
| [▶ 7](https://media.zerohostcms.com/astra-cms/astra-video-7-tenants-en.mp4) <sub>2:28</sub> | [Multi-tenancy](./07-tenants.md) | Two companies on one server: same URL, different data; Links never cross tenants, 403/400 when the tenant is unclear, RLS returns 0 rows. |
| [▶ 8](https://media.zerohostcms.com/astra-cms/astra-video-8-failclosed-en.mp4) <sub>2:02</sub> | [Fail-closed security](./08-failclosed.md) | Connect as a superuser and the kernel refuses to start, before Flyway runs any migration; the application role is NOSUPERUSER NOBYPASSRLS. |
| [▶ 10](https://media.zerohostcms.com/astra-cms/astra-video-10-k8s-en.mp4) <sub>2:06</sub> | [Running on Kubernetes](./10-k8s.md) | Two kernel pods write in parallel with no duplicate and no gap in the numbering; a rolling restart under load, and what is still missing (no drain yet). |

## Building on Astra

| Video | Article | What you learn |
| --- | --- | --- |
| [▶ 9](https://media.zerohostcms.com/astra-cms/astra-video-9-developer-en.mp4) <sub>2:22</sub> | [For developers: writing an Astra app](./09-developer.md) | An app is plain Java + YAML on astra-spi; dev-mode live reload adds a new column in 0.73 s; 44 integration tests against real PostgreSQL, Seata and Redis. |
| [▶ 13](https://media.zerohostcms.com/astra-cms/astra-video-13-overlay-en.mp4) <sub>2:24</sub> | [Tenant overlays](./13-overlay.md) | Hanoi adds VAT fields and an island zone with one YAML file, live immediately; the Express app does not change a line. |
| [▶ 14](https://media.zerohostcms.com/astra-cms/astra-video-14-wasm-en.mp4) <sub>2:20</sub> | [Tier 2 Wasm: each tenant's own shipping formula](./14-wasm.md) | A Tết promotion written in Rust (421 bytes) switches the fee from 73,000 to 51,000; an infinite loop is stopped at 50 ms. |

## Identity, permissions and money

| Video | Article | What you learn |
| --- | --- | --- |
| [▶ 11](https://media.zerohostcms.com/astra-cms/astra-video-11-keycloak-en.mp4) <sub>2:18</sub> | [Keycloak: one identity, roles per tenant, permissions per DocType](./11-keycloak.md) | PKCE login with the Astra theme, roles as Keycloak groups, permissions declared on the DocType; the maker of a payment voucher cannot approve it (403). |
| [▶ 21](https://media.zerohostcms.com/astra-cms/astra-video-21-sso-en.mp4) <sub>2:32</sub> | [SAML SSO and SCIM](./21-sso.md) | Login through a signed SAML IdP, IdP groups become tenant roles; SCIM 2.0 creates, regroups, deactivates and deletes users per tenant, and another tenant's token is blocked. |
| [▶ 12](https://media.zerohostcms.com/astra-cms/astra-video-12-ledger-en.mp4) <sub>2:32</sub> | [Append-only ledger](./12-ledger.md) | Double-entry postings ride the document's transaction, cancel reverses, a trigger blocks UPDATE even for the table owner, monthly partitions. |

## Integration: AI agents, CDC, distributed transactions

| Video | Article | What you learn |
| --- | --- | --- |
| [▶ 15](https://media.zerohostcms.com/astra-cms/astra-video-15-mcp-en.mp4) <sub>2:24</sub> | [MCP: AI agents use Astra with the user's permissions](./15-mcp.md) | Real Claude calls tools over MCP: PII is masked, the customer care agent cannot create orders, and the accountant's COD question is answered from the ledger. |
| [▶ 16](https://media.zerohostcms.com/astra-cms/astra-video-16-cdc-en.mp4) <sub>2:36</sub> | [Rust CDC + Sync-Aware LSN](./16-cdc.md) | A Rust worker reads the WAL, writes return X-Tx-LSN, and a read from a replica that has not caught up gets 202 Sync Pending instead of stale data. |
| [▶ 17](https://media.zerohostcms.com/astra-cms/astra-video-17-seata-en.mp4) <sub>2:26</sub> | [Seata: one submit, three resources](./17-seata.md) | The AT branch charges the BMS credit limit, the TCC branch holds a slot in Redis, the kernel commits last; one branch refusing rolls everything back. |

## Business apps

| Video | Article | What you learn |
| --- | --- | --- |
| [▶ 19](https://media.zerohostcms.com/astra-cms/astra-video-19-hrm-en.mp4) <sub>2:40</sub> | [HRM: attendance and payroll](./19-hrm.md) | Following Frappe HRMS concepts: attendance sets working days, Payroll Entry computes salary slips in parallel, a courier bonus in Wasm, one accrual journal entry for the period, cancel reverses; the AI agent only sees masked names. |
| [▶ 20](https://media.zerohostcms.com/astra-cms/astra-video-20-stms-en.mp4) <sub>2:58</sub> | [STMS: vehicle dispatch with Timefold + AI copilot](./20-stms.md) | Timefold plans routes by capacity and time window and re-plans on a breakdown, an urgent order or a traffic jam; the copilot takes Vietnamese commands and calls real tools. The copilot's LLM is simulated from the demo script, labelled on every frame. |

## What comes next

| Video | Article | What you learn |
| --- | --- | --- |
| [▶ 18](https://media.zerohostcms.com/astra-cms/astra-video-18-roadmap-en.mp4) <sub>1:36</sub> | [the rest of use-case-01](./18-roadmap.md) | What videos 1 to 17 and 19 to 21 already run for real (including HRM, STMS, SAML and SCIM) and what is still in development: multi-schema, Tier 1, streaming CDC, autonomous agents, audit hash-chain, field encryption. |
