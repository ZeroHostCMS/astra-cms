#!/usr/bin/env bash
# Smoke test against a running Astra: metadata, two tenants, hooks, naming series, isolation.
# Signs in as lan (admin of acme) and john (admin of globex) through Keycloak; see scripts/demo-iam.sh
# for the IAM checks themselves.
set -euo pipefail
BASE="${1:-http://localhost:8080}"
BASE="${BASE%/}"

token() {
  curl -sS -f "${BASE}/auth/realms/astra/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=astra-dev-cli -d scope=openid \
    --data-urlencode "username=$1" --data-urlencode "password=Astra@123" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])"
}
ACME="Authorization: Bearer $(token lan)"
GLOBEX="Authorization: Bearer $(token john)"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
req() { curl -sS -H "Content-Type: application/json" "$@"; echo; }

say "Health"
curl -sS "${BASE}/q/health/ready" ; echo

say "DocTypes registered by installed apps"
req "${BASE}/api/doctype" -H "$ACME" | cut -c1-600

say "Tenant acme creates an Article (slug hook + ART- naming series run inside the kernel)"
req -X POST "${BASE}/api/doc/Article" -H "$ACME" \
    -d '{"title":"Xin chào Đà Nẵng","body":"Bài viết đầu tiên","tags":["demo","vi"]}'

say "Tenant globex creates its own Article (its series starts at 00001 too: counters are per tenant)"
req -X POST "${BASE}/api/doc/Article" -H "$GLOBEX" \
    -d '{"title":"Hello from Globex","status":"Published"}'

say "acme sees only acme"
req "${BASE}/api/doc/Article" -H "$ACME"

say "globex sees only globex"
req "${BASE}/api/doc/Article" -H "$GLOBEX"

say "acme creates a second Article; acme always has more documents than globex, so its newest name is unique to acme"
ACME_NAME=$(req -X POST "${BASE}/api/doc/Article" -H "$ACME" -d '{"title":"Second post"}' \
  | sed -E 's/.*"name":"([^"]+)".*/\1/')
echo "acme's newest document: ${ACME_NAME}"

say "globex reads ART-00001: gets ITS OWN document, never acme's (row-level security)"
req "${BASE}/api/doc/Article/ART-00001" -H "$GLOBEX" | cut -c1-160

say "globex reads acme's ${ACME_NAME} by name (expect 404)"
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' "${BASE}/api/doc/Article/${ACME_NAME}" -H "$GLOBEX"

say "Validation: unknown field and bad Select value (expect 422)"
req -X POST "${BASE}/api/doc/Article" -H "$ACME" -d '{"title":"x","status":"Bogus","nope":1}'

say "No token (expect 401)"
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' "${BASE}/api/doc/Article"

say "lan asks for globex, where she is not a member (expect 403)"
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' "${BASE}/api/doc/Article" -H "$ACME" -H "X-Astra-Tenant: globex"
