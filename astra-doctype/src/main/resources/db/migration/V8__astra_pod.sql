-- Kernel pods that are alive, for canary rollout across pods: each pod upserts its row every few seconds,
-- and a pod not seen for a while no longer counts towards "all pods". Cluster state, not tenant data: no RLS.
CREATE TABLE astra_pod (
    name     text        PRIMARY KEY,
    seen_at  timestamptz NOT NULL DEFAULT now()
);
