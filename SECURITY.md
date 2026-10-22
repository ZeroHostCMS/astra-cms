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
- The Tier 2 WebAssembly sandbox: memory caps, deadlines, host function access.
- Authentication and authorisation: OIDC tokens, tenant roles, DocType permissions, SCIM.
- The MCP server: tool permissions and PII masking.
