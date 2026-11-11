# Security Policy

## Supported versions

Astra has not had a release yet. Security fixes land on the `main` branch only.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Report it privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**. If that option is not available, email the maintainer at pandev00@sagozen.digital with "SECURITY" in the subject.

Include what you can of the following:

- the affected module (for example `astra-doctype`, `astra-iam`, `astra-plugin-host`) and commit;
- steps to reproduce, or a proof of concept;
- the impact you expect, such as cross-tenant data access, privilege escalation or sandbox escape.

We aim to acknowledge a report within 5 working days and to agree on a disclosure date with you once a fix is ready.

## Areas of particular interest

- Tenant isolation: row-level security, tenant resolution, Link validation across tenants.
- The Tier 2 WebAssembly sandbox: memory caps, deadlines, host function access, and the bytes ABI (`ScriptRuntime.callBytes`):
  the host bounds-checks the pointer and length a module returns and caps both input and answer at 64 KiB.
- The Tier 1 isolated-JVM host (`astra-plugin-host`, package `jvm`): a plugin's class loader sees only the JDK and
  `io.astra.spi.*` (not the kernel, documents or the ledger); the network it is given is `PartnerHttp`, limited to
  the origins in its manifest (`capabilities.network`); every call runs in a bulkhead (permits, circuit breaker,
  deadline). This is isolation, not a sandbox: Java 25 has no SecurityManager, so nothing stops plugin code from using
  JDK classes such as `java.net.Socket` directly. Tier 1 is for code you trust to run in the JVM; untrusted code,
  including code an AI wrote, belongs in Tier 2.
- Price rules as data (`astra-kernel`, `io.astra.kernel.rules`): DRL runs inside the core JVM, so `RuleValidator`
  checks every word against an allowlist before compiling (docs/pricing-rules.md). A way to reach anything beyond
  `QuoteRequest`, the `lines` global and `Math` from a rule set that passes validation is a vulnerability.
- Releases (`AppRelease`): only the tenant role `admin` approves (submits) a release. The `release-bot` role, meant for
  AI agents' tokens, can propose and read but not approve; the MCP server has no approve tool. A path that lets
  `release-bot` change what runs in production is a vulnerability.
- Authentication and authorisation: OIDC tokens, tenant roles, DocType permissions, SCIM.
- The MCP server: tool permissions and PII masking.
