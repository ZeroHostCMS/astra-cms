# IAM with Keycloak and a custom login page, on local k3d

Status: done · Created 2026-10-21 · Verified on local k3d (scripts/demo-iam.sh, 11 server tests)

## Outcome

A mobile (Flutter) developer can, this week, sign users in against the local k3d stack through a branded
Keycloak login page (vi + en), call the Astra API with a bearer token, and see per-tenant roles and DocType
permissions enforced. Staff are provisioned per tenant by tenant admins; public users self-register and
join a tenant chosen by the app as `reader`.

## Decisions

| Topic | Decision |
| --- | --- |
| Keycloak | 26.8.1, custom image (`astra/keycloak:dev`) with theme + realm baked in, `--import-realm`, served at `/auth` behind Traefik on `http://localhost:8080` |
| Public URL | `ASTRA_PUBLIC_URL` (default `http://localhost:8080`) fixes the token issuer; set it to a LAN IP for physical devices. `adb reverse` for Android emulator |
| Mobile flow | Authorization Code + PKCE (S256), public client `astra-mobile`, redirect `io.astra.mobile:/oauthredirect`, refresh tokens, `offline_access` optional |
| Tenancy model | Keycloak groups `/tenants/{tenant}/{admin,editor,author,reader}`; full group paths in the `groups` claim become Quarkus roles |
| Tenant per request | `X-Astra-Tenant` header, subdomain, or the user's only tenant; user must be a member (403 otherwise). `X-Astra-User` removed |
| Platform admin | realm role `platform-admin`: manages tenants and members, no implicit document access |
| DocType permissions | `permissions:` in DocType YAML (`role`, `read`, `create`, `write`, `if-owner`), enforced in the generated REST API |
| Self-join | `POST /api/iam/tenants/{t}/join` when the tenant group has `public-signup=true`; adds `reader` |
| Login features | Login, register, update password (first login), vi + en. No SMTP, MFA or social login this round |

## Phases

1. Infra: Keycloak image, realm export, k8s manifests, compose service, `k3d-up.sh`.
2. Login theme `astra` (login, register, update-password, info/error), vi + en.
3. Server: `astra-iam` module (OIDC resource server, `/api/iam/*`, Keycloak admin client); kernel tenant filter on
   identity; DocType permissions in SPI + loader + enforcement.
4. Tests (`@TestSecurity`), `scripts/demo-iam.sh` end-to-end against k3d.
5. Docs: `docs/iam-mobile-integration.md` (Flutter), README run section.

## Acceptance criteria

- `make k3d-up` brings up Postgres, Redis, Keycloak, 2 server pods; `http://localhost:8080/auth/realms/astra/account` shows the Astra theme.
- `./mvnw test` passes; unauthenticated `/api/doc/*` → 401; non-member tenant → 403; reader cannot create (403).
- `scripts/demo-iam.sh` passes against k3d: tokens, `/api/iam/me`, member provisioning, self-join, permission checks.
- The Flutter guide's discovery URL, client id and redirect URI match the realm export.

## Non-goals

SMTP/forgot password, MFA, social IdPs, SCIM, per-tenant realms, row filters on reads (readers see drafts).
