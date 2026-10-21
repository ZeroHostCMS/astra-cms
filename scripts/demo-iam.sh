#!/usr/bin/env bash
# End-to-end IAM check against a running stack (make k3d-up): real Keycloak tokens, tenant
# membership, DocType permissions, member provisioning, self-join and tenant administration.
# Tokens come from the local-only astra-dev-cli client (password grant); the mobile app uses
# Authorization Code + PKCE instead. Exits non-zero on the first failed expectation.
set -euo pipefail
BASE="${1:-http://localhost:8080}"
BASE="${BASE%/}"
REALM_URL="${BASE}/auth/realms/astra"
PASSWORD='Astra@123'
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-$(kubectl -n astra get secret astra-keycloak -o jsonpath='{.data.admin-password}' 2>/dev/null | base64 -d || echo astra-local-admin)}"
RUN="$(date +%s)"

say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
pass() { printf '\033[32m  ok\033[0m  %s\n' "$*"; }
fail() { printf '\033[31m  FAIL\033[0m %s\n' "$*"; exit 1; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

# token <username> [password]  -> access token on stdout
token() {
  curl -sS -f "${REALM_URL}/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=astra-dev-cli -d scope=openid \
    --data-urlencode "username=$1" --data-urlencode "password=${2:-$PASSWORD}" | json "d['access_token']"
}

# expect <status> <description> <curl args...>; the body is left in $BODY
expect() {
  local want="$1" what="$2"; shift 2
  local out; out="$(curl -sS -w $'\n%{http_code}' "$@")"
  BODY="${out%$'\n'*}"; local got="${out##*$'\n'}"
  [[ "$got" == "$want" ]] && pass "$what (HTTP $got)" || fail "$what: expected HTTP $want, got $got: $BODY"
}

kc_admin_token() {
  curl -sS -f "${BASE}/auth/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli -d username=admin \
    --data-urlencode "password=${KC_ADMIN_PASSWORD}" | json "d['access_token']"
}

CREATED_USERS=()
CREATED_TENANT=""
cleanup() {
  local t; t="$(kc_admin_token 2>/dev/null)" || return 0
  for u in "${CREATED_USERS[@]:-}"; do
    [[ -n "$u" ]] || continue
    local id; id="$(curl -sS -H "Authorization: Bearer $t" "${BASE}/auth/admin/realms/astra/users?exact=true&username=$u" | json "d[0]['id'] if d else ''")"
    [[ -n "$id" ]] && curl -sS -o /dev/null -X DELETE -H "Authorization: Bearer $t" "${BASE}/auth/admin/realms/astra/users/$id"
  done
  if [[ -n "$CREATED_TENANT" ]]; then
    local gid; gid="$(curl -sS -H "Authorization: Bearer $t" "${BASE}/auth/admin/realms/astra/group-by-path/tenants/${CREATED_TENANT}" | json "d.get('id','')" 2>/dev/null || true)"
    [[ -n "$gid" ]] && curl -sS -o /dev/null -X DELETE -H "Authorization: Bearer $t" "${BASE}/auth/admin/realms/astra/groups/$gid"
  fi
  echo; echo "cleaned up demo users and tenant"
}
trap cleanup EXIT

say "Public bootstrap config and OIDC discovery"
expect 200 "GET /api/iam/config is public" "${BASE}/api/iam/config"
ISSUER="$(echo "$BODY" | json "d['issuer']")"
[[ "$ISSUER" == "${REALM_URL}" ]] && pass "advertised issuer is ${ISSUER}" || fail "issuer ${ISSUER} != ${REALM_URL}"
expect 200 "discovery document" "${REALM_URL}/.well-known/openid-configuration"
[[ "$(echo "$BODY" | json "d['issuer']")" == "${REALM_URL}" ]] && pass "discovery issuer matches" || fail "discovery issuer mismatch"
expect 200 "login page renders with the Astra theme" "${REALM_URL}/protocol/openid-connect/auth?client_id=astra-mobile&response_type=code&scope=openid&redirect_uri=io.astra.mobile:/oauthredirect&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&ui_locales=vi"
grep -q 'astra' <<<"$BODY" && pass "page served by the astra theme" || fail "astra theme markers not found"

say "Authentication is required"
expect 401 "anonymous document list" -H "X-Astra-Tenant: acme" "${BASE}/api/doc/Article"
expect 401 "garbage bearer token" -H "Authorization: Bearer not-a-token" "${BASE}/api/iam/me"

say "Tokens for seeded users"
LAN="$(token lan)";   pass "lan    (acme admin)"
MINH="$(token minh)"; pass "minh   (acme editor)"
HOA="$(token hoa)";   pass "hoa    (acme author)"
AN="$(token an)";     pass "an     (acme reader)"
JOHN="$(token john)"; pass "john   (globex admin)"
PLATFORM="$(token platform)"; pass "platform (platform-admin)"

say "Who am I"
expect 200 "lan /api/iam/me" -H "Authorization: Bearer $LAN" "${BASE}/api/iam/me"
[[ "$(echo "$BODY" | json "[t['roles'] for t in d['tenants'] if t['tenant']=='acme'][0]")" == "['admin']" ]] && pass "lan is admin of acme" || fail "lan memberships: $BODY"
[[ "$(echo "$BODY" | json "d['tenants'][0]['permissions']['Article']['write']")" == "all" ]] && pass "lan may write all Articles" || fail "lan permissions: $BODY"

say "DocType permissions per tenant role"
expect 201 "lan creates an Article without naming the tenant (single membership)" \
  -H "Authorization: Bearer $LAN" -H "Content-Type: application/json" -d '{"title":"Chào mừng Astra IAM"}' "${BASE}/api/doc/Article"
LAN_DOC="$(echo "$BODY" | json "d['name']")"
expect 200 "editor minh edits lan's Article" -X PUT -H "Authorization: Bearer $MINH" -H "Content-Type: application/json" \
  -d '{"status":"Published"}' "${BASE}/api/doc/Article/${LAN_DOC}"
expect 403 "author hoa cannot edit lan's Article" -X PUT -H "Authorization: Bearer $HOA" -H "Content-Type: application/json" \
  -d '{"body":"x"}' "${BASE}/api/doc/Article/${LAN_DOC}"
expect 201 "author hoa creates her own Article" -H "Authorization: Bearer $HOA" -H "Content-Type: application/json" \
  -d '{"title":"Bài của Hoa"}' "${BASE}/api/doc/Article"
HOA_DOC="$(echo "$BODY" | json "d['name']")"
expect 200 "author hoa edits her own Article" -X PUT -H "Authorization: Bearer $HOA" -H "Content-Type: application/json" \
  -d '{"body":"đã sửa"}' "${BASE}/api/doc/Article/${HOA_DOC}"
expect 200 "reader an lists Articles" -H "Authorization: Bearer $AN" "${BASE}/api/doc/Article"
expect 403 "reader an cannot create" -H "Authorization: Bearer $AN" -H "Content-Type: application/json" \
  -d '{"title":"nope"}' "${BASE}/api/doc/Article"

say "Tenant boundary"
expect 403 "john (globex) asks for acme" -H "Authorization: Bearer $JOHN" -H "X-Astra-Tenant: acme" "${BASE}/api/doc/Article"
expect 403 "platform admin has no implicit document access" -H "Authorization: Bearer $PLATFORM" -H "X-Astra-Tenant: acme" "${BASE}/api/doc/Article"

say "Tenant admin provisions staff"
STAFF="staff-${RUN}"; CREATED_USERS+=("$STAFF")
expect 201 "lan adds new user ${STAFF} as author (temporary password)" -H "Authorization: Bearer $LAN" -H "Content-Type: application/json" \
  -d "{\"username\":\"${STAFF}\",\"email\":\"${STAFF}@acme.test\",\"firstName\":\"Staff\",\"lastName\":\"Demo\",\"role\":\"author\",\"temporaryPassword\":\"Temp@${RUN}\"}" \
  "${BASE}/api/iam/tenants/acme/members"
STAFF_ID="$(echo "$BODY" | json "d['userId']")"
expect 200 "lan promotes ${STAFF} to editor" -X PUT -H "Authorization: Bearer $LAN" -H "Content-Type: application/json" \
  -d '{"role":"editor"}' "${BASE}/api/iam/tenants/acme/members/${STAFF_ID}"
[[ "$(echo "$BODY" | json "d['role']")" == "editor" ]] && pass "role is now editor" || fail "role change: $BODY"
expect 200 "lan lists acme members" -H "Authorization: Bearer $LAN" "${BASE}/api/iam/tenants/acme/members"
echo "$BODY" | json "[m['username'] for m in d]" | grep -q "$STAFF" && pass "${STAFF} is listed" || fail "member list: $BODY"
curl -sS "${REALM_URL}/protocol/openid-connect/token" -d grant_type=password -d client_id=astra-dev-cli \
  --data-urlencode "username=${STAFF}" --data-urlencode "password=Temp@${RUN}" | grep -q "not fully set up" \
  && pass "temporary password forces a password change at first login" || fail "temporary password was usable directly"
expect 403 "editor minh cannot manage members" -H "Authorization: Bearer $MINH" "${BASE}/api/iam/tenants/acme/members"
expect 403 "john cannot manage acme members" -H "Authorization: Bearer $JOHN" "${BASE}/api/iam/tenants/acme/members"
expect 409 "lan cannot demote the last acme admin (herself)" -X PUT -H "Authorization: Bearer $LAN" -H "Content-Type: application/json" \
  -d '{"role":"reader"}' "${BASE}/api/iam/tenants/acme/members/$(echo "$(curl -sS -H "Authorization: Bearer $LAN" "${BASE}/api/iam/me")" | json "d['id']")"
expect 204 "lan removes ${STAFF} from acme" -X DELETE -H "Authorization: Bearer $LAN" "${BASE}/api/iam/tenants/acme/members/${STAFF_ID}"

say "Public user self-registers, then joins a public tenant"
PUB="reader-${RUN}"; CREATED_USERS+=("$PUB")
KC="$(kc_admin_token)"
# Equivalent of submitting the themed registration form: a user with no tenant membership.
curl -sS -f -o /dev/null -H "Authorization: Bearer $KC" -H "Content-Type: application/json" "${BASE}/auth/admin/realms/astra/users" \
  -d "{\"username\":\"${PUB}\",\"email\":\"${PUB}@example.test\",\"firstName\":\"Public\",\"lastName\":\"Reader\",\"enabled\":true,\"emailVerified\":true,\"credentials\":[{\"type\":\"password\",\"value\":\"${PASSWORD}\",\"temporary\":false}]}"
PUBT="$(token "$PUB")"; pass "registered ${PUB} signs in"
expect 200 "${PUB} has no tenants yet" -H "Authorization: Bearer $PUBT" "${BASE}/api/iam/me"
[[ "$(echo "$BODY" | json "len(d['tenants'])")" == "0" ]] && pass "no memberships" || fail "$BODY"
expect 200 "${PUB} lists joinable tenants" -H "Authorization: Bearer $PUBT" "${BASE}/api/iam/tenants/public"
[[ "$(echo "$BODY" | json "[t['id'] for t in d]")" == "['acme']" ]] && pass "only acme allows public sign-up" || fail "$BODY"
expect 403 "${PUB} cannot join globex (no public sign-up)" -X POST -H "Authorization: Bearer $PUBT" "${BASE}/api/iam/tenants/globex/join"
expect 200 "${PUB} joins acme" -X POST -H "Authorization: Bearer $PUBT" "${BASE}/api/iam/tenants/acme/join"
[[ "$(echo "$BODY" | json "(d['role'], d['refreshToken'])")" == "('reader', True)" ]] && pass "joined as reader; client must refresh its token" || fail "$BODY"
expect 403 "the old token still predates the join" -H "Authorization: Bearer $PUBT" -H "X-Astra-Tenant: acme" "${BASE}/api/doc/Article"
PUBT="$(token "$PUB")"
expect 200 "after refresh ${PUB} reads acme Articles" -H "Authorization: Bearer $PUBT" "${BASE}/api/doc/Article"
expect 403 "and still cannot create" -H "Authorization: Bearer $PUBT" -H "Content-Type: application/json" -d '{"title":"x"}' "${BASE}/api/doc/Article"

say "Platform admin manages tenants"
CREATED_TENANT="demo-${RUN}"
expect 403 "lan cannot create tenants" -H "Authorization: Bearer $LAN" -H "Content-Type: application/json" \
  -d "{\"id\":\"${CREATED_TENANT}\"}" "${BASE}/api/iam/tenants"
expect 201 "platform creates ${CREATED_TENANT}" -H "Authorization: Bearer $PLATFORM" -H "Content-Type: application/json" \
  -d "{\"id\":\"${CREATED_TENANT}\",\"name\":\"Demo ${RUN}\",\"publicSignup\":false}" "${BASE}/api/iam/tenants"
expect 409 "creating it twice conflicts" -H "Authorization: Bearer $PLATFORM" -H "Content-Type: application/json" \
  -d "{\"id\":\"${CREATED_TENANT}\"}" "${BASE}/api/iam/tenants"
expect 200 "platform opens ${CREATED_TENANT} to public sign-up" -X PUT -H "Authorization: Bearer $PLATFORM" -H "Content-Type: application/json" \
  -d '{"publicSignup":true}' "${BASE}/api/iam/tenants/${CREATED_TENANT}"
expect 200 "lan sees only her own tenants" -H "Authorization: Bearer $LAN" "${BASE}/api/iam/tenants"
[[ "$(echo "$BODY" | json "[t['id'] for t in d]")" == "['acme']" ]] && pass "lan: [acme]" || fail "$BODY"

say "All IAM checks passed"
