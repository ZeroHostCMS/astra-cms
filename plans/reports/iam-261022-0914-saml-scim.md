# Phase 1c: IAM, SAML SSO and SCIM 2.0 on Keycloak

Plan: `plans/261022-0914-roadmap-business-apps/plan.md`, phase 1c.
Worktree: `/Users/bwork/Documents/AstraCMS/astra-cms/.claude/worktrees/agent-a08cdc6d86bc7ccf2`
Branch: `worktree-agent-a08cdc6d86bc7ccf2` (commits `3f108e1`, `64cef2b`, `5d5cbb1`, plus this report, on top of `d6eaa83`).

## What was built

### SAML 2.0 SSO through Keycloak brokering
- **New realm `acme-corp-idp`** (`deploy/keycloak/realm/acme-corp-idp-realm.json`): a self-contained enterprise IdP running in the same Keycloak, so no external SaaS is involved.
  - Groups: `astra-admins`, `astra-editors`, `astra-authors`, `astra-readers`.
  - Users: `linh` (in editors) and `bao` (in readers), both with password `Astra@123`.
  - SAML client `urn:astra:sp`:
    - signs both the assertion and the response, with RSA-SHA256;
    - sends `email`, `firstName`, `lastName` and `memberOf` as attributes.
- **New identity provider `acme-corp` (SAML) in the `astra` realm**:
  - signatures are required: `validateSignature=true` and `wantAssertionsSigned=true`;
  - the IdP signing key is loaded from the IdP's metadata descriptor URL inside the container (`useMetadataDescriptorUrl`), so no certificate is committed to the repo;
  - sync mode is FORCE, so the role is re-evaluated at every login.
- **Mappers on `acme-corp`**:
  - username becomes `acme-corp.<NameID>`;
  - email, firstName and lastName are copied from the SAML attributes;
  - four "Advanced Attribute to Group" mappers turn `memberOf=astra-<role>s` into membership of `/tenants/acme/<role>`.
- **Flow (SP-initiated)**:
  1. The Desk client starts sign-in with `kc_idp_hint=acme-corp`.
  2. Keycloak sends an AuthnRequest to the IdP, and the user logs in there.
  3. The IdP posts back a signed SAMLResponse to the broker.
  4. Keycloak runs first-broker-login and returns an OIDC authorization code.
  5. The access token's `groups` claim contains `/tenants/acme/editor`.
  6. Astra applies the tenant role exactly as it does for local users.
- **Import works the same way on Compose and k3d**:
  - `deploy/keycloak/Dockerfile` now copies `realm/*.json`, so `--import-realm` loads both realms on first start.
  - `scripts/keycloak-sync-realm.sh` now brings an already-running Keycloak up to date:
    - creates any realm file's realm that is missing;
    - adds missing identity providers and mappers;
    - grants the service-account realm roles listed in the realm file. This is needed because Keycloak creates the service-account user itself when a client is imported, so the partial import skips the user entry in the file.
    - rewrites the browser-facing SAML URLs (`singleSignOnServiceUrl`, `idpEntityId`, and the SP client's redirect URIs) to the public origin `BASE`: `:8180` on Compose, `PUBLIC_URL` on k3d.
  - `scripts/keycloak-compose-dev.sh` also makes `acme-corp-idp` accept plain HTTP from the host, after the sync step.

### SCIM 2.0 at `/scim/v2` (astra-iam)
- **Endpoints**:
  - Discovery: `ServiceProviderConfig`, `ResourceTypes[/{id}]`, `Schemas[/{id}]`. These are public and return no tenant data.
  - `Users`:
    - list, with filter `userName eq "…"`, `startIndex` and `count`;
    - get, create, replace, PATCH, delete.
  - `Groups`:
    - list, with filter `displayName eq` and `excludedAttributes=members`;
    - get;
    - PATCH of members: add, remove `members[value eq "…"]`, remove with a value list, replace;
    - PUT, for members only;
    - POST and DELETE answer 501.
- **Storage**: the Keycloak Admin API, through the existing `KeycloakDirectory` admin client (the `astra-server` service account).
  - A tenant's SCIM users are the users its own SCIM client created. They are marked as direct members of `/tenants/<tenant>`, which grants no role.
  - SCIM Groups are the tenant's role groups `/tenants/<tenant>/<role>`, so group membership is exactly the tenant role that ends up in the token.
  - The "keep at least one admin" guard also applies to SCIM removals and deletes.
- **Authentication**: a client-credentials token from Keycloak client `scim-acme` or `scim-globex`, with secret `scim-<tenant>-local-secret`.
  - The token must carry the realm role `scim` and the `scim_tenant` claim. The claim comes from a hardcoded mapper on the client and is the only source of the tenant.
  - Cross-tenant access fails closed:
    - another tenant's users and groups answer 404;
    - a conflicting `X-Astra-Tenant` header answers 403;
    - adding a user the tenant does not own answers 400 `invalidValue`.
  - A normal user token answers 403. A request with no token answers 401 with `WWW-Authenticate: Bearer`.
  - The SCIM token has no group memberships, so it cannot reach tenant documents.
- **PATCH accepts the dialects real clients send**:
  - op names in any case (Entra sends `Replace`);
  - string booleans such as `"False"`;
  - a value object with no path (Okta style);
  - `emails[type eq "work"].value`.
- **Deactivation**: `active=false` disables the Keycloak account and ends its sessions.
- **Errors** follow RFC 7644 §3.12: `schemas` is `[…:Error]`, `status` is a string, plus `scimType` and `detail`. Every response, including errors, is `application/scim+json`.
- No database migration was needed, so there is no `V8__`.

## Files
| File | Change |
|------|--------|
| `deploy/keycloak/realm/acme-corp-idp-realm.json` | New: the demo SAML IdP realm. |
| `deploy/keycloak/realm/astra-realm.json` | Adds the realm role `scim`, clients `scim-acme` and `scim-globex` with their service-account users, and `identityProviders` and `identityProviderMappers`. Nothing else is touched. |
| `deploy/keycloak/Dockerfile` | Imports every realm file. |
| `scripts/keycloak-sync-realm.sh` | Creates missing realms, identity providers and mappers; grants service-account roles; points the SAML URLs at the public origin. |
| `scripts/keycloak-compose-dev.sh` | Allows plain HTTP for `acme-corp-idp` after the sync. |
| `astra-iam/src/main/java/io/astra/iam/ScimResource.java` | New: the JAX-RS resource, with its own SCIM error mappers. |
| `astra-iam/src/main/java/io/astra/iam/ScimDirectory.java` | New: tenant-scoped Keycloak operations. |
| `astra-iam/src/main/java/io/astra/iam/ScimPatch.java`, `ScimFilter.java`, `ScimException.java` | New: PATCH semantics, the filter parser, and SCIM errors. |
| `astra-iam/src/main/resources/scim/schemas.json` | New: User and Group schema definitions. |
| `astra-iam/src/main/java/io/astra/iam/KeycloakDirectory.java` | Four helpers changed from private to package-private. |
| `astra-iam/pom.xml` | Adds `junit-jupiter` at test scope. |
| `astra-iam/src/test/java/io/astra/iam/ScimFilterTest.java`, `ScimPatchTest.java` | New unit tests. |
| `astra-server/src/test/java/io/astra/server/KeycloakResource.java`, `KeycloakProfile.java` | New: a real Keycloak test container, same version as the Dockerfile, importing `deploy/keycloak/realm/*.json`. |
| `astra-server/src/test/java/io/astra/server/ScimTest.java`, `SamlBrokerTest.java`, `SamlBrowser.java` | New integration tests, plus a minimal SAML browser used by the SAML test. |
| `scripts/demo-sso.sh` | New: the scene-by-scene demo. |
| `Makefile` | New target `demo-sso`. |

Not touched: `README*`, `docs/**`, `scripts/demo-express.sh`, the application config in `astra-server/src/main`, and the root and server `pom.xml`.

## Test results
- **Full run**: `./mvnw -pl astra-server -am test -Dquarkus.http.test-port=0` gives **BUILD SUCCESS**.
  - astra-iam: 12 tests (`ScimFilterTest` 4, `ScimPatchTest` 8).
  - astra-server: 52 tests, 0 failures. That includes the new `ScimTest` (5) and `SamlBrokerTest` (3), plus every existing class (IamAccess, ExpressFlow, Seata, …).
  - The `-Dquarkus.http.test-port=0` flag only keeps the existing test classes off the shared port 8081 while other agents are running tests. The Keycloak profile always uses a free port.
- **`ScimTest.userLifecycleWithFilterRolesAndDeactivation`**:
  - create returns 201 with a `Location` header and no password in the body; a duplicate create returns 409 `uniqueness`;
  - `userName eq` filter (case-insensitive), list, get and PUT all work;
  - a token for the new user, before it has a role, gets 403 on `/api/doc`;
  - after a PATCH adding the user to group editor, `/api/iam/me` shows acme editor and the user can create an Article;
  - after PATCH `active` = `"False"`, the password grant fails with `invalid_grant` and the refresh token no longer works;
  - after removing the member and a DELETE (204), GET returns 404 with a SCIM error body.
- **`ScimTest.groupsAreTheTenantRoles`**: lists the 4 acme roles; `displayName eq` is case-insensitive; `excludedAttributes` works; renaming a group gives `mutability`; POST gives 501.
- **`ScimTest.tenantComesFromTheTokenAndCrossTenantAccessFailsClosed`**:
  - the globex token cannot get, filter, patch or delete acme's user;
  - it cannot add that user to a globex role (400) or read acme's group (404);
  - a header naming acme gives 403;
  - a normal user token gives 403; no token gives 401 with a SCIM body and `WWW-Authenticate`;
  - the SCIM token itself gets 403 on `/api/doc`.
- **`ScimTest.errorsUseTheScimErrorSchema`**: covers invalidSyntax (bad JSON, missing `schemas`), invalidValue, invalidFilter, 404, and PATCH without a value.
- **`ScimTest.discoveryEndpointsDescribeWhatIsSupported`**: checks the discovery endpoints.
- **`SamlBrokerTest.signedAssertionFromTheEnterpriseIdpBecomesATenantRole`**: runs the full SAML flow against the real Keycloak.
  - The assertion contains a `SignatureValue`.
  - The token has `preferred_username=acme-corp.linh`.
  - `/api/iam/me` shows acme `[editor]`, and an Article POST returns 201.
- **`SamlBrokerTest.anAlteredAssertionIsRejected`**: changing `astra-readers` to `astra-admins` in transit makes Keycloak answer 400 and issue no code.
- **`SamlBrokerTest.realmFilesDeclareSignedSamlAndTenantScopedScimClients`**: checks the realm JSON structure:
  - the signature flags are set;
  - every mapper target group exists and is under `/tenants/acme`;
  - the SCIM clients are client-credentials only;
  - their service accounts hold `scim`.

  The import itself is proven because the test container starts from these files.
- **Script syntax**: `bash -n` passes for `scripts/demo-sso.sh`, `scripts/keycloak-sync-realm.sh` and `scripts/keycloak-compose-dev.sh`.
- **Sync script, checked on a throwaway Keycloak**:
  - I deleted the IdP realm, the IdP, and the SCIM clients and role, then ran `keycloak-sync-realm.sh`. Everything was recreated, the `scim` role was granted, and the SAML flow worked again.
  - A second run changed nothing.
- **Full demo, end to end**: `PAUSE=0 scripts/demo-sso.sh` ran all 8 scenes and exited 0.
  - It ran against a throwaway Astra jar on `:18761`, with throwaway Keycloak, Postgres and Redis containers on random ports. All of these were stopped and removed afterwards.
  - The shared Compose and k3d stacks were not touched.

## Demo scenes (`make demo-sso`, or `scripts/demo-sso.sh [BASE] [SCENE...]`, `PAUSE=0`)
Before running on the shared stack, the controller needs to:
1. Rebuild or recreate Keycloak, or simply run `scripts/keycloak-compose-dev.sh` (which `make infra-up` runs). The running Compose Keycloak was imported before this change; the sync adds the new realm, IdP, mappers and SCIM clients.
2. Restart astra-server, so that `/scim/v2` exists.

Expected output, abridged from the real run:

1. **SAML SSO**
   - Replayed browser steps:
     1. `GET …/auth?client_id=astra-desk&kc_idp_hint=acme-corp`
     2. `303 → …/broker/acme-corp/login`
     3. `200 auto-post SAMLRequest → …/acme-corp-idp/protocol/saml`
     4. IdP login page, then `POST username=linh`
     5. `200 auto-post signed SAMLResponse → …/broker/acme-corp/endpoint`
     6. `302 first-broker-login → 302 → http://localhost:8080/desk/?code=…`
   - Decoded assertion: Issuer `…/realms/acme-corp-idp`, NameID `linh`, Audience `urn:astra:sp`, `memberOf astra-editors`, `Assertion signed yes, rsa-sha256`, `Response signed yes`.
   - Token claims: `preferred_username: acme-corp.linh`, `groups: ["/tenants/acme/editor"]`.
   - `GET /api/iam/me` → 200: acme roles `[editor]`, Article `write: all`.
   - `POST /api/doc/Article` → 201, `ART-…`.
2. **Altered assertion**
   - bao's SAMLResponse has `astra-readers` changed to `astra-admins` in transit.
   - Result: `400 Keycloak stopped the flow: Chữ ký không hợp lệ trong phản hồi từ nhà cung cấp danh tính.` No code is issued.
3. **SCIM discovery and token**
   - ServiceProviderConfig: `patch: true`, `filter {supported, maxResults: 200}`, `bulk: false`, `etag: false`, `auth: oauthbearertoken`.
   - ResourceTypes: User and Group.
   - Token claims: `azp: scim-acme`, `scim_tenant: acme`, roles include `scim`.
4. **Create and filter**
   - `POST /scim/v2/Users` → 201, `application/scim+json`, with `id`, `meta.location` and `groups: []`.
   - Filter `userName eq "CHI.SCIM-…"` → `totalResults 1`.
   - A second POST → 409 `uniqueness`.
   - The new user's own token: `/api/iam/me` shows `tenants: []`; `/api/doc/Article` → 403 "not a member of tenant acme".
5. **Group = role**
   - Groups list: admin, editor, author, reader.
   - PATCH editor to add the member → 200, and the member is listed.
   - After a fresh sign-in the token has `groups: ["/tenants/acme", "/tenants/acme/editor"]`.
   - `/api/iam/me` shows acme `[editor]`, and an Article POST → 201.
6. **Deactivate**
   - PATCH `{"op":"Replace","path":"active","value":"False"}` → `active: false`.
   - Password grant → `invalid_grant` "Account disabled".
   - Refresh token → `invalid_grant` "Session not active".
   - **The old access token still answers 200 until its `exp`**, which the scene prints (1 hour for the `astra-dev-cli` demo client).
7. **Fail closed**
   - The globex token gets 404 (SCIM error) for acme's user, and its filter returns `totalResults 0`.
   - With `X-Astra-Tenant: acme` it gets 403: "this SCIM token is bound to tenant globex and cannot act on acme".
   - Adding acme's user to a globex role → 400 `invalidValue`.
   - Filter `userName co "chi"` → 400 `invalidFilter`.
8. **Delete**: `DELETE` → 204, then GET → 404.

The SCIM user is deleted when the script exits. The brokered users `acme-corp.linh` and `acme-corp.bao` stay, like real federated accounts.

## Nói đúng (for video 21)

**SAML**
- The IdP is a Keycloak realm (`acme-corp-idp`) in the same container, not Okta, Entra or ADFS. The SAML protocol between them is real.
- Assertions and responses are signed with RSA-SHA256, and an altered assertion is rejected. Assertions are **not encrypted**, and AuthnRequests are **not signed**.
- Only SP-initiated sign-in (via `kc_idp_hint`) is set up. IdP-initiated SSO is not configured, and **Single Logout is neither wired nor tested**.
- On first login Keycloak creates the account `acme-corp.<username>` automatically. If the email already belongs to a local account, Keycloak asks to link the two, which the demo does not show.
- The group-to-role mapping is fixed in the realm config and only targets tenant `acme`. Another enterprise tenant needs its own IdP and mappers.
- The demo replays the browser with curl, not a real browser, and never follows the redirect back to the Desk.

**SCIM**
- **Not implemented**: Bulk, ETag/versioning (`meta.version`), sort, `/Me`, `meta.lastModified`, and the `attributes` parameter. `excludedAttributes` only works for `members` on Groups.
- **Filters are limited** to `userName eq "…"` on Users and `displayName eq "…"` on Groups. There is no `and`/`or`, `co`, `sw` or `pr`.
- `externalId` is ignored and not stored.
- Only one email is kept: the primary one, or the first.
- `displayName` is derived from the name.
- `userName` cannot be changed, because the realm sets `editUsernameAllowed=false`.
- PATCH `remove` on a user only supports `name.*` and `emails`.
- Groups are fixed, because they are the tenant's roles. Creating or deleting a group returns 501; only membership can change.
- A tenant's SCIM client only sees and changes the users it provisioned. Local members such as lan or minh are invisible to it.
- A SCIM delete removes the Keycloak account, even if another tenant later added that user.
- A role change takes effect at the **next sign-in or token refresh**.
- After `active=false`, new sign-ins and refreshes are blocked immediately. **An access token already issued stays valid until it expires**, because Astra verifies the JWT offline. That is 5 minutes for normal clients and 1 hour for the `astra-dev-cli` demo client.
- An invalid or expired token is rejected by the OIDC layer with a bare 401 and no SCIM body. A request with no token gets a SCIM-format 401.
- Listing users fetches the tenant's members from Keycloak and pages them in memory (up to 10,000), with one extra call per user to read their groups. That is fine for a demo, not for 100,000 users.
- The SCIM client secrets in the realm JSON are local development secrets.

## Concerns
- **Shared `~/.m2`**: early on I ran `./mvnw -pl astra-server -am install -DskipTests` once, and later ran `install` once for astra-iam. That put this branch's SNAPSHOT jars into the shared `~/.m2`.
  - Reactor builds with `-am` are unaffected.
  - A build of a single module without `-am` would pick up this astra-iam, which only adds the SCIM classes.
- **Possible merge conflict in `astra-realm.json`**: my changes are in the `roles.realm`, `clients` and `users` arrays, plus the new top-level `identityProviders` and `identityProviderMappers`. The other agents' `hr`/`dispatcher` additions may conflict textually; resolving it is mechanical.

Status: DONE
Summary: SAML SSO is brokered through Keycloak with a self-contained demo IdP realm. Assertions are signed and validated, `memberOf` maps to `/tenants/acme/<role>`, and the realms import on Compose and k3d. SCIM 2.0 is implemented in astra-iam on top of the Keycloak Admin API: the tenant comes from the token, cross-tenant access fails closed, and errors use the RFC 7644 format. All tests pass, including real-Keycloak integration tests (astra-server 52, astra-iam 12), and the 8-scene `make demo-sso` ran end to end.
Concerns: SNAPSHOT jars installed into the shared `~/.m2` (harmless); possible textual conflict in `astra-realm.json` with `hr`/`dispatcher`; the stack's Keycloak needs the sync (`scripts/keycloak-compose-dev.sh`) and astra-server needs a restart before `make demo-sso`.
Worktree: `/Users/bwork/Documents/AstraCMS/astra-cms/.claude/worktrees/agent-a08cdc6d86bc7ccf2`, branch `worktree-agent-a08cdc6d86bc7ccf2`.
