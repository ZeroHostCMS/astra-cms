#!/usr/bin/env bash
# Adds what deploy/keycloak/realm/astra-realm.json declares but the running realm lacks (clients, groups,
# users, roles, identity providers and their mappers), merges the file's client attributes into existing
# clients, creates the other realms in deploy/keycloak/realm (the demo SAML IdP) when missing, and points
# SAML brokering at the public origin BASE. Keycloak imports the realm
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

# Keycloak creates a client's service-account user itself when the client is imported, so the partial import
# above skips the file's entry for it; grant the realm roles the file lists (for example scim) explicitly.
while IFS= read -r u; do
  cid="$(kc GET "${KC}/clients?clientId=$(jq -r '.serviceAccountClientId | @uri' <<<"$u")" | jq -r '.[0].id // empty')"
  [[ -n $cid ]] || continue
  uid="$(kc GET "${KC}/clients/${cid}/service-account-user" | jq -r .id)"
  reps="$(kc GET "${KC}/roles?briefRepresentation=true&max=1000" | jq -c --argjson want "$(jq -c .realmRoles <<<"$u")" \
    '[.[] | select(.name as $n | $want | index($n))]')"
  kc POST "${KC}/users/${uid}/role-mappings/realm" "$reps" >/dev/null
done < <(jq -c '.users[] | select(.serviceAccountClientId != null and .realmRoles != null)' "$REALM_FILE")
echo "service accounts: realm roles in step with the realm file"

# Other realm files (the acme-corp-idp demo SAML IdP): created whole when the realm is missing.
for f in deploy/keycloak/realm/*.json; do
  [[ $f == "$REALM_FILE" ]] && continue
  name="$(jq -r .realm "$f")"
  kc GET "${BASE}/auth/admin/realms/${name}" >/dev/null
  if [[ $KC_CODE == 404 ]]; then kc POST "${BASE}/auth/admin/realms" "$(jq -c . "$f")" >/dev/null; echo "realm ${name}: created"
  else echo "realm ${name}: present"; fi
done

# Identity providers and their mappers: added when missing (existing ones are left as they are).
while IFS= read -r idp; do
  alias="$(jq -r .alias <<<"$idp")"
  kc GET "${KC}/identity-provider/instances/${alias}" >/dev/null
  if [[ $KC_CODE == 404 ]]; then kc POST "${KC}/identity-provider/instances" "$idp" >/dev/null; echo "identity provider ${alias}: created"; fi
  have="$(kc GET "${KC}/identity-provider/instances/${alias}/mappers" | jq -c '[.[].name]')"
  while IFS= read -r m; do
    jq -e --arg n "$(jq -r .name <<<"$m")" 'index($n) != null' <<<"$have" >/dev/null && continue
    kc POST "${KC}/identity-provider/instances/${alias}/mappers" "$m" >/dev/null
    echo "identity provider mapper '$(jq -r .name <<<"$m")': created"
  done < <(jq -c --arg a "$alias" '.identityProviderMappers[]? | select(.identityProviderAlias == $a)' "$REALM_FILE")
done < <(jq -c '.identityProviders[]?' "$REALM_FILE")

# SAML brokers: the browser-facing IdP endpoints and issuer follow the public origin BASE (the realm file holds
# http://localhost:8080), and the IdP's SP client accepts the broker endpoint on that origin. The metadata
# descriptor URL, from which the broker loads the IdP's signing key, stays on Keycloak's in-container port.
while IFS= read -r alias; do
  cur="$(kc GET "${KC}/identity-provider/instances/${alias}")"
  idp_realm="$(jq -r '.config.singleSignOnServiceUrl | capture("/realms/(?<r>[^/]+)/").r' <<<"$cur")"
  issuer="${BASE}/auth/realms/${idp_realm}"
  kc PUT "${KC}/identity-provider/instances/${alias}" "$(jq -c --arg i "$issuer" \
    '.config.idpEntityId = $i | .config.singleSignOnServiceUrl = ($i + "/protocol/saml")' <<<"$cur")" >/dev/null
  sp="$(kc GET "${BASE}/auth/admin/realms/${idp_realm}/clients?clientId=$(jq -r '.config.entityId | @uri' <<<"$cur")" | jq -c '.[0] // empty')"
  if [[ -n $sp ]]; then
    kc PUT "${BASE}/auth/admin/realms/${idp_realm}/clients/$(jq -r .id <<<"$sp")" "$(jq -c \
      --arg u "${BASE}/auth/realms/astra/broker/${alias}/endpoint*" '.redirectUris = ((.redirectUris // []) + [$u] | unique)' <<<"$sp")" >/dev/null
  fi
  echo "SAML broker ${alias}: IdP ${issuer}, broker endpoint ${BASE}/auth/realms/astra/broker/${alias}/endpoint"
done < <(jq -r '.identityProviders[]? | select(.providerId == "saml") | .alias' "$REALM_FILE")
