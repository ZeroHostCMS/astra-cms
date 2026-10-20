#!/usr/bin/env bash
# Create (or reuse) the local k3d cluster, build the server image, import it, deploy, wait.
set -euo pipefail
cd "$(dirname "$0")/.."

export PATH="$HOME/.local/bin:$PATH"
CLUSTER=astra
IMAGE=astra/server:dev

if ! k3d cluster list 2>/dev/null | grep -q "^${CLUSTER} "; then
  k3d cluster create --config deploy/k3d/k3d-config.yaml
else
  echo "k3d cluster '${CLUSTER}' already exists; reusing"
fi

./mvnw -q -DskipTests package
docker build -q -f astra-server/src/main/docker/Dockerfile.jvm -t "${IMAGE}" .
k3d image import "${IMAGE}" -c "${CLUSTER}"

kubectl apply -k deploy/k8s/base
kubectl -n astra rollout status statefulset/postgres --timeout=180s
kubectl -n astra rollout status deployment/redis --timeout=120s
kubectl -n astra rollout restart deployment/astra-server   # pick up a re-imported image with the same tag
kubectl -n astra rollout status deployment/astra-server --timeout=300s

echo
echo "Astra is reachable at http://localhost:8080  (try: scripts/demo.sh http://localhost:8080)"
