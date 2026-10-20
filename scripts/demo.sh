#!/usr/bin/env bash
# Smoke test against a running Astra: metadata, two tenants, hooks, naming series, isolation.
set -euo pipefail
BASE="${1:-http://localhost:8080}"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
req() { curl -sS -H "Content-Type: application/json" "$@"; echo; }

say "Health"
curl -sS "${BASE}/q/health/ready" ; echo

say "DocTypes registered by installed apps"
req "${BASE}/api/doctype" -H "X-Astra-Tenant: acme" | cut -c1-600

say "Tenant acme creates an Article (slug hook + ART- naming series run inside the kernel)"
req -X POST "${BASE}/api/doc/Article" -H "X-Astra-Tenant: acme" -H "X-Astra-User: lan" \
    -d '{"title":"Xin chào Đà Nẵng","body":"Bài viết đầu tiên","tags":["demo","vi"]}'

say "Tenant globex creates its own Article (its series starts at 00001 too: counters are per tenant)"
req -X POST "${BASE}/api/doc/Article" -H "X-Astra-Tenant: globex" \
    -d '{"title":"Hello from Globex","status":"Published"}'

say "acme sees only acme"
req "${BASE}/api/doc/Article" -H "X-Astra-Tenant: acme"

say "globex sees only globex"
req "${BASE}/api/doc/Article" -H "X-Astra-Tenant: globex"

say "acme creates a second Article; acme always has more documents than globex, so its newest name is unique to acme"
ACME_NAME=$(req -X POST "${BASE}/api/doc/Article" -H "X-Astra-Tenant: acme" -d '{"title":"Second post"}' \
  | sed -E 's/.*"name":"([^"]+)".*/\1/')
echo "acme's newest document: ${ACME_NAME}"

say "globex reads ART-00001: gets ITS OWN document, never acme's (row-level security)"
req "${BASE}/api/doc/Article/ART-00001" -H "X-Astra-Tenant: globex" | cut -c1-160

say "globex reads acme's ${ACME_NAME} by name (expect 404)"
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' "${BASE}/api/doc/Article/${ACME_NAME}" -H "X-Astra-Tenant: globex"

say "Validation: unknown field and bad Select value (expect 422)"
req -X POST "${BASE}/api/doc/Article" -H "X-Astra-Tenant: acme" -d '{"title":"x","status":"Bogus","nope":1}'

say "No tenant header in multi mode (expect 400)"
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' "${BASE}/api/doc/Article"
