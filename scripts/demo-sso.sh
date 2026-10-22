#!/usr/bin/env bash
# Scene-by-scene terminal demo of enterprise identity on Astra: SAML 2.0 single sign-on through Keycloak
# brokering (the demo IdP is the acme-corp-idp realm in the same Keycloak), and SCIM 2.0 provisioning of
# tenant users and roles at /scim/v2.
#   scripts/demo-sso.sh [BASE] [SCENE...]     e.g. scripts/demo-sso.sh http://localhost:8080 1 4
# PAUSE=0 runs without waiting for Enter. Everything is real: the browser steps of the SAML flow are
# replayed with curl (redirects, auto-posting forms, the IdP login form), and SCIM calls use a
# client-credentials token of the tenant's scim-acme Keycloak client. Users created here are deleted at the end.
if (( BASH_VERSINFO[0] < 5 )); then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [[ -x $b ]] && exec "$b" "$0" "$@"; done
  echo "Bash 5+ is required: brew install bash" >&2; exit 1
fi
set -uo pipefail
BASE="${1:-http://localhost:8080}"; shift || true
SCENES=("$@"); [[ ${#SCENES[@]} -eq 0 ]] && SCENES=(1 2 3 4 5 6 7 8)
PAUSE="${PAUSE:-1}"
cd "$(dirname "$0")/.."
source scripts/lib/demo-common.sh
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

KC="${ISSUER%/realms/astra}"                 # Keycloak base (…/auth), from Astra's advertised issuer
IDP_PASSWORD="${IDP_PASSWORD:-Astra@123}"    # password of linh and bao in deploy/keycloak/realm/acme-corp-idp-realm.json
REDIRECT="http://localhost:8080/desk/"       # an astra-desk redirect URI; the flow stops before following it
SCIM_USER="chi.scim-$(date +%s)"
SCIM_PASSWORD="Scim@$(date +%s)"
STATE="$(mktemp -d)"; JAR="$STATE/cookies"
SCIM_ID=""

title() { printf '\n\033[1;35m━━━ Scene %s · %s ━━━\033[0m\n' "$1" "$2"; }
say()   { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
step()  { printf '\033[2m  %s\033[0m\n' "$*"; }
wait_key() { [[ $PAUSE == 1 ]] && read -rsp $'\033[2m  [Enter]\033[0m' _ && echo; return 0; }
status() { if [[ $1 == 2* ]]; then printf '\033[1;32mHTTP %s\033[0m\n' "$1"; else printf '\033[1;31mHTTP %s\033[0m\n' "$1"; fi; }
claims() { local p; p="$(cut -d. -f2 <<<"$1" | tr '_-' '/+')"; while (( ${#p} % 4 )); do p+="="; done; base64 -d <<<"$p" 2>/dev/null; }
urlenc() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

cleanup() {
  if [[ -n $SCIM_ID && -n ${SCIM_TOKEN:-} ]]; then
    curl -sS -o /dev/null -X DELETE -H "Authorization: Bearer ${SCIM_TOKEN}" "${BASE}/scim/v2/Users/${SCIM_ID}" || true
  fi
  rm -rf "$STATE"
}
trap cleanup EXIT

# ---------- browser replay for the SAML flow ----------

# req METHOD URL [FORM-DATA] — one browser request with the cookie jar; sets CODE, LOC, BODY
req() {
  local out
  out="$(curl -sS -b "$JAR" -c "$JAR" -X "$1" "$2" ${3:+--data "$3"} -w $'\n%{http_code}\n%{redirect_url}|')"
  out="${out%|}"   # the trailing | keeps $(…) from eating an empty redirect URL
  LOC="${out##*$'\n'}"; out="${out%$'\n'*}"; CODE="${out##*$'\n'}"; BODY="${out%$'\n'*}"
}

# form — reads the first HTML form on stdin: line 1 its action, then name=url-encoded-value lines
form() {
  python3 -c '
import sys, html.parser, urllib.parse
class P(html.parser.HTMLParser):
    def __init__(s): super().__init__(); s.action = None; s.fields = []; s.done = False
    def handle_starttag(s, tag, a):
        a = dict(a)
        if s.done: return
        if tag == "form" and s.action is None: s.action = a.get("action")
        elif tag == "input" and s.action is not None and a.get("name"): s.fields.append((a["name"], a.get("value") or ""))
    def handle_endtag(s, tag):
        if tag == "form" and s.action is not None: s.done = True
p = P(); p.feed(sys.stdin.read())
print(p.action or "")
for k, v in p.fields: print(k + "=" + urllib.parse.quote(v, safe=""))
'
}

# assertion_summary — what the IdP asserted (stdin: the decoded SAMLResponse XML)
assertion_summary() {
  python3 -c '
import sys, xml.etree.ElementTree as ET
ns = {"p": "urn:oasis:names:tc:SAML:2.0:protocol", "a": "urn:oasis:names:tc:SAML:2.0:assertion", "d": "http://www.w3.org/2000/09/xmldsig#"}
r = ET.fromstring(sys.stdin.read()); a = r.find("a:Assertion", ns)
print("  Issuer          ", a.find("a:Issuer", ns).text)
print("  Subject NameID  ", a.find("a:Subject/a:NameID", ns).text)
print("  Audience        ", a.find("a:Conditions/a:AudienceRestriction/a:Audience", ns).text)
for at in a.findall("a:AttributeStatement/a:Attribute", ns):
    print("  attribute %-7s" % at.get("Name"), ", ".join(v.text or "" for v in at.findall("a:AttributeValue", ns)))
sig = a.find("d:Signature/d:SignedInfo/d:SignatureMethod", ns)
print("  Assertion signed", "yes, " + sig.get("Algorithm").rsplit("#", 1)[-1] if sig is not None else "NO")
print("  Response signed ", "yes" if r.find("d:Signature", ns) is not None else "no")
'
}

# saml_login USER [TAMPER] — replays the browser: Astra Desk → Keycloak broker → Acme Corp IdP login →
# signed SAMLResponse → broker → authorization code. TAMPER is a sed expression applied to the decoded
# SAMLResponse before it is posted back. Sets AUTH_CODE (empty on failure) and SAML_XML.
saml_login() {
  local user=$1 tamper=${2:-} verifier challenge url action data f
  : > "$JAR"; AUTH_CODE=""; SAML_XML=""
  verifier="$(openssl rand -hex 32)"
  challenge="$(printf '%s' "$verifier" | openssl dgst -binary -sha256 | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
  PKCE_VERIFIER=$verifier
  url="${KC}/realms/astra/protocol/openid-connect/auth?client_id=astra-desk&response_type=code&scope=openid"
  url+="&redirect_uri=$(urlenc "$REDIRECT")&code_challenge=${challenge}&code_challenge_method=S256&kc_idp_hint=acme-corp"
  step "GET  /realms/astra/protocol/openid-connect/auth?client_id=astra-desk&kc_idp_hint=acme-corp  (PKCE S256)"
  req GET "$url"
  for _ in $(seq 20); do
    if [[ $CODE == 3* ]]; then
      if [[ $LOC == "$REDIRECT"* ]]; then
        AUTH_CODE="$(python3 -c 'import sys, urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["code"][0])' "$LOC")"
        step "$CODE → ${REDIRECT}?code=…   (back at the Desk with an authorization code)"
        return 0
      fi
      step "$CODE → ${LOC%%\?*}"
      req GET "$LOC"; continue
    fi
    mapfile -t f < <(form <<<"$BODY")
    action="${f[0]:-}"; action="${action//&amp;/&}"
    data="$(IFS='&'; echo "${f[*]:1}")"
    if [[ $CODE == 200 && $data == *SAMLRequest=* ]]; then
      step "200  auto-post SAMLRequest (AuthnRequest from SP urn:astra:sp) → ${action}"
      req POST "$action" "$data"
    elif [[ $CODE == 200 && $data == *SAMLResponse=* ]]; then
      local b64; b64="$(sed -n 's/^SAMLResponse=//p' <<<"${data//&/$'\n'}")"
      SAML_XML="$(python3 -c 'import sys, base64, urllib.parse; print(base64.b64decode(urllib.parse.unquote(sys.argv[1])).decode())' "$b64")"
      if [[ -n $tamper ]]; then
        local forged; forged="$(sed "$tamper" <<<"$SAML_XML" | python3 -c 'import sys, base64, urllib.parse; print(urllib.parse.quote(base64.b64encode(sys.stdin.read().rstrip("\n").encode()).decode(), safe=""))')"
        data="$(sed "s|SAMLResponse=[^&]*|SAMLResponse=${forged}|" <<<"$data")"
        step "200  signed SAMLResponse received — altered in transit (${tamper}) → ${action}"
      else
        step "200  auto-post signed SAMLResponse → ${action}"
      fi
      req POST "$action" "$data"
    elif [[ $CODE == 200 && $BODY == *'name="password"'* ]]; then
      step "200  Acme Corp IdP login page → POST username=${user}"
      req POST "$action" "username=$(urlenc "$user")&password=$(urlenc "$IDP_PASSWORD")&credentialId="
    else
      step "${CODE}  Keycloak stopped the flow: $(python3 -c '
import sys, re, html
b = sys.stdin.read()
m = re.search(r"id=\"kc-error-message\".*?<p[^>]*>(.*?)</p>", b, re.S) or re.search(r"<title>(.*?)</title>", b, re.S)
print(html.unescape(re.sub(r"<[^>]+>", "", m.group(1))).strip() if m else "(no message)")' <<<"$BODY")"
      return 1
    fi
  done
  return 1
}

desk_token() {
  curl -sS "${KC}/realms/astra/protocol/openid-connect/token" -d grant_type=authorization_code -d client_id=astra-desk \
    --data-urlencode "code=${AUTH_CODE}" --data-urlencode "redirect_uri=${REDIRECT}" -d "code_verifier=${PKCE_VERIFIER}" \
    | jq -r .access_token
}

# ---------- SCIM ----------

scim_token() {
  curl -sfS "${KC}/realms/astra/protocol/openid-connect/token" -d grant_type=client_credentials \
    -d "client_id=$1" -d "client_secret=$1-local-secret" | jq -r .access_token
}

# scim TOKEN-NAME METHOD PATH [JSON] [JQ] [EXTRA-HEADER] — shows the call, status and (filtered) body; sets SC_CODE, SC_BODY
scim() {
  local who=$1 method=$2 path=$3 body=${4:-} filter=${5:-.} header=${6:-} token
  token="${!who}"
  printf '\033[2m$ curl -X %s %s%s -H "Authorization: Bearer <%s>"%s%s\033[0m\n' "$method" "$BASE" "$path" "$who" \
    "${header:+ -H \"$header\"}" "${body:+ -H \"Content-Type: application/scim+json\" -d '$body'}"
  local args=(-sS -X "$method" "${BASE}${path}" -H "Authorization: Bearer ${token}" -H "Accept: application/scim+json"
              -w $'\n%{http_code}\n%{content_type}|')
  [[ -n $body ]] && args+=(-H "Content-Type: application/scim+json; charset=utf-8" --data-binary "$body")
  [[ -n $header ]] && args+=(-H "$header")
  local out ct; out="$(curl "${args[@]}")"; out="${out%|}"
  ct="${out##*$'\n'}"; out="${out%$'\n'*}"; SC_CODE="${out##*$'\n'}"; SC_BODY="${out%$'\n'*}"
  printf '%s   \033[2m%s\033[0m\n' "$(status "$SC_CODE")" "$ct"
  [[ $SC_CODE == 2* ]] || filter=.
  [[ -n $SC_BODY ]] && { jq -C "$filter" <<<"$SC_BODY" 2>/dev/null || echo "$SC_BODY"; }
  return 0
}

# as_user TOKEN METHOD PATH [JSON] [JQ] — an Astra API call with a user's access token
as_user() {
  local token=$1 method=$2 path=$3 body=${4:-} filter=${5:-.}
  if [[ $method == GET ]] && (( $# == 4 )); then filter=$body; body=""; fi
  printf '\033[2m$ curl -X %s %s%s -H "Authorization: Bearer <token>" -H "X-Astra-Tenant: acme"%s\033[0m\n' "$method" "$BASE" "$path" \
    "${body:+ -d '$body'}"
  local args=(-sS -X "$method" "${BASE}${path}" -H "Authorization: Bearer ${token}" -H "X-Astra-Tenant: acme" -w $'\n%{http_code}')
  [[ -n $body ]] && args+=(-H "Content-Type: application/json" --data-binary "$body")
  local out; out="$(curl "${args[@]}")"; local code="${out##*$'\n'}"; out="${out%$'\n'*}"
  status "$code"; [[ $code == 2* ]] || filter=.
  [[ -n $out ]] && { jq -C "$filter" <<<"$out" 2>/dev/null || echo "$out"; }
  return 0
}

password_grant() {
  curl -sS "${ISSUER}/protocol/openid-connect/token" -d grant_type=password -d client_id=astra-dev-cli -d scope=openid \
    --data-urlencode "username=$1" --data-urlencode "password=$2"
}

ensure_scim_user() {
  [[ -n $SCIM_ID ]] && return 0
  SCIM_TOKEN="$(scim_token scim-acme)"
  SCIM_ID="$(curl -sS -H "Authorization: Bearer ${SCIM_TOKEN}" --get --data-urlencode "filter=userName eq \"${SCIM_USER}\"" \
    "${BASE}/scim/v2/Users" | jq -r '.Resources[0].id // empty')"
  if [[ -z $SCIM_ID ]]; then
    SCIM_ID="$(curl -sS -H "Authorization: Bearer ${SCIM_TOKEN}" -H "Content-Type: application/scim+json; charset=utf-8" \
      --data-binary "$(new_user)" "${BASE}/scim/v2/Users" | jq -r '.id // empty')"
  fi
  [[ -n $SCIM_ID ]] || { echo "could not provision ${SCIM_USER} through SCIM" >&2; exit 1; }
}

new_user() {
  jq -nc --arg u "$SCIM_USER" --arg p "$SCIM_PASSWORD" '{
    schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"], userName: $u,
    name: {givenName: "Chi", familyName: "Trần"}, emails: [{value: ($u + "@acme-corp.test"), type: "work", primary: true}],
    active: true, password: $p}'
}

group_id() {
  curl -sS -H "Authorization: Bearer ${SCIM_TOKEN}" --get --data-urlencode "filter=displayName eq \"$1\"" \
    --data-urlencode "excludedAttributes=members" "${BASE}/scim/v2/Groups" | jq -r '.Resources[0].id'
}

# ---------- scenes ----------

scene1() {
  title 1 "SAML SSO: an Acme Corp employee signs in with the company IdP"
  say "Astra's realm brokers identity provider 'acme-corp' (SAML 2.0, signed assertions required)"
  step "IdP realm acme-corp-idp: linh is in group astra-editors; the broker maps memberOf=astra-editors → /tenants/acme/editor"
  wait_key
  say "The browser flow, replayed step by step"
  saml_login linh || exit 1
  wait_key
  say "What the IdP asserted (decoded SAMLResponse)"
  assertion_summary <<<"$SAML_XML"; wait_key
  say "Authorization code → OIDC access token (PKCE): the SAML group arrived as a tenant role"
  DESK_TOKEN="$(desk_token)"
  claims "$DESK_TOKEN" | jq -C '{iss, preferred_username, email, groups}'; wait_key
  say "Astra trusts the token like any other: linh is an editor of tenant acme"
  as_user "$DESK_TOKEN" GET /api/iam/me '{username, tenants: [.tenants[] | {tenant, roles, Article: .permissions.Article}]}'
  as_user "$DESK_TOKEN" POST /api/doc/Article '{"title":"Viết qua Acme Corp SSO"}' '{name, owner, title: .data.title}'
  wait_key
}

scene2() {
  title 2 "SAML: an altered assertion is refused"
  say "bao is a reader. Rewrite his assertion in transit to claim astra-admins:"
  saml_login bao 's/astra-readers/astra-admins/g'
  if [[ -z $SAML_XML ]]; then
    echo "the flow did not reach the IdP's SAMLResponse" >&2; exit 1
  elif [[ -z $AUTH_CODE ]]; then
    say "No authorization code: the signature over the assertion no longer verifies, so nobody signs in"
  else
    echo "UNEXPECTED: Keycloak accepted an altered assertion" >&2; exit 1
  fi
  wait_key
}

scene3() {
  title 3 "SCIM 2.0: discovery and a tenant-bound token"
  say "The service provider describes itself (RFC 7644 §4)"
  SCIM_TOKEN="$(scim_token scim-acme)"
  scim SCIM_TOKEN GET /scim/v2/ServiceProviderConfig "" '{patch: .patch.supported, filter: .filter, bulk: .bulk.supported, etag: .etag.supported, auth: .authenticationSchemes[0].type}'
  scim SCIM_TOKEN GET /scim/v2/ResourceTypes "" '[.Resources[] | {id, endpoint, schema}]'; wait_key
  say "The provisioning client's token (client credentials of Keycloak client scim-acme)"
  claims "$SCIM_TOKEN" | jq -C '{azp, scim_tenant, roles: .realm_access.roles}'
  step "The tenant comes only from the scim_tenant claim, set by Keycloak for this client — never from the request."
  wait_key
}

scene4() {
  title 4 "SCIM: create a user, find it by filter"
  SCIM_TOKEN="$(scim_token scim-acme)"
  say "The enterprise directory pushes a new employee"
  scim SCIM_TOKEN POST /scim/v2/Users "$(new_user)" '{id, userName, name, emails, active, groups, meta}'
  SCIM_ID="$(jq -r '.id // empty' <<<"$SC_BODY")"
  [[ -n $SCIM_ID ]] || exit 1
  wait_key
  say "Before creating, clients look users up with a filter (userName is case-insensitive)"
  scim SCIM_TOKEN GET "/scim/v2/Users?filter=$(urlenc "userName eq \"${SCIM_USER^^}\"")" "" '{totalResults, ids: [.Resources[].id]}'
  say "Creating it twice is a uniqueness conflict"
  scim SCIM_TOKEN POST /scim/v2/Users "$(new_user)"; wait_key
  say "Signed in, ${SCIM_USER} exists but has no role in any tenant yet"
  local t; t="$(password_grant "$SCIM_USER" "$SCIM_PASSWORD" | jq -r .access_token)"
  as_user "$t" GET /api/iam/me '{username, tenants}'
  as_user "$t" GET /api/doc/Article
  wait_key
}

scene5() {
  title 5 "SCIM: group membership is the tenant role"
  ensure_scim_user
  say "Groups are the tenant's roles"
  scim SCIM_TOKEN GET "/scim/v2/Groups?excludedAttributes=members" "" '[.Resources[] | {id, displayName}]'
  local editor; editor="$(group_id editor)"; wait_key
  say "Add ${SCIM_USER} to group editor (PATCH, RFC 7644 §3.5.2)"
  scim SCIM_TOKEN PATCH "/scim/v2/Groups/${editor}" \
    "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"${SCIM_ID}\"}]}]}" \
    '{displayName, members}'
  wait_key
  say "A fresh sign-in carries the role; Astra applies the editor's DocType permissions"
  USER_TOKENS="$(password_grant "$SCIM_USER" "$SCIM_PASSWORD")"
  USER_TOKEN="$(jq -r .access_token <<<"$USER_TOKENS")"
  claims "$USER_TOKEN" | jq -C '{preferred_username, groups}'
  as_user "$USER_TOKEN" GET /api/iam/me '{username, tenants: [.tenants[] | {tenant, roles}]}'
  as_user "$USER_TOKEN" POST /api/doc/Article '{"title":"Bài viết của nhân viên mới"}' '{name, owner, title: .data.title}'
  wait_key
}

scene6() {
  title 6 "SCIM: deactivate (active=false)"
  ensure_scim_user
  [[ -n ${USER_TOKENS:-} ]] || USER_TOKENS="$(password_grant "$SCIM_USER" "$SCIM_PASSWORD")"
  say "The employee leaves; the directory sends PATCH active=false (Entra style: \"Replace\", \"False\")"
  scim SCIM_TOKEN PATCH "/scim/v2/Users/${SCIM_ID}" \
    '{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[{"op":"Replace","path":"active","value":"False"}]}' \
    '{userName, active}'
  wait_key
  say "Sign-in is refused at once"
  password_grant "$SCIM_USER" "$SCIM_PASSWORD" | jq -C .
  say "Sessions were ended: the refresh token no longer works"
  curl -sS "${ISSUER}/protocol/openid-connect/token" -d grant_type=refresh_token -d client_id=astra-dev-cli \
    --data-urlencode "refresh_token=$(jq -r .refresh_token <<<"$USER_TOKENS")" | jq -C .
  say "An access token already issued stays valid until it expires (Astra verifies tokens offline)"
  as_user "$(jq -r .access_token <<<"$USER_TOKENS")" GET /api/iam/me '{username, tenants: [.tenants[] | {tenant, roles}]}'
  claims "$(jq -r .access_token <<<"$USER_TOKENS")" | jq -C '{expires_at: (.exp | todate)}'
  wait_key
}

scene7() {
  title 7 "SCIM: another tenant's token fails closed"
  ensure_scim_user
  GLOBEX_TOKEN="$(scim_token scim-globex)"
  say "Globex's provisioning client asks for Acme's user"
  scim GLOBEX_TOKEN GET "/scim/v2/Users/${SCIM_ID}"
  scim GLOBEX_TOKEN GET "/scim/v2/Users?filter=$(urlenc "userName eq \"${SCIM_USER}\"")" "" '{totalResults}'
  say "…or claims to act for acme"
  scim GLOBEX_TOKEN GET /scim/v2/Users "" . "X-Astra-Tenant: acme"
  say "…or tries to pull Acme's user into a Globex role"
  local editor; editor="$(curl -sS -H "Authorization: Bearer ${GLOBEX_TOKEN}" --get --data-urlencode 'filter=displayName eq "editor"' \
    "${BASE}/scim/v2/Groups" | jq -r '.Resources[0].id')"
  scim GLOBEX_TOKEN PATCH "/scim/v2/Groups/${editor}" \
    "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"${SCIM_ID}\"}]}]}"
  say "Errors follow RFC 7644 §3.12: an unsupported filter"
  scim SCIM_TOKEN GET "/scim/v2/Users?filter=$(urlenc 'userName co "chi"')"
  wait_key
}

scene8() {
  title 8 "SCIM: delete"
  ensure_scim_user
  scim SCIM_TOKEN DELETE "/scim/v2/Users/${SCIM_ID}"
  scim SCIM_TOKEN GET "/scim/v2/Users/${SCIM_ID}"
  SCIM_ID=""
  wait_key
}

for s in "${SCENES[@]}"; do
  declare -F "scene$s" >/dev/null || { echo "no scene $s (1-8)" >&2; exit 1; }
  "scene$s"
done
printf '\n\033[1;32mDone.\033[0m\n'
