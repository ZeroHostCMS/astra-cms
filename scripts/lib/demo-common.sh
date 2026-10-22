# Shared helpers for the demo scripts. Requires Bash 5; each script re-executes itself under it first.
# Identity comes from Keycloak: tokens are issued to the realm's demo users through the local-only
# astra-dev-cli client (password grant), exactly as scripts/demo-iam.sh does.

BASE="${BASE%/}"
DEMO_PASSWORD="${DEMO_PASSWORD:-Astra@123}"     # password of the demo users in deploy/keycloak/realm
declare -gA TOKENS=() TOKEN_EXPIRES=()

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
# Wait for Astra (a rollout may still be draining old pods behind the ingress).
for _ in $(seq 60); do
  ISSUER="$(curl -sf "${BASE}/api/iam/config" | jq -r .issuer 2>/dev/null || true)"
  [[ -n $ISSUER && $ISSUER != null ]] && break
  sleep 2
done
[[ -n $ISSUER && $ISSUER != null ]] || { echo "Astra is not reachable at ${BASE}" >&2; exit 1; }

# login USER — caches an access token for USER in TOKENS[USER], refreshing it shortly before it expires
login() {
  local user=$1
  if [[ -n ${TOKENS[$user]:-} ]] && (( EPOCHSECONDS < TOKEN_EXPIRES[$user] )); then return; fi
  local res
  res="$(curl -sfS "${ISSUER}/protocol/openid-connect/token" -d grant_type=password -d client_id=astra-dev-cli \
         -d scope=openid --data-urlencode "username=${user}" --data-urlencode "password=${DEMO_PASSWORD}")" \
    || { echo "cannot sign in as ${user} at ${ISSUER}" >&2; exit 1; }
  TOKENS[$user]="$(jq -r .access_token <<<"$res")"
  TOKEN_EXPIRES[$user]=$(( EPOCHSECONDS + $(jq -r .expires_in <<<"$res") - 20 ))
}

# http USER TENANT METHOD PATH [BODY] — sets HTTP_CODE, HTTP_BODY and TX_LSN (X-Tx-LSN of a write); TENANT may be
# empty; CT overrides the JSON body type; REQUIRED_LSN sends X-Required-LSN
http() {
  local user=$1 tenant=$2 method=$3 path=$4 body=${5:-}
  login "$user"
  local args=(-sS -w $'\n%{http_code}\n%header{x-tx-lsn}|' -X "$method" "${BASE}${path}" -H "Authorization: Bearer ${TOKENS[$user]}")
  [[ -n ${REQUIRED_LSN:-} ]] && args+=(-H "X-Required-LSN: ${REQUIRED_LSN}")
  [[ -n $tenant ]] && args+=(-H "X-Astra-Tenant: ${tenant}")
  [[ -n $body ]] && args+=(-H "Content-Type: ${CT:-application/json}" --data-binary "$body")
  local out attempt
  for attempt in $(seq 15); do   # 502/503: the ingress found no ready pod (a rollout is draining), so nothing ran yet
    out="$(curl "${args[@]}")"
    out="${out%|}"   # the trailing | keeps $(…) from eating an empty last line
    TX_LSN="${out##*$'\n'}"; out="${out%$'\n'*}"
    HTTP_CODE="${out##*$'\n'}"
    HTTP_BODY="${out%$'\n'*}"
    [[ $HTTP_CODE == 502 || $HTTP_CODE == 503 ]] || break
    sleep 2
  done
}
