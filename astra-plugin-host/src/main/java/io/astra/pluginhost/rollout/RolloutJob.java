package io.astra.pluginhost.rollout;

import io.astra.pluginhost.jvm.IsolatedJvmHost;
import io.astra.spi.doc.Document;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs on every pod every two seconds: heartbeat, flush this pod's health counters into the releases, re-read the
 * releases of the tenants this pod serves, unload plugins nothing serves any more. Then, on the one pod holding the
 * PostgreSQL advisory lock, the promoter: a canary that took {@code promote_after} healthy calls per canary pod
 * gains the next pod, a canary on every pod becomes Live (the release it replaces is Retired), and a canary whose
 * error rate exceeds its {@code error_budget} is Rolled back, which sends its pods back to the previous release.
 */
@ApplicationScoped
public class RolloutJob {

    private static final Logger LOG = Logger.getLogger(RolloutJob.class);
    static final int MIN_CALLS_TO_JUDGE = 5;

    @Inject RolloutController controller;
    @Inject ReleaseStore store;
    @Inject IsolatedJvmHost jvm;

    private int leakChecks;

    @Scheduled(every = "${astra.rollout.every:2s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class)
    void tick() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.warnf("rollout tick failed: %s", e.toString());
        }
    }

    /** One tick; also called directly by tests. */
    public void run() {
        store.heartbeat(controller.pod());
        flushHealth();
        for (String tenant : controller.knownTenants()) {
            TenantContext.run(tenant, () -> controller.load(tenant));
        }
        controller.forgetUnreferenced();
        checkLeaks();
        store.ifPromoter(this::promote);
    }

    void flushHealth() {
        controller.health().forEach((key, h) -> {
            long[] window = h.drain();
            if (window[0] == 0) return;
            int slash = key.indexOf('/');
            String tenant = key.substring(0, slash);
            String release = key.substring(slash + 1);
            TenantContext.run(tenant, () -> store.addHealth(release, window[0], window[1], window[2] / 1000.0));
        });
    }

    void promote() {
        List<String> pods = store.livePods();
        Set<String> tenants = new LinkedHashSet<>(controller.knownTenants());
        try {
            tenants.addAll(store.tenants());
        } catch (RuntimeException e) {
            // without astra_ext.tenants() the promoter still covers the tenants this pod has served
        }
        for (String tenant : tenants) {
            TenantContext.run(tenant, () -> {
                List<Document> canaries = store.canaries();
                for (Document canary : canaries) judge(tenant, canary, pods);
                if (!canaries.isEmpty()) controller.invalidate();   // this pod acts on its own decisions at once
            });
        }
    }

    private void judge(String tenant, Document r, List<String> pods) {
        long calls = number(r.get("calls"));
        long errors = number(r.get("errors"));
        double budget = r.get("error_budget") == null ? 0.05 : ((Number) r.get("error_budget")).doubleValue();
        long promoteAfter = Math.max(1, number(r.get("promote_after")));
        List<String> canaryPods = new ArrayList<>(Arrays.stream(String.valueOf(r.getString("canary_pods")).split(","))
                .map(String::trim).filter(s -> !s.isEmpty() && !"null".equals(s)).toList());
        String id = r.name() + " " + r.getString("app_id") + "@" + r.getString("version");
        if (calls >= MIN_CALLS_TO_JUDGE && (double) errors / calls > budget) {
            String reason = String.format("error rate %.1f%% (%d of %d calls) exceeded budget %.1f%%", 100.0 * errors / calls,
                    errors, calls, 100 * budget);
            store.patch(r.name(), Map.of("stage", "Rolled back", "canary_pods", "", "stage_reason", reason));
            LOG.warnf("rollout %s: %s rolled back on pods %s: %s", tenant, id, canaryPods, reason);
            return;
        }
        canaryPods.retainAll(pods.isEmpty() ? canaryPods : pods);   // a canary pod that went away no longer counts
        if (calls < promoteAfter * Math.max(1, canaryPods.size())) return;
        List<String> next = pods.stream().filter(p -> !canaryPods.contains(p)).toList();
        if (next.isEmpty()) {
            Map<String, Object> live = new LinkedHashMap<>();
            live.put("stage", "Live");
            live.put("canary_pods", "");
            live.put("target_pods", pods.size() + "/" + pods.size());
            live.put("stage_reason", calls + " calls, " + errors + " errors on every pod");
            // Only retire the previous Live release if this one actually became Live: if an admin cancelled this canary
            // between reading it and now, the guarded patch hits no row and the old Live keeps serving the slot.
            if (!store.patchIf(r.name(), "Canary", live)) return;
            for (Document old : store.live(r.getString("slot"))) {
                if (!old.name().equals(r.name()) && AppReleaseHook.sameScope(r.getString("customer"), old.getString("customer"))) {
                    store.patch(old.name(), Map.of("stage", "Retired", "stage_reason", "replaced by " + id));
                    LOG.infof("rollout %s: %s %s@%s retired, replaced by %s", tenant, old.name(), old.getString("app_id"),
                            old.getString("version"), id);
                }
            }
            LOG.infof("rollout %s: %s is Live on %d/%d pods", tenant, id, pods.size(), pods.size());
        } else {
            canaryPods.add(next.getFirst());
            store.patch(r.name(), Map.of("canary_pods", String.join(",", canaryPods),
                    "target_pods", canaryPods.size() + "/" + pods.size(),
                    "stage_reason", calls + " healthy calls; canary extended to " + next.getFirst()));
            LOG.infof("rollout %s: %s canary on %d/%d pods %s", tenant, id, canaryPods.size(), pods.size(), canaryPods);
        }
    }

    /** After unloads, nudges the GC a few times so a collected class loader is reported promptly. */
    private void checkLeaks() {
        List<String> alive = jvm.leakCheck();
        if (alive.isEmpty()) {
            leakChecks = 0;
        } else if (leakChecks++ < 5) {
            System.gc();
        } else if (leakChecks == 6) {
            LOG.warnf("class loaders still reachable after unload: %s", alive);
        }
    }

    private static long number(Object v) {
        return v instanceof Number n ? n.longValue() : v == null ? 0 : Long.parseLong(v.toString());
    }
}
