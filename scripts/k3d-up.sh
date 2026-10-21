#!/usr/bin/env bash
# Create (or reuse) the local k3d cluster, build the server and Keycloak images, import, deploy, wait.
#
# ASTRA_PUBLIC_URL is the origin clients use for both the API and the login page (default
# http://localhost:8080). It becomes the token issuer, so set it to your LAN address when a physical
# phone on Wi-Fi must sign in, for example: ASTRA_PUBLIC_URL=http://192.168.1.20:8080 make k3d-up
set -euo pipefail
cd "$(dirname "$0")/.."

export PATH="$HOME/.local/bin:$PATH"
# The build targets Java 25; on macOS prefer an installed JDK 25 over an older JAVA_HOME.
if JDK25=$(/usr/libexec/java_home -v 25 2>/dev/null); then export JAVA_HOME="$JDK25"; fi
CLUSTER=astra
IMAGE=astra/server:dev
KC_IMAGE=astra/keycloak:dev
PUBLIC_URL="${ASTRA_PUBLIC_URL:-http://localhost:8080}"
PUBLIC_URL="${PUBLIC_URL%/}"

if ! k3d cluster list 2>/dev/null | grep -q "^${CLUSTER} "; then
  k3d cluster create --config deploy/k3d/k3d-config.yaml
else
  echo "k3d cluster '${CLUSTER}' already exists; reusing"
fi

./mvnw -q -DskipTests package
docker build -q -f astra-server/src/main/docker/Dockerfile.jvm -t "${IMAGE}" .
docker build -q -t "${KC_IMAGE}" deploy/keycloak
k3d image import "${IMAGE}" "${KC_IMAGE}" -c "${CLUSTER}"

kubectl apply -k deploy/k8s/base
kubectl -n astra create configmap astra-public \
  --from-literal=public-url="${PUBLIC_URL}" \
  --from-literal=oidc-issuer="${PUBLIC_URL}/auth/realms/astra" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n astra rollout status statefulset/postgres --timeout=180s
kubectl -n astra rollout status deployment/redis --timeout=120s
kubectl -n astra rollout restart deployment/keycloak       # pick up a re-imported image or a new public URL
kubectl -n astra rollout status deployment/keycloak --timeout=420s
kubectl -n astra rollout restart deployment/astra-server   # pick up a re-imported image with the same tag
kubectl -n astra rollout status deployment/astra-server --timeout=300s

echo
echo "Astra API:       ${PUBLIC_URL}/api   (try: scripts/demo-iam.sh ${PUBLIC_URL})"
echo "Login page:      ${PUBLIC_URL}/auth/realms/astra/account   (Astra theme; ${PUBLIC_URL}/auth itself is the master-realm admin console)"
echo "OIDC discovery:  ${PUBLIC_URL}/auth/realms/astra/.well-known/openid-configuration"
echo "Keycloak admin:  ${PUBLIC_URL}/auth/admin   (admin / see secret astra-keycloak)"
