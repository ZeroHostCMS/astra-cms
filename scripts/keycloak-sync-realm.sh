#!/usr/bin/env bash
# Adds what deploy/keycloak/realm/astra-realm.json declares but the running realm lacks (clients, groups,
# users, roles) and merges the file's client attributes into existing clients. Keycloak imports the realm
# file only when the realm does not exist yet; this keeps a persistent local realm in step with the file
# without deleting anything that already exists.
#   scripts/keycloak-sync-realm.sh [BASE]   (default http://localhost:8080, Keycloak under /auth)
if (( BASH_VERSINFO[0] < 5 )); then
  for b in /opt/homebrew/bin/bash /usr/local/bin/bash; do [[ -x $b ]] && exec "$b" "$0" "$@"; done
  echo "Bash 5+ is required: brew install bash" >&2; exit 1
fi
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${1:-http://localhost:8080}"; BASE="${BASE%/}"
REALM_FILE=deploy/keycloak/realm/astra-realm.json
KC="${BASE}/auth/admin/realms/astra"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-$(kubectl -n astra get secret astra-keycloak -o jsonpath='{.data.admin-password}' 2>/dev/null | base64 -d || echo astra-local-admin)}"
ADMIN_TOKEN=""

# kc METHOD URL [BODY] — Keycloak admin call; prints the body, sets KC_CODE. Retries while the ingress
# answers 502/503 (a restarted Keycloak pod is draining); fails on any other error except 404.
kc() {
  local method=$1 url=$2 body=${3:-} out
  local args=(-sS -w $'\n%{http_code}' -X "$method" "$url" -H "Authorization: Bearer ${ADMIN_TOKEN}" -H "Content-Type: application/json")
  [[ -n $body ]] && args+=(--data-binary "$body")
  for _ in $(seq 60); do
    out="$(curl "${args[@]}")"; KC_CODE="${out##*$'\n'}"
    [[ $KC_CODE == 502 || $KC_CODE == 503 ]] || break
    sleep 2
  done
  if [[ $KC_CODE != 2* && $KC_CODE != 404 ]]; then echo "Keycloak ${method} ${url}: HTTP ${KC_CODE}: ${out%$'\n'*}" >&2; exit 1; fi
  printf '%s' "${out%$'\n'*}"
}

# Wait for the admin API: Keycloak may have just restarted behind the ingress.
for _ in $(seq 90); do
  ADMIN_TOKEN="$(curl -sf "${BASE}/auth/realms/master/protocol/openid-connect/token" -d grant_type=password -d client_id=admin-cli \
    -d username=admin --data-urlencode "password=${KC_ADMIN_PASSWORD}" | jq -r .access_token 2>/dev/null || true)"
  [[ -n $ADMIN_TOKEN && $ADMIN_TOKEN != null ]] && curl -sf -o /dev/null -H "Authorization: Bearer ${ADMIN_TOKEN}" "$KC" && break
  ADMIN_TOKEN=""; sleep 2
done
[[ -n $ADMIN_TOKEN ]] || { echo "Keycloak admin API not reachable at ${BASE}/auth" >&2; exit 1; }

# Clients and roles: partial import skips the ones that exist.
kc POST "${KC}/partialImport" "$(jq -c '{ifResourceExists: "SKIP", clients, roles}' "$REALM_FILE")" \
  | jq -r '"clients and roles: \(.added) added, \(.skipped) already present"'

# Client attributes the file sets (for example token lifespans) are merged into existing clients too.
while IFS= read -r c; do
  id="$(kc GET "${KC}/clients?clientId=$(jq -r .clientId <<<"$c")" | jq -r '.[0].id // empty')"
  [[ -n $id ]] || continue
  kc PUT "${KC}/clients/${id}" "$(kc GET "${KC}/clients/${id}" | jq -c --argjson a "$(jq -c '.attributes // {}' <<<"$c")" '.attributes += $a')" >/dev/null
done < <(jq -c '.clients[]' "$REALM_FILE")
echo "client attributes: in step with the realm file"

# Groups: partial import rejects a tree whose root exists, so walk the tree and create missing paths.
ensure_group() { # ensure_group PARENT_ID PATH JSON
  local parent=$1 path=$2 json=$3 found id="" child
  found="$(kc GET "${KC}/group-by-path${path}")"   # a 404 body carries an error, not an id
  id="$(jq -r '.id // empty' <<<"$found" 2>/dev/null || true)"
  if [[ -z $id ]]; then
    local body; body="$(jq -c '{name, attributes: (.attributes // {})}' <<<"$json")"
    if [[ -z $parent ]]; then kc POST "${KC}/groups" "$body" >/dev/null
    else kc POST "${KC}/groups/${parent}/children" "$body" >/dev/null; fi
    id="$(kc GET "${KC}/group-by-path${path}" | jq -r .id)"
    echo "group ${path}: created"
  fi
  while IFS= read -r child; do
    [[ -n $child ]] && ensure_group "$id" "${path}/$(jq -r .name <<<"$child")" "$child"
  done < <(jq -c '.subGroups[]?' <<<"$json")
}
while IFS= read -r g; do ensure_group "" "/$(jq -r .name <<<"$g")" "$g"; done < <(jq -c '.groups[]' "$REALM_FILE")

# Users last, so their group paths resolve.
kc POST "${KC}/partialImport" "$(jq -c '{ifResourceExists: "SKIP", users}' "$REALM_FILE")" \
  | jq -r '"users: \(.added) added, \(.skipped) already present"'
