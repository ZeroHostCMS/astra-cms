#!/usr/bin/env bash
# Prepares the Keycloak of the Docker Compose dev stack (http://localhost:8180) for host clients:
#  1. Lets the master and astra realms accept plain HTTP. The realm file says sslRequired=external, which
#     exempts only loopback and private IPv4 clients; Docker Desktop forwards host traffic from an address
#     Keycloak counts as external, so every sign-in from the host fails with "HTTPS required". The change is
#     made with kcadm inside the container (a loopback client) and touches only this throwaway dev-mode
#     Keycloak; the k3d cluster and the realm file keep sslRequired=external.
#  2. Syncs the realm file into it with the Compose admin password (keycloak-sync-realm.sh would otherwise
#     prefer the k3d cluster's admin secret when kubectl is installed).
#   scripts/keycloak-compose-dev.sh   (run after `docker compose … up`; `make infra-up` does it)
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=deploy/docker/docker-compose.yml
CONTAINER="$(docker compose -f "$COMPOSE" ps -q keycloak)"
[[ -n $CONTAINER ]] || { echo "the Compose keycloak service is not running" >&2; exit 1; }
ADMIN_PASSWORD="$(sed -nE 's/^[[:space:]]*KC_BOOTSTRAP_ADMIN_PASSWORD:[[:space:]]*"?([^"]*)"?[[:space:]]*$/\1/p' "$COMPOSE" | head -1)"
kcadm() { docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

for _ in $(seq 60); do   # the container reports healthy before the admin API answers on a cold start
  kcadm config credentials --server http://localhost:8080/auth --realm master --user admin \
    --password "$ADMIN_PASSWORD" >/dev/null 2>&1 && break
  sleep 2
done
for realm in master astra; do
  kcadm get "realms/$realm" --fields realm >/dev/null 2>&1 || continue
  kcadm update "realms/$realm" -s sslRequired=NONE
done
echo "Compose Keycloak: master and astra accept plain HTTP from the host (dev only)."
KC_ADMIN_PASSWORD="$ADMIN_PASSWORD" scripts/keycloak-sync-realm.sh http://localhost:8180
