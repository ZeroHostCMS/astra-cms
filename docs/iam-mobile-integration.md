# IAM: integrating the Flutter app

How the mobile app signs users in to Astra and calls the API. The local stack (`make k3d-up`) serves the
API and the Keycloak login page from one origin, `http://localhost:8080` by default.

## What the app talks to

| Item | Value (local stack) |
| --- | --- |
| Bootstrap config (public) | `GET {BASE}/api/iam/config` |
| Issuer | `{BASE}/auth/realms/astra` |
| Discovery | `{BASE}/auth/realms/astra/.well-known/openid-configuration` |
| Client id | `astra-mobile` (public client, no secret) |
| Flow | Authorization Code + PKCE (S256) in the system browser. Password grant is disabled for this client. |
| Redirect URI | `io.astra.mobile:/oauthredirect` (also the post-logout redirect) |
| Scopes | `openid profile email`, plus `offline_access` for "stay signed in" |
| Languages | `vi` (default) and `en`. Pass `ui_locales` to pick one. |
| API auth | `Authorization: Bearer <access token>` |
| Tenant | `X-Astra-Tenant: <tenant>`. This header is optional when the user belongs to exactly one tenant. |

`{BASE}` is the value of `ASTRA_PUBLIC_URL` that the cluster was started with. Keycloak writes it into every
token as the issuer, and the server rejects tokens from any other issuer. The app must therefore reach the
stack through exactly that origin.

## Reaching the laptop from a device

| Device | Setup |
| --- | --- |
| iOS simulator | Works as is with `http://localhost:8080`. |
| Android emulator or USB phone | Run `adb reverse tcp:8080 tcp:8080`, then use `http://localhost:8080`. |
| Phone on Wi-Fi | Run `ASTRA_PUBLIC_URL=http://<laptop LAN IP>:8080 make k3d-up`, then use that URL in the app. |

The local stack is plain HTTP. Allow it in debug builds only:

* **Android:** pass `allowInsecureConnections: true` to `flutter_appauth` requests, and allow cleartext
  traffic to the dev host with a debug-only `network_security_config.xml`.
* **iOS:** add `NSAppTransportSecurity` → `NSAllowsLocalNetworking = YES` to the debug `Info.plist`. If
  token calls to a LAN IP are still blocked, add a debug-only exception for that host.

## Flutter setup

```yaml
# pubspec.yaml
dependencies:
  flutter_appauth: ^12.1.0
  flutter_secure_storage: ^11.2.0
  http: ^1.6.0
```

Android, `android/app/build.gradle`:

```groovy
android {
  defaultConfig {
    manifestPlaceholders += [appAuthRedirectScheme: 'io.astra.mobile']
  }
}
```

iOS, `ios/Runner/Info.plist`:

```xml
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleURLSchemes</key>
    <array><string>io.astra.mobile</string></array>
  </dict>
</array>
```

## Sign in, register, refresh, sign out

```dart
import 'dart:convert';
import 'package:flutter_appauth/flutter_appauth.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

class AstraAuth {
  AstraAuth(this.base); // e.g. 'http://localhost:8080'
  final String base;
  final _appAuth = const FlutterAppAuth();
  final _store = const FlutterSecureStorage();

  static const clientId = 'astra-mobile';
  static const redirect = 'io.astra.mobile:/oauthredirect';
  static const scopes = ['openid', 'profile', 'email', 'offline_access'];
  String get discovery => '$base/auth/realms/astra/.well-known/openid-configuration';

  /// Opens the Astra login page. Set [register] to open the sign-up form instead.
  Future<void> signIn({String locale = 'vi', bool register = false}) async {
    final r = await _appAuth.authorizeAndExchangeCode(AuthorizationTokenRequest(
      clientId, redirect,
      discoveryUrl: discovery,
      scopes: scopes,
      additionalParameters: {
        'ui_locales': locale,
        if (register) 'prompt': 'create',
      },
      allowInsecureConnections: true, // local HTTP stack only
    ));
    await _save(r.accessToken, r.refreshToken, r.idToken);
  }

  /// Exchanges the refresh token. Call on 401, and after joining a tenant or a role change.
  Future<bool> refresh() async {
    final rt = await _store.read(key: 'refresh_token');
    if (rt == null) return false;
    try {
      final r = await _appAuth.token(TokenRequest(clientId, redirect,
          discoveryUrl: discovery, refreshToken: rt, scopes: scopes,
          allowInsecureConnections: true));
      await _save(r.accessToken, r.refreshToken ?? rt, r.idToken);
      return true;
    } catch (_) {
      await _store.deleteAll(); // refresh token expired or revoked: sign in again
      return false;
    }
  }

  Future<void> signOut() async {
    final idToken = await _store.read(key: 'id_token');
    await _appAuth.endSession(EndSessionRequest(
      idTokenHint: idToken,
      postLogoutRedirectUrl: redirect,
      discoveryUrl: discovery,
      allowInsecureConnections: true,
    ));
    await _store.deleteAll();
  }

  Future<String?> accessToken() => _store.read(key: 'access_token');

  Future<void> _save(String? at, String? rt, String? idt) async {
    await _store.write(key: 'access_token', value: at);
    await _store.write(key: 'refresh_token', value: rt);
    await _store.write(key: 'id_token', value: idt);
  }
}

/// Calls the Astra API with the bearer token and tenant header; retries once after a refresh on 401.
class AstraApi {
  AstraApi(this.auth, {this.tenant});
  final AstraAuth auth;
  String? tenant;

  Future<http.Response> send(String method, String path, {Object? body}) async {
    Future<http.Response> once() async {
      final req = http.Request(method, Uri.parse('${auth.base}$path'))
        ..headers['Authorization'] = 'Bearer ${await auth.accessToken()}'
        ..headers['Content-Type'] = 'application/json';
      if (tenant != null) req.headers['X-Astra-Tenant'] = tenant!;
      if (body != null) req.body = jsonEncode(body);
      return http.Response.fromStream(await req.send());
    }
    var res = await once();
    if (res.statusCode == 401 && await auth.refresh()) res = await once();
    return res;
  }
}
```

## After sign-in

1. Call `GET /api/iam/me`. It returns the user's profile, `platformAdmin`, and a `tenants` list. Each entry
   holds the user's `roles` in that tenant and the effective `permissions` per DocType, for example
   `{"Article": {"read": true, "create": true, "write": "own"}}`. Use these to show or hide actions. The
   server enforces the same rules either way.
2. If `tenants` is empty, the user just self-registered. Join the app's tenant with
   `POST /api/iam/tenants/{tenant}/join`, or let the user pick one from `GET /api/iam/tenants/public`.
   The user joins as `reader`. The response contains `"refreshToken": true`. Call `refresh()` before using
   the tenant, because the current access token was issued before the join.
3. If the user belongs to several tenants, send `X-Astra-Tenant` on every document call.

Role changes made by a tenant admin reach the user's token at the next refresh, within 5 minutes.

## API reference

| Method and path | Who | Purpose |
| --- | --- | --- |
| `GET /api/iam/config` | anyone | Issuer, discovery URL, client id, redirect URI, scopes, locales |
| `GET /api/iam/me` | signed in | Profile, memberships, effective permissions |
| `GET /api/iam/tenants` | signed in | Tenants the caller belongs to (all of them for a platform admin) |
| `GET /api/iam/tenants/public` | signed in | Tenants open to self-join |
| `POST /api/iam/tenants/{t}/join` | signed in | Join a public tenant as `reader`. Repeating it is harmless. |
| `GET /api/iam/tenants/{t}/members` | tenant admin, platform admin | List members and their roles |
| `POST /api/iam/tenants/{t}/members` | tenant admin, platform admin | `{username, email, firstName, lastName, role, temporaryPassword}`. Creates the user if new. The temporary password must be changed at first login, on the themed page. |
| `PUT /api/iam/tenants/{t}/members/{userId}` | tenant admin, platform admin | `{role}`. Each user has one role per tenant. |
| `DELETE /api/iam/tenants/{t}/members/{userId}` | tenant admin, platform admin | Remove the user from the tenant. The last admin cannot be removed or demoted (409). |
| `POST /api/iam/tenants` | platform admin | `{id, name, publicSignup}`. Creates the tenant and its role groups. |
| `PUT /api/iam/tenants/{t}` | platform admin | `{name, publicSignup}` |
| `/api/doc/{DocType}` | tenant member | Generated document API, checked against the DocType's `permissions` |

Tenant roles are `admin`, `editor`, `author` and `reader`. For `Article`, admins and editors can read,
create and write everything. Authors can read and create, and can write only their own documents. Readers
can only read. A platform admin manages tenants and members but gets no document access unless also made a
member.

Errors are JSON `{"error": "..."}`:

| Status | Meaning |
| --- | --- |
| 401 | Token missing, expired, or from the wrong issuer |
| 403 | Not a member of the tenant, or the role does not allow the action |
| 400 | Tenant could not be resolved, or the request was invalid |
| 409 | Conflict, such as a duplicate username, an existing tenant, or the last admin |

## Seeded local accounts

All seeded accounts use the password `Astra@123`. They exist only in the local realm export.

| Username | Access |
| --- | --- |
| `platform` | platform admin |
| `lan` | acme admin |
| `minh` | acme editor |
| `hoa` | acme author |
| `an` | acme reader |
| `john` | globex admin |

`acme` allows public sign-up and `globex` does not. The Keycloak admin console is at `{BASE}/auth/admin`
(user `admin`; the password is in the `astra-keycloak` secret).

## Not in this release

Forgot-password by email (no SMTP yet), MFA, social login, and filtering drafts out of readers' lists.

## Troubleshooting

* **"Invalid parameter: redirect_uri":** the app's redirect must be exactly `io.astra.mobile:/oauthredirect`.
* **401 on every API call right after sign-in:** the app reached the stack through a different origin than
  `ASTRA_PUBLIC_URL`, so the token issuer does not match. Compare `iss` in the token with `/api/iam/config`.
* **403 "not a member of tenant ..." after joining:** refresh the token first.
* **Run the checks:** `scripts/demo-iam.sh http://localhost:8080` exercises all of the above with real tokens.
