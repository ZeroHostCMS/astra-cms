# Astra: why a Frappe/ERPNext-style metadata-driven platform, rewritten in Java, that changes continuously without downtime and is AI-native

**English** · [Tiếng Việt](explain.md) · Back to the [README](../README.md)

> A document for a public talk (meetup, conference talk, article), delivered as one continuous 40-minute talk. Each section has a **key point**, **reference platforms**, **rationale**, and **trade-offs**. Astra's performance figures are **design targets**, not measurements; other platforms' figures are their own claims.

---

## 0. Opening: what ERPNext proved

**Key point:** ERPNext proved something the enterprise software industry had mostly given up on: **one open platform can run a whole company's content, commerce, accounting, inventory, manufacturing, HR and projects**, and small teams can customise it without forking it.

The reason is not any single module but Frappe's core idea: **the application is metadata**. A DocType defines a document type's fields, validation, permissions, form, list, API and workflow as data; everything else (REST, UI, reports, print, notifications) is generated from it.

Astra takes that idea and rebuilds it for four constraints Frappe was never designed for:

1. **Throughput and latency at cluster scale.** Java 25 virtual threads, Generational ZGC, Quarkus build-time wiring, in-process plugin isolation. Thousands of tenants and their customisations on one pod, instead of one process per site.
2. **Change every day without breaking yesterday.** Zero-DDL DocTypes (customising a form never locks a table), side-by-side app versions, per-tenant pinning, canary rollout with auto-rollback, an SPI with a deprecation policy, and tenant customisations stored as overlays that survive every upgrade.
3. **AI both builds apps and uses them.** Agents build apps against a small typed SPI with a sandbox and a test kit; agents use the platform through MCP tools generated from the same DocTypes, under the same permissions and audit.
4. **Compliance as a property of the metadata.** Every DocType field carries classification, retention, legal basis and residency. Records of processing, data-subject requests, retention schedules, audit trails and access reviews are all generated from that metadata. An operator can reach SOC 2 Type II, ISO 27001, GDPR and Vietnam's Personal Data Protection Law (Luật Bảo vệ dữ liệu cá nhân) without reworking the data model.

> **Say two things up front:** (1) Astra is in the design phase; there is a **running thin slice** (CMS DocTypes, tenancy via ScopedValue + row-level security, generated REST, a Tier 0 hook, running on k3d with two pods), and the rest is blueprint. (2) **Clean-room:** Frappe is MIT, ERPNext is GPL-3.0, Astra is MIT. Astra uses no code, schema or assets from either; it borrows only concepts and vocabulary ("DocType"). Say this first so nobody mistakes it for a port.

**One-sentence summary:** *Astra is a Frappe-style metadata-driven application platform, rewritten from scratch in Java 25, where everything is a plugin; the first app is a CMS, the second is an ERP; it changes every day with no downtime, and every DocType is also a tool for AI agents.*

---

## 1. Who we learn from

| Platform | What they do | What Astra borrows |
| --- | --- | --- |
| **Frappe / ERPNext** | DocType metadata drives schema, form, API, permissions, workflow. Apps are installable units with hooks. Custom Fields and Property Setters let you customise core DocTypes without forking, and survive upgrades. Submittable documents (draft → submitted → cancelled; amend creates a new revision) give accounting-grade immutability. Bench runs many sites. | The DocType model, app-as-plugin, customisation-as-overlay, the submittable lifecycle, and the "everything generated from metadata" discipline. Rebuilt in Java with zero-DDL storage, cluster rollout, and tiers of isolation Frappe does not have. |
| **Linux kernel** | Never break userspace. | `astra-spi` is the userspace ABI. |
| **Kubernetes** | Alpha → beta → GA APIs; deprecated GA APIs keep working 12 months or 3 releases; rolling updates under PDBs. | SPI maturity levels, the same deprecation window, kernel rolling upgrades that drain transactions. |
| **Stripe** | Dated API versions pinned per account. | App and API versions pinned per tenant. |
| **Erlang / OTP** | Old and new code coexist; running calls finish on old code. | Side-by-side plugin versions, drain before release. |
| **Shopify Functions** | Merchant logic as Wasm with deterministic instruction limits. | Tier 2 Wasm with budgets and per-invocation metrics. |
| **Cloudflare Workers** | In-process isolates instead of containers; claims ~100x faster start. | In-process isolation instead of a microservice per customisation. |
| **Envoy / Proxy-Wasm** | Control plane distributes Wasm filters to every proxy. | Plugin control plane delivering the same version to every pod. |
| **VS Code** | Separate extension host, stable typed API, tens of thousands of extensions. | Strongly typed extension points + isolation. |
| **Google SRE / Netflix** | Canary, error budgets, automated rollback. | Health gates, auto-rollback, DORA metrics per app. |
| **Alibaba → Apache Seata** | Transaction coordination at Singles' Day scale. | AT on PostgreSQL, TCC on Redis Cluster, smart bypass. |
| **MCP (Linux Foundation / AAIF)** | Open agent-tool standard; adopted by ChatGPT, Gemini, Copilot, Cursor, VS Code; 10,000+ servers. | DocTypes and extension points projected as MCP tools under the same permissions. |

**How to tell it:** "We didn't ask 'how do you build an ERP'. We asked 'what does Frappe get right, what is Frappe missing, and how did Erlang, Stripe and Kubernetes solve the missing parts', then put it together for Java 25."

---

## 2. The DocType engine: the heart of the platform

**Key point:** The DocType engine is what turns Astra from "a CMS with plugins" into a Frappe-class platform. A DocType fully describes a kind of business record. Everything a user or an agent touches is a DocType: a blog post, a customer, a sales invoice, a stock ledger entry, a workflow definition, even a DocType itself.

### 2.1 What a DocType declares

Name, module, kind (master, transaction, child, setup, ledger, virtual), storage strategy, whether it is submittable, naming rules, change tracking, the field list (Link, Table, Currency, Select, fetch-from…), indexes on projected columns, permissions by role and perm level with row-level conditions, lifecycle hooks (validate, on_submit, on_cancel…), workflow, and the generated API config (REST, GraphQL, MCP, dated versions).

### 2.2 Storage: zero-DDL by default, ledgers where it matters

Frappe creates one table per DocType and runs DDL when you add a Custom Field. Astra never runs DDL for a form change in production, and still gives accounting-grade storage where ERP needs it.

| `storage` | Used for | How it works | DDL |
| --- | --- | --- | --- |
| `jsonb` (default) | Masters, transactions, settings, content | Core metadata in indexed relational columns; fields in JSONB. Fields marked `projected` get an **expression index** (`CREATE INDEX CONCURRENTLY` on the LEAKPROOF accessor `astra_ext.astra_field(data, 'field')`, because under row-level security PostgreSQL ignores indexes on `data->>'field'`), which is genuinely online; generated queries use the same expression. Range filters use `projected: materialised`: a typed column added as a metadata-only change, kept in sync by a trigger, backfilled in batches in the background, then indexed `CONCURRENTLY`. **No stored generated columns** on tables with data, because PostgreSQL rewrites the whole table. | Never for fields; expression indexes are online, additive, reversible; materialised columns are online but take time to backfill |
| `ledger` | GL Entry, Stock Ledger Entry, payroll postings, audit | Append-only relational tables, partitioned by period, fixed columns. Never updated; corrections are reversing entries. Written only inside `@AstraGlobalTransactional` by the owning app. | Owned by the app, evolved with expand/contract |
| `virtual` | External systems | A `DocTypeAdapter` maps the DocType onto a legacy ERP/CRM/DB. Same API, permissions, tools. | n/a |

**Line to say:** "The invoice lives in JSONB so it can be customised freely; its postings live in a ledger so they are immutable and auditable. Two strategies, one DocType engine."

**Two rules so flexibility doesn't eat performance:**
* **Projection and index quotas.** Each DocType has a projection and index quota (default 8 and 12), each tenant has an overlay projection quota, and materialised projections on large tables need an approval step. Going over quota is blocked during metadata validation, before it reaches the production database.
* **Hot DocTypes.** A PostgreSQL `UPDATE` rewrites the whole row, so a DocType with a few fields that change many times a day (stock levels, outstanding amounts, counters, status) will bloat and overload vacuum if every field lives in JSONB. The rules: keep documents small, under about 2 KB, so an update does not rewrite TOAST; leave fast-changing fields unindexed where possible so updates are HOT, and set `fillfactor` below 100; put counters and balances in `ledger` or Redis; split extremely churny status into a narrow side DocType. Materialised columns are a typed copy for range filters and do not make an update cheaper. For the highest-churn DocTypes, `ledger` or a relational table fits better than `jsonb`.

### 2.3 Document lifecycle, versions, amend

* **Submittable** DocTypes carry `docstatus`: draft, submitted, cancelled. Submitted documents are immutable except for `allow-on-submit` fields. Cancelling reverses postings; **amend** creates a new revision (`INV-0001-1`) linked to the original. That is why an invoice is handled as a financial record, not a blog post.
* **Track changes** stores a diff per save; versions are queryable.
* **Links** are validated on write, protecting referential integrity across JSONB and ledgers; `fetch-from` copies denormalised values at write time, the way ERP documents expect.
* **Child tables** are ordered sub-documents.
* **Naming series** are cluster-safe (Redis counters, gap-free mode for fiscal documents).

### 2.4 Customisation as overlays that survive upgrades

A tenant customises an app's DocType without forking it, and the customisation **survives every app upgrade**: add fields, change field properties, add permissions, attach a Wasm server script, attach a client script for Desk. Overlays are data, versioned, pinned per tenant, and applied at metadata-load time. An app upgrade that removes a field an overlay depends on is caught by the **conformance check** before rollout.

This is the biggest lesson from Frappe: Custom Fields and Property Setters are why ERPNext customers can upgrade. Astra keeps the idea and tightens it with automated checks.

### 2.5 Everything generated from metadata

REST `/api/doc/<DocType>`, GraphQL, MCP tools, Desk UI (form, list, kanban, calendar, tree, report builder, dashboard, workspace), print/PDF, notification/email/webhook, search and vector index, OpenAPI/JSON Schema/client types. Write the DocType once, get all of it.

### 2.6 Reports and scripts

Report Builder runs on projected columns. Query reports are parameterised SQL over projections and ledgers, reviewed like code. Script reports and server scripts run as Tier 2 Wasm with budgets, or Tier 1 Java for trusted apps. This is Frappe's Server Script idea with a real sandbox instead of a restricted interpreter.

---

## 3. Everything is a plugin ("app")

**Key point:** An app is a plugin that ships DocTypes, lifecycle hooks, extension-point implementations, fixtures, patches, and Desk assets. Astra CMS and the Astra ERP modules are apps; a tenant's own module is an app too. The kernel has **no privileged shortcuts**.

* An application = kernel + a list of apps in `astra.yaml`; `astra build` produces an immutable image.
* An app can **define new extension points** for other apps, like the sales module's `PricingRule`, which a tenant implements in Wasm.
* Extension points are **typed Java interfaces** with JSON Schema, validated at load, dispatched without reflection: DocType hooks, workflow, report, print, Desk, route, event, job, TCC branch, ledger, adapter, auth, tool, agent, model provider, CLI.

### Three isolation tiers, one SPI

| | Tier 0: Shared (build-time) | Tier 1: Isolated JVM (runtime) | Tier 2: Wasm sandbox (runtime) |
| --- | --- | --- | --- |
| Loading | Compiled into the image, Quarkus `@BuildStep` + Jandex | Separate ClassLoader per app version | Chicory module, Extism-compatible ABI |
| Dispatch cost | Direct call | `MethodHandle` bound at load | Wasm compiled to bytecode; interpreter on native |
| Change requires | Image rebuild, rolling deploy | Hot-swap | Hot-swap |
| Capabilities | Everything, including ledgers and new extension points | DocTypes, hooks, workflows, reports, routes, events, jobs, TCC, tools, agents | Server scripts, script reports, enrichers, validators, tools |
| Failure blast radius | Whole pod | Bulkhead: timeout, concurrency limit, circuit breaker | Hard sandbox: memory, deadline, interrupt, no ungranted host calls |
| Languages | Java/Kotlin | Java/Kotlin | Rust, Go, C/C++, C#, Zig, AssemblyScript, JS, Python (Extism PDK) |
| Default for | Core apps (accounts, stock), anything on every request | Tenant modules, integrations, localisation | Tenant scripts, marketplace, **AI-generated code** |

**How to tell it:** "Accounts and stock are Tier 0 because every document goes through them. Vietnamese localisation is Tier 1 because it changes with every new government circular. A tenant's credit-check script is Tier 2 because we don't trust that code yet but want it running now." Moving between tiers is a build flag.

Chicory: pure Java, no JNI, with a Wasm → bytecode compiler. Extism ABI: ready-made PDKs for many languages; Astra implements the ABI on Chicory itself because Extism's official Chicory SDK is still experimental. Trade-offs: Chicory has no fuel metering yet (we use deadline + memory); native image is interpreter-only; Tier 1 is ClassLoader "leak-resistant", not leak-proof.

### App manifest: least privilege, with provenance

`astra-app.yaml`: id, version, SPI range, tier, dependencies, DocType list, overlays, fixtures, patches, **capabilities** (hosts, secrets, DocTypes, read-only ledgers, LLM budget), tenancy, runtime limits, compat, rollout, and **provenance** (which person or tool generated it). Security review = manifest review. Sigstore signing, SBOM, publisher allow-lists, a policy that forces AI-generated code onto the Wasm tier.

### Cluster-wide hot-swap

Publish → verify (signature, SPI, provenance) → announce via Redis Pub/Sub → canary by pod percentage with health gates → side-by-side load, warm-up, atomic dispatch re-point, drain, leak check → automatic cluster-wide rollback on health failure → new HPA pods pull current state at boot.

---

## 4. Continuous change without breaking: the compatibility contract

**Key point:** Enterprise architects should hear this part first. *If we ship every day, does yesterday's feature still work, and how do we keep downtime at zero?*

### 4.1 The SPI is the ABI

Apps depend only on `astra-spi`. Maturity levels are `alpha`/`beta`/`stable`. A deprecated `stable` element keeps working for at least **12 months or 3 minor releases**, with a structured warning. **Kernel N runs SPI N-1 apps** through a shim. **Conformance suite:** golden apps from every supported SPI version, **plus every registered tenant overlay**, run in CI; break one and it does not ship.

### 4.2 Versions coexist; tenants pin

N app versions run side by side; an old version stays as long as a tenant pins it or a call is still in flight. Per-tenant pins for apps and APIs. Generated APIs are date-versioned and served side by side.

### 4.3 Data evolves without locks

| Data | Mechanism | DDL | Rollback |
| --- | --- | --- | --- |
| DocType fields, overlays | Zero-DDL JSONB; upcasters on read, lazy rewrite; online projections | Never for fields | Instant |
| Ledgers | Expand/contract enforced by CI; append-only | Additive, online | Previous version still reads |
| Data patches | Idempotent, versioned, run per tenant on rollout, resumable | n/a | Compensating patch |
| Events | `schemaVersion`; consumers declare ranges; upcasters | n/a | Old consumers keep working |
| Cache | Keys include app and schema version; cluster-wide invalidation | n/a | No stale reads |

### 4.4 Zero-downtime matrix (one-slide summary)

| Change | Mechanism | Downtime | Rollback |
| --- | --- | --- | --- |
| Tier 1/2 app, overlay, server script | Hot-swap, canary, drain | 0 | Seconds, automatic |
| Tier 0 app | New image, rolling under PDB, transactions drained | 0 | Minutes |
| DocType field, form, permission, workflow | Metadata version, zero-DDL | 0 | Instant |
| Ledger schema | Expand/contract | 0 | Previous app still runs |
| Prompt, model, agent policy | Versioned artifacts, same rollout path | 0 | Seconds |
| Kernel minor / major | Rolling with shim / blue-green, tenants migrate by pin | 0 | Minutes / switch traffic back |
| PostgreSQL / Redis / Seata TC | Standard HA; retries with idempotent TCC | 0 for reads, sub-second failover for writes | n/a |

### 4.5 Change is observable

Every rollout is an event correlated with metrics. **DORA metrics per app** are built in.

---

## 4A. Multi-tenancy: a kernel property, not an app feature

**Key point:** Every kernel service applies the tenant boundary itself. An app or agent cannot cross tenants even if it tries, because no API accepts a tenant from the caller.

### 4A.1 Model

`Tenant` is a kernel `setup` DocType, so onboarding, suspension and offboarding are ordinary, audited document lifecycles. A Tenant declares: status, parent organisation (optional), isolation profile, region, resolution and identity providers (OIDC, SAML, SCIM), keys (BYOK, rotation), enabled apps with pins and configuration, overlays, API version, plan, quotas, feature flags.

### 4A.2 Resolution and context

Resolution order: custom domain or subdomain, OIDC token claim, header for service accounts, path prefix for the Desk. The result is bound once per request in a `ScopedValue` and inherited by every virtual thread, subtask, hook, job and agent. A user can belong to several tenants; switching tenant is a new session with a new scope.

### 4A.3 Three isolation profiles

| Profile | Data | Keys | Compute | Use for |
| --- | --- | --- | --- | --- |
| `shared-rls` (default) | One PostgreSQL cluster; `tenant_id` column + row-level security on every table, including ledgers and audit; partitioning by tenant hash for large tables | Per-tenant data keys under the tenant's KMS | Shared pods, bulkheads per tenant and per app | Most tenants; thousands per pod |
| `database-per-tenant` | Own database, same schema, RLS still on | BYOK | Shared pods | Regulated customers, large tenants |
| `cluster-per-tenant` | Dedicated PostgreSQL, Redis, storage, Astra pods | HYOK / HSM | Dedicated | Data sovereignty, air-gapped |

Tenants move between profiles with `astra tenant migrate`: snapshot, dual-write window, cutover, verify; no interruption for reads and a sub-second write pause at cutover.

### 4A.4 What is scoped per tenant

Data and ledgers (RLS, partitioning), metadata (base DocTypes global; overlays, pins, feature flags per tenant; effective metadata cached per tenant and version), apps (installed globally; enabled, pinned, configured per tenant), cache (keys include tenant), events and jobs (fair scheduling, per-tenant concurrency caps), transactions (Seata keys and Redis hash tags include tenant), search and vectors, object storage, identity (per-tenant IdP, MFA, SCIM), AI (per-tenant model, region, budgets, PII redaction, approval gates, opt-out), compliance (per-tenant RoPA, DSAR, retention, residency), observability (every span, metric and log tagged with tenant; per-tenant dashboards and SLOs).

### 4A.5 Organisations and sharing

An `Organisation` groups tenants (a holding company and subsidiaries, a franchise network, an agency and its clients). Within an organisation, DocTypes can be declared `shareable` with an explicit policy (read-only masters such as Item or Chart of Accounts templates; consolidated reporting). Sharing is always opt-in, logged, and expressed as metadata; the default remains strict isolation. Multi-company within one tenant is handled by the `Company` DocType and is unrelated to tenancy.

### 4A.6 Fairness, quotas and metering

Bulkheads per tenant and per app: virtual-thread limits, rate limits, job concurrency, agent token budgets. A noisy tenant degrades itself, not its neighbours. Quotas come from the plan, can be overridden per tenant, with soft limits before hard limits. Metering emits usage records (requests, storage, jobs, agent tokens, seats) as a `ledger` DocType for billing integrations. Astra does not ship billing; it ships the meter.

### 4A.7 Tenant lifecycle

Provision (create the Tenant, keys, region placement, enable apps, run fixtures and patches, verify with the conformance suite) → customise (overlays, pins, configuration, IdP; versioned and audited) → suspend (requests rejected with a tenant-specific status, jobs paused, data retained) → export (all DocTypes, files, audit, metadata, overlays in an open format) → offboard (retention applied, erasure by key destruction, ledgers retained under legal obligation with pseudonymised references, deletion evidence recorded) → migrate (between profiles or regions).

### 4A.8 Single-tenant mode

`tenancy.mode: single` keeps every mechanism but fixes one tenant: a self-hosted deployment for one company pays no tenancy overhead, and can later switch to `multi` without a data migration.

**Line to say:** "One self-hosted company today, a SaaS serving thousands of companies tomorrow: same codebase, same data model, no migration."

---

## 4B. Compliance by Design

SOC 2 Type II and ISO 27001 certify an *operator* (the organisation running the service) over an observation period, not software. GDPR and equivalent laws bind *controllers and processors*. No open-source codebase is "SOC 2 certified". What a platform can do is **make those outcomes cheaper**: ship the controls, generate the evidence, and keep the data model compliant by construction. A hosted Astra Cloud would pursue the certifications itself; self-hosters inherit the controls and the evidence pipeline.

**Key point:** Most compliance effort is collecting evidence and knowing what data lives where, why, and for how long. If the DocType already answers those four questions for every field, then RoPA, DSAR, retention and residency are **generated**, not **done by hand**.

### 4B.1 Frameworks in scope

| Framework | Scope | What Astra provides |
| --- | --- | --- |
| **SOC 2 Type II** | Security, Availability, Processing Integrity, Confidentiality, Privacy | Controls for criteria CC6 (access), CC7 (monitoring), CC8 (change management), A1, PI1, P-series; continuous evidence export; control matrix in `docs/compliance/` |
| **ISO/IEC 27001:2022** + 27017/27018/27701 | ISMS, cloud, PII in cloud, privacy management | Annex A mapping, asset inventory from DocType metadata, access review workflows, sub-processor register |
| **GDPR / UK GDPR** | EU/UK personal data | Classification, legal basis, RoPA generation, DSAR automation, retention and erasure, 72-hour breach workflow, DPIA templates, processing records, EU residency profile |
| **Vietnam PDPL** (Law 91/2025/QH15, Decree 356/2025, effective 1 January 2026, replaces Decree 13/2023) | Personal data of Vietnamese data subjects | Basic vs sensitive data categories, consent records, impact assessment dossiers, cross-border transfer records, request-response timelines, VN residency profile |
| **CCPA/CPRA, LGPD, PDPA (SG), APPI, PIPL** | Regional privacy laws | Same privacy engine with jurisdiction profiles; localisation rules (PIPL, VN) enforced by `ResidencyPolicy` |
| **SOX ITGC** (ERP customers) | Change management, access, segregation of duties, audit trail | Signed, approved, audited rollouts; SoD rules on DocType actions; immutable ledgers; period locks; access reviews |
| **PCI DSS 4.0.1** | Card data | Designed to keep card data **out of scope**: tokenisation via payment providers, no PAN storage |
| **HIPAA** (optional profile) | US health data | Field-level encryption, access logging, BAA-ready controls |
| **EU AI Act** (phased 2025-2027; high-risk dates being deferred by the Digital Omnibus) + **ISO/IEC 42001**, **NIST AI RMF** | AI systems and governance | Agent action logging, human-in-the-loop gates, model and prompt versioning, transparency notices, PII redaction before external model calls, tenant opt-out, AI inventory |
| **EU DORA** (financial entities, since Jan 2025), **NIS2** | Operational resilience, ICT third-party risk | Resilience testing hooks, incident reporting workflow, RTO/RPO evidence, sub-processor register |
| **EU Cyber Resilience Act** (reporting from Sept 2026, full application Dec 2027) | Products with digital elements | SBOM per release, vulnerability handling process, coordinated disclosure, security update policy; the Astra project operates as an open-source steward |
| **WCAG 2.2 AA**, **EU Accessibility Act** (June 2025), **Section 508** | Accessibility | Desk UI built to WCAG 2.2 AA; automated and manual checks in CI |
| **OWASP ASVS, NIST SSDF, SLSA, OpenSSF Scorecard, CIS Kubernetes** | Secure engineering and supply chain | ASVS Level 2 baseline, Level 3 for auth, payments, ledgers; SSDF-aligned SDLC; SLSA Level 3 builds; Scorecard in CI; hardened Helm defaults |

### 4B.2 Compliance metadata on DocTypes

Compliance is declared where the data is declared. Each field can carry: `classification` (public → restricted), `pii` / `sensitive` (including special categories: health, biometric), `legal-basis` (consent, contract, legal-obligation…), `retention` (period, anchor, reason, then delete / anonymise / archive), `residency` (tenant-region, eu-only, vn-only), `encrypt` (field, deterministic for searchability), `mask` and `dsar` (export / redact / exclude).

From this the platform **generates**: the Record of Processing Activities, a data map per tenant, DSAR packages, retention schedules, DPIA skeletons and Vietnam PDPL impact-assessment dossiers, and the data inventory ISO 27001 asks for.

**Line to say:** "In Astra, the question 'where does our personal data live?' is answered by a metadata query, not a three-month project."

### 4B.3 Controls in the kernel

* **Identity and access.** OIDC and SAML SSO, MFA enforcement, SCIM provisioning and deprovisioning, RBAC plus attribute rules, least-privilege defaults, periodic **access reviews** generated as workflows, break-glass accounts with mandatory justification and auto-expiry.
* **Segregation of duties (SoD).** DocType-level rules: the user who creates a Payment Entry cannot submit it; approval chains are workflow states; violations are blocked and logged.
* **Tamper-evident audit trail.** Every DocType change, permission change, rollout, agent action, admin action and export is written to an append-only `ledger` DocType with a hash chain, exportable to WORM and SIEM (`AuditSink`: OpenTelemetry, syslog, OCSF). Retention and legal hold apply to audit too.
* **Encryption and keys.** TLS 1.3, encryption at rest, field-level encryption, keys from `KeyProvider` (AWS KMS, GCP KMS, Azure Key Vault, Vault, HSM), **BYOK per tenant**, rotation, crypto-shredding for erasure where records must stay immutable.
* **Privacy operations.** DSAR portal and API: access, export (JSON/CSV), rectification, erasure, restriction, portability, objection, with deadlines tracked per jurisdiction. Erasure on ledgers: pseudonymise the subject reference and shred the key, preserving the financial record under the legal-obligation basis. Consent is a DocType with versioned purposes. Breach workflow with a 72-hour clock.
* **Data residency and sovereignty.** Tenants pin a region; `ResidencyPolicy` enforces it on primary storage, caches, backups, search, object storage, **and LLM providers**. Profiles `eu`, `us`, `vn`, `sg`, `custom`. No cross-region replication of PII unless the transfer record permits it.
* **Change management.** Signed artifacts with SBOM and provenance; four-eyes approval for production; environment separation; every rollout audited; canary and rollback as compensating controls; DORA metrics as evidence.
* **Availability and resilience.** HA on every tier, encrypted backups with PITR, **restore tests as scheduled jobs with evidence**, published RPO/RTO, chaos and failover testing hooks, incident workflow.
* **Vulnerability and supply chain.** SCA, SAST, DAST and container scanning in CI; CVE response SLAs; `SECURITY.md`; CycloneDX SBOM; SLSA Level 3 provenance; OpenSSF Scorecard; annual third-party penetration test for the reference deployment.
* **Tenant isolation.** Row-level security, per-tenant keys, bulkheads against noisy neighbours, and **DB-per-tenant** or **cluster-per-tenant** profiles for regulated customers.
* **AI governance.** Every agent action logged with model, prompt version, inputs hash and outputs; **human approval gates** for high-impact actions (posting ledgers, changing permissions, bulk operations) configurable per tenant; PII redaction before external model calls; region-pinned or local models; AI inventory and transparency notices generated from metadata.
* **Accessibility.** Desk built to WCAG 2.2 AA, automated axe checks and manual audits.

### 4B.4 Evidence automation

`astra compliance evidence --framework soc2 --period 2026-Q3` exports access-review records, change records with approvals, backup and restore results, vulnerability SLAs, incident logs, and audit-trail integrity proofs. Connectors push the same evidence to compliance automation platforms (Vanta, Drata, Secureframe…). Control catalogues are kept in machine-readable form (OSCAL) under `docs/compliance/`.

### 4B.5 Shared-responsibility matrix

| Area | Astra platform | Operator / tenant |
| --- | --- | --- |
| Controls implemented in software | Provides and tests | Configures, reviews |
| Evidence generation | Automates and exports | Retains, presents to auditors |
| Policies, risk assessment, training, vendor management | Templates and registers | Owns |
| Certification (SOC 2 report, ISO certificate) | Astra Cloud pursues for itself | Self-hosters pursue for their own scope |
| Legal roles (controller, processor, DPO) | Supports both roles | Decides and documents |

---

## 5. Toward an ERP: Astra ERP on Astra

**Key point:** Astra ERP is the proof that the platform is general. It is built the way ERPNext is built on Frappe: **every module is an app, every record is a DocType, every posting is a ledger entry**. It copies ERPNext's *shape*, not its code.

| Module (app) | Core DocTypes | Ledgers | Notes |
| --- | --- | --- | --- |
| `erp.core` | Company, Fiscal Year, Currency, Exchange Rate, UoM, Address, Contact | | Multi-company and multi-currency from day one |
| `erp.accounts` | Account (tree), Journal Entry, Payment Entry, Sales/Purchase Invoice, Tax Template, Cost Center, Period Closing | **GL Entry** | Double-entry; postings are reversed, never edited; period locks |
| `erp.stock` | Item, Warehouse (tree), Stock Entry, Delivery Note, Purchase Receipt, Batch, Serial No | **Stock Ledger Entry** | FIFO / moving average; backdated repost as a job; reservation via Redis TCC for flash sales |
| `erp.selling` | Customer, Quotation, Sales Order, Pricing Rule | | Pricing Rule is an extension point tenants implement |
| `erp.buying` | Supplier, RFQ, Purchase Order | | |
| `erp.manufacturing` | BOM, Work Order, Job Card, Routing | | |
| `erp.hr` | Employee, Leave, Attendance, Payroll Entry, Salary Slip | **Payroll Ledger** | |
| `erp.projects` / `erp.crm` / `erp.assets` | Project, Task, Timesheet / Lead, Opportunity / Asset, Depreciation | GL via `erp.accounts` | |

Platform features that ERP needs and the kernel already provides:

* **Posting a Sales Invoice** touches GL Entry, Stock Ledger Entry, customer outstanding, and inventory reservations in Redis. That is **one** `@AstraGlobalTransactional` method: Postgres via Seata AT, Redis via TCC, all-or-nothing.
* **Month-end close** on 50 pods: period locks are DocType state, enforced by the kernel in lifecycle hooks, not by application code remembering to check.
* **Localisation** (e-invoice, VAT, Vietnamese chart of accounts) ships as a Tier 1 app with overlays and fixtures, pinned per tenant, upgraded independently of `erp.accounts`.
* **Agents in the back office:** "create a journal entry for this bank statement line", "explain this variance" run as `AgentContributor` with role-scoped MCP tools over DocTypes, with budgets and audit.

**Compared with Frappe/ERPNext (fair, not dismissive):** Frappe uses Python and MariaDB/Postgres, one table per DocType, DDL when a Custom Field is added, Gunicorn workers and RQ queues, one database per site. That is a sound choice for SMEs and a broad community. Astra targets cluster scale, high-density multi-tenancy, zero-DDL, multi-tier isolation, cross-store transactions, and an AI surface. Two philosophies, one core idea.

---

## 6. Performance: principles and targets

No reflection on the hot path; DocType reads served from L1/L2 cache keyed by metadata version; writes go through projected columns so reports never scan JSONB; bulkheads per app; zero cost when idle; in-process isolation; structured concurrency for fan-out.

Every target is stated with the **conditions under which it is measured**.

| Metric | Condition | Target (to be validated by public benchmarks) |
| --- | --- | --- |
| DocType get by id | metadata in L1, document in L2 or PostgreSQL by primary key | p99 < 2 ms |
| DocType list, 1M rows per tenant | equality or range filters on at most 2 indexed projections, no joins, page of 50 | p99 < 20 ms |
| Document save, single store | smart bypass, no global transaction, 1 hook | p99 < 15 ms |
| Sales Invoice submit, global transaction | Seata AT on PostgreSQL + TCC on Redis, Raft coordinator in region, 2 branches | p50 < 40 ms, p99 < 150 ms |
| Tier 1 hook, dispatch only | `MethodHandle` vs direct call | < 1 µs |
| Tier 1 hook, end to end | dispatch + capability check + context propagation + audit span | < 50 µs |
| Tier 2 script, warm | pooled, already-compiled module, small input | p99 < 5 ms |
| Tier 2 script, cold compile | Chicory Wasm→bytecode, done at rollout warm-up, off the request path | < 500 ms per module |
| App rollout, propagation | announce → every pod has the new version loaded and warmed in standby, 50 pods | < 2 s |
| App rollout, cutover | atomic dispatch re-point on one pod | < 10 ms |
| App rollout, drain | in-flight calls finish on the old version | bounded by request timeout |
| Kernel pod ready, JVM | Tier 0 wired at build time, Flyway no-op, warm registry | < 5 s |
| Kernel pod ready, native | same | < 1 s |

Three more things, said plainly: a density of "thousands of tenants per pod" is a **hypothesis until benchmarked** with real overlay, cache and ClassLoader footprints; the Seata TC is a central component, kept off the hot path by smart bypass and **sharded by transaction service group** (each group a Raft cluster), so TC throughput scales with the number of groups, not the number of pods; JVM heap per Tier 1 app is **observable but not enforceable**, which is one more reason untrusted code belongs in Tier 2.

**Java 25 and Quarkus (briefly):** unpinned virtual threads (JEP 491), Scoped Values final (JEP 506), Structured Concurrency fifth preview (JEP 505, wrapped behind an executor), Generational ZGC by default (JEP 490, `-XX:+UseZGC`). Quarkus build-time augmentation is the Tier 0 mechanism; full JDK 25 support from 3.31, LTS 3.27. Java 21 is not enough; Spring Boot relies on runtime reflection, which works against Tier 0; the price is writing the Seata extension ourselves.

---

## 7. AI-native: built by AI, used by AI

**For agents that build apps:** the SPI as a whole is large: twenty-plus extension points, the DocType model, manifests, overlays and compliance metadata. What makes it workable for an agent is that each task is narrow: a hook touches two interfaces, a DocType is one YAML file, a Wasm script is one exported function. `AGENTS.md` is organised per extension point so an agent loads only the slice it needs, and `astra mcp --dev` answers "what exists" from the live kernel. The test kit is what makes agent-written apps trustworthy. Two more disciplines for the tool surface: namespacing (`doc.<doctype>.<op>`, `app.<id>.<tool>`), exposing only DocTypes the tenant declares, tool packs by role, paginated `tools/list`; and **grounding**: answers about business data must cite the DocTypes and ledger entry ids they used, and the gateway rejects unsourced answers for financial DocTypes. The test kit gives a pass/fail loop (contract tests, capability assertions, budgets, **assertions that overlays survive upgrades**). AI-generated code defaults to Tier 2 Wasm; `provenance.generator` is recorded; policy can require the sandbox. Canary + auto-rollback catch failures under real traffic. The repository ships `AGENTS.md`, `CLAUDE.md` and `llms.txt`.

**For agents that use an application:** every DocType (search, get, create, submit, cancel), event, job and `ToolContributor` is projected as an MCP tool with the DocType's JSON Schema, scoped by role and tenant, audited like any call. `AgentContributor` runs budgeted agents with scoped memory (DocTypes + pgvector). `ModelProvider` abstracts Claude, OpenAI, Gemini and local models. Prompts and evals are versioned artifacts on the same rollout path.

**Line to say:** "AI writes, the platform verifies, the cluster rolls out, and if it is wrong the cluster rolls itself back. And the AI accountant works on the same DocTypes, with the same permissions and the same audit, as the human accountant."

---

## 8. Comparison table (one-slide summary)

| Platform | Metadata-driven | Typed extension points | Hot-swap without restart | Side-by-side versions, tenant pins | Isolation | Zero-DDL customisation | Cross-store transactions | Agent tool surface |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Frappe / ERPNext | **Yes** | Python hooks | Partial (bench restart) | No | No | No (DDL) | No | Bolt-on |
| WordPress / Strapi | No | No | Yes | No | No | No | No | Bolt-on |
| Keycloak SPI | No | Yes | No | No | No | n/a | No | No |
| OSGi / Liferay | Partial | Yes | Yes | Partial | ClassLoader | n/a | No | No |
| Shopify Functions | n/a | Yes | Yes | Per app | Wasm | n/a | n/a | n/a |
| **Astra** | **Yes** | **Yes** | **Tier 1 & 2** | **Yes, per tenant** | **Shared / JVM / Wasm** | **Yes** | **Seata AT + TCC** | **MCP, capability-scoped** |

---

## 9. Risks and trade-offs (for Q&A)

| Risk | Level | Mitigation |
| --- | --- | --- |
| Very large scope (platform + CMS + ERP) | High | 9-phase roadmap; kernel and DocType engine first, ERP later; each ERP module is an independent app |
| Overall complexity | High | Everything can be turned off; a small project runs one pod, single Postgres + Redis, no Seata, no MCP |
| Self-written Seata extension for Quarkus; Seata still incubating | High | Prioritised in Phase 4; public benchmarks; Seata is widely used in Chinese e-commerce |
| Is JSONB enough for ERP | Medium | Projected columns for filters/reports; append-only relational ledgers for GL/SLE; 1M rows per tenant benchmark |
| Chicory lacks fuel metering; Extism Chicory SDK is experimental | Medium | Deadline + memory cap; own ABI implementation |
| Structured Concurrency in preview | Medium | Wrapped behind an executor |
| Tier 1 ClassLoader leaks | Low to medium | Lifecycle tracking, Tier 2 recommended |
| SPI and DocType model must stabilise early | Medium | Maturity levels, deprecation, shims, conformance suite including overlays |
| Performance targets not yet measured | Medium | Benchmarks in Phase 2, revised against real numbers |
| AI-generated code in production | Medium | Sandbox by default, provenance, contract tests, canary |
| Licence confusion with ERPNext | Medium | Clean-room stated in README and CONTRIBUTING; no PRs that copy GPL code |
| Overpromising "compliance" | Medium | Say it plainly: the platform provides controls and evidence; certification belongs to the operator; public shared-responsibility matrix |
| Changing laws (AI Act deadlines deferred, PDPL has new decrees) | Medium | Jurisdiction profiles are data, updated like apps; no law hard-coded into the kernel |
| Erasing personal data on immutable ledgers | Medium | Pseudonymised references + crypto-shredding; financial records retained under the legal-obligation basis; documented in the DPIA |

---

## 10. Roadmap

Rebuilding a Frappe-class platform with a three-tier sandbox, distributed transactions, multi-tenancy, a compliance engine and an AI surface is years of work for a team; a single repository cannot finish it in a year. The roadmap therefore has two readings: the **thin slice** is what gets built and demonstrated first, end to end; later phases are sequenced so each phase ships something usable on its own. The compliance table is a catalogue of **profiles enabled over time**, not a single Phase 5 deliverable.

**Thin slice (first demo, in progress):** one DocType-driven app (CMS `Article`), tenancy by `ScopedValue` and row-level security, zero-DDL JSONB storage with generated REST, one Tier 0 hook, running on Docker Compose and k3d. Next increments, in order: a minimal `GL Entry` ledger with a submittable invoice; a Seata spike proving AT on PostgreSQL + TCC on Redis on virtual threads; one Tier 2 Wasm script; one canary rollout across two pods; a thin MCP server with `doc.search / get / create`. Each increment comes with a public benchmark.

1. **Phase 1 (in progress):** Maven skeleton, `astra-spi`, DocType model and YAML schema **including compliance attributes**, manifest, maturity levels, deprecation, `AGENTS.md`/`llms.txt`, `SECURITY.md`, OSCAL catalogue skeleton.
2. **Phase 2:** kernel, three-tier plugin host **with instruction counting for Tier 2**, **tenancy core** (Tenant DocType, resolution, `ScopedValue` context, RLS, `shared-rls` profile, bulkheads), DocType engine core (JSONB, expression-index projections with quotas, naming, links, child tables, permissions, submittable, versions), **tamper-evident audit ledger from the first write**, generated REST, **thin MCP** (`doc.search / get / create`), **Seata spike** (AT + TCC + virtual threads, go/no-go for Phase 4), testkit, SLSA Level 3 build pipeline, first benchmarks.
3. **Phase 3:** control plane and compatibility: registry, signatures, SBOM, four-eyes approval, canary, shims, conformance suite including overlays, data patches, DORA.
4. **Phase 4:** Seata extension (or the fallback chosen by the Phase 2 spike: transactional outbox plus idempotent TCC without a central coordinator), ledger storage, materialised projections with backfill jobs, cache bus, upcasters, date-versioned APIs.
5. **Phase 5: Compliance & Tenancy Pack.** Identity (OIDC/SAML, MFA, SCIM per tenant), access reviews, SoD, `database-per-tenant` and `cluster-per-tenant` profiles, tenant migration, organisations and sharing policies, quotas and metering, `KeyProvider` with BYOK and field-level encryption, `ResidencyPolicy` and region profiles (eu, us, vn, sg), privacy engine (RoPA, DSAR, consent, retention, crypto-shredding, breach workflow), `AuditSink` to SIEM and compliance platforms, evidence export CLI, SOC 2 / ISO 27001 / GDPR / PDPL mappings published.
6. **Phase 6:** Desk to WCAG 2.2 AA, overlays, workflow, reports, print, notifications.
7. **Phase 7:** AI surface and AI governance: MCP, LLM gateway with PII redaction and region pinning, agent runtime with budgets and approval gates, prompt registry, AI inventory.
8. **Phase 8:** CIS-hardened Helm, Operator, Seata Raft, OpenTelemetry, Prometheus, backup/restore evidence jobs, failover testing, third-party penetration test.
9. **Phase 9:** Astra CMS.
10. **Phase 10:** Astra ERP: core, accounts, stock, then selling, buying, manufacturing, hr, projects, crm, assets; SOX ITGC evidence; Vietnam localisation (e-invoice, VAT, PDPL dossiers) as the reference Tier 1 app; signed marketplace.

---

## 11. Closing line

> *"Astra is a Frappe-style, metadata-driven application platform, rewritten from scratch in Java 25. Everything is a plugin, including the CMS and the ERP. DocTypes are data, customisations are overlays that survive upgrades, postings are immutable ledger entries, and compliance is a property of the metadata. Versioned apps run side by side, pinned per tenant, hot-swapped across the cluster with a canary that rolls itself back. Every write across PostgreSQL and Redis is one real transaction. Every DocType is also a tool for AI agents, under the same permissions and audit. Ship every day, never break yesterday, and be ready for an auditor in any country."*

---

## Appendix: expected questions

**"Is this a port of ERPNext to Java?"** - No. Frappe is MIT, ERPNext is GPL-3.0, Astra is MIT. No code, schema, fixtures or assets are used. Only the concepts are borrowed: DocType, app, overlay, submittable, and the shape of the ERP modules. Every implementation is original.

**"Why not just use ERPNext?"** - ERPNext is very good for SMEs. Astra targets cluster scale, high-density multi-tenancy, zero-DDL customisation, multi-tier isolation for third-party and AI code, transactions across Postgres and Redis, and an AI surface. If you do not need those things, ERPNext is the better choice.

**"Can JSONB handle ERP?"** - Flexible fields live in JSONB, filter/report fields in indexed projected columns, postings in append-only relational ledgers. No report query scans JSONB. The 1M rows per tenant benchmark comes in Phase 2.

**"Does upgrading an app break tenant customisations?"** - Overlays are versioned data, applied at metadata-load time; the conformance suite runs every tenant's overlays before rollout; if something breaks, it does not ship.

**"Is it really zero downtime?"** - Tier 1/2, overlays, DocTypes, prompts: 0, with rollback in seconds. Tier 0 and the kernel: rolling under a PDB. Infrastructure failover: sub-second for writes, to be straight about it.

**"Is it safe to run AI-generated code in production?"** - Wasm sandbox by default with budgets; provenance recorded; contract tests; canary + auto-rollback. More layers than most human-written code gets.

**"Is Seata production-ready?"** - It entered the Apache Incubator in 10/2023, release 2.6 (2/2026), is graduating, and is widely used in Chinese e-commerce. The immature part is the Quarkus bridge, and that is the work Astra does.

**"Is MCP stable?"** - It was donated to the Agentic AI Foundation under the Linux Foundation in 12/2025, co-founded by Anthropic, Block and OpenAI, backed by Google, Microsoft and AWS. An open, multi-vendor standard.

**"Is Astra SOC 2 or ISO 27001 certified?"** - No open-source codebase is certified; certification belongs to the operator, over an observation period. Astra provides the controls, generates the evidence, and keeps the data model compliant by construction, so the operator reaches certification faster and cheaper. The shared-responsibility matrix is public in the README.

**"Does Vietnamese customer data have to be stored in Vietnam?"** - The tenant pins region `vn`; `ResidencyPolicy` enforces it on storage, cache, backups, search, and LLM calls too. Cross-border transfer dossiers under Law 91/2025 and Decree 356/2025 are generated from metadata.

**"How do you erase personal data when postings must be immutable?"** - Pseudonymise the subject reference and destroy the encryption key (crypto-shredding); financial records are retained under the legal-obligation basis; documented in the DPIA. This is what GDPR allows and accounting requires.

**"Does an AI agent posting entries need human approval?"** - By default, yes. Human approval gates are configurable per tenant for high-impact actions; every agent action is logged with the model and prompt version; PII is redacted before external models are called.

**"Does multi-tenancy slow things down or risk leaks?"** - The tenant boundary is applied by the kernel through `ScopedValue` and RLS; no API accepts a tenant from the caller. Bulkheads are per tenant, so a noisy tenant only slows itself down. Customers who need stronger separation choose a dedicated database or cluster without changing code.

**"Where is the code? Where are the performance numbers?"** - There is a running thin slice in the repo: `make k3d-up && make demo` brings up a two-pod k3d cluster and proves tenant isolation end to end. The rest is blueprint; benchmarks come in Phase 2. Transparency matters more than impressing.

**"One lesson from the demo itself?"** - The first version bypassed row-level security without a single error, because the application connected as the PostgreSQL superuser. Now the kernel refuses to start if its role can bypass RLS. That is why "fail-closed" has to be the default, not an option.
