package io.astra.pluginhost.rollout;

import io.astra.kernel.cluster.PodIdentity;
import io.astra.pluginhost.WasmSandbox;
import io.astra.pluginhost.jvm.IsolatedJvmHost;
import io.astra.pluginhost.jvm.LoadedPlugin;
import io.astra.spi.doc.Document;
import io.astra.spi.release.Releases;
import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Decides, per call, which approved release serves a slot on this pod, and counts each release's health. Each pod
 * keeps the serving releases and pins of every tenant it has seen in memory and re-reads them from PostgreSQL every
 * two seconds (and on first use after boot); there is no message bus, so a stage change reaches every pod within one
 * read interval.
 */
@ApplicationScoped
public class RolloutController implements Releases {

    private static final Logger LOG = Logger.getLogger(RolloutController.class);
    static final long REFRESH_NANOS = 2_000_000_000L;

    @Inject PodIdentity pod;
    @Inject ReleaseStore store;
    @Inject IsolatedJvmHost jvm;

    private final Map<String, TenantState> tenants = new ConcurrentHashMap<>();
    private final Map<String, byte[]> artifacts = new ConcurrentHashMap<>();   // by SHA-256
    private final Map<String, View> views = new ConcurrentHashMap<>();         // tenant/release → the plugin and its SPI proxy
    private final Map<String, Health> health = new ConcurrentHashMap<>();      // tenant/release → this pod's counters

    /** A cached SPI proxy and the plugin behind it, so a view whose plugin was unloaded is rebuilt on next use. */
    private record View(LoadedPlugin plugin, Object proxy) { }

    /** An approved release as the controller needs it. */
    record Rel(String name, String slot, String tier, String app, String version, String customer, String stage,
               Set<String> canaryPods, String sha256, String artifact) { }

    record Pin(String slot, String customer, String app, String version) { }

    record TenantState(long loadedAt, List<Rel> releases, List<Pin> pins) { }

    @Override
    public String pod() { return pod.name(); }

    @Override
    public <T> Optional<Bound<T>> plugin(String slot, String customer, Class<T> spi) {
        String tenant = TenantContext.require();
        return resolve(slot, customer).filter(r -> "isolated-jvm".equals(r.tier())).map(r -> {
            // Rebuild the view if it was never made or its plugin has since been unloaded (otherwise its MethodHandles
            // are cleared and every call would fail "has no method"); jvm.load returns the live instance for the SHA.
            View view = views.compute(tenant + "/" + r.name(), (k, cached) -> cached != null && !cached.plugin().stopped()
                    ? cached
                    : newView(jvm.load(bytes(r)), spi, tenant, r.name()));
            return new Bound<>(r.name(), r.app(), r.version(), pod.name(), spi.cast(view.proxy()));
        });
    }

    @Override
    public Optional<Artifact> artifact(String slot, String customer) {
        return resolve(slot, customer).filter(r -> !"isolated-jvm".equals(r.tier()))
                .map(r -> new Artifact(r.name(), r.app(), r.version(), r.tier(), r.sha256(), bytes(r), pod.name()));
    }

    @Override
    public void record(Artifact artifact, boolean ok, long nanos) {
        health(TenantContext.require(), artifact.release()).record(ok, nanos);
    }

    /**
     * Pin for the customer, pin for the tenant, then canary (when this pod is a canary pod) or live. Releases scoped
     * to a customer take precedence for that customer's calls and are never used for anyone else's.
     */
    Optional<Rel> resolve(String slot, String customer) {
        TenantState state = state(TenantContext.require());
        boolean forCustomer = customer != null && !customer.isBlank();
        for (Pin pin : state.pins()) {
            if (pin.slot().equals(slot) && forCustomer && customer.equals(pin.customer())) return pinned(state, pin);
        }
        for (Pin pin : state.pins()) {
            if (pin.slot().equals(slot) && blank(pin.customer())) {
                Optional<Rel> r = pinned(state, pin);
                if (r.isPresent()) return r;
            }
        }
        List<Rel> inSlot = state.releases().stream().filter(r -> r.slot().equals(slot)).toList();
        List<Rel> scoped = forCustomer ? inSlot.stream().filter(r -> customer.equals(r.customer())).toList() : List.of();
        List<Rel> candidates = !scoped.isEmpty() ? scoped : inSlot.stream().filter(r -> blank(r.customer())).toList();
        Optional<Rel> canary = candidates.stream()
                .filter(r -> "Canary".equals(r.stage()) && r.canaryPods().contains(pod.name()))
                .max(Comparator.comparing(Rel::name));
        if (canary.isPresent()) return canary;
        return candidates.stream().filter(r -> "Live".equals(r.stage())).max(Comparator.comparing(Rel::name));
    }

    private <T> View newView(LoadedPlugin plugin, Class<T> spi, String tenant, String release) {
        return new View(plugin, plugin.as(spi, (p, ok, reason, nanos) -> health(tenant, release).record(ok, nanos)));
    }

    private static Optional<Rel> pinned(TenantState state, Pin pin) {
        return state.releases().stream()
                .filter(r -> r.slot().equals(pin.slot()) && pin.app().equals(r.app()) && pin.version().equals(r.version()))
                .max(Comparator.comparing(Rel::name));
    }

    TenantState state(String tenant) {
        TenantState s = tenants.get(tenant);
        if (s == null || System.nanoTime() - s.loadedAt() > REFRESH_NANOS) s = load(tenant);
        return s;
    }

    /** Reads the tenant's serving releases and pins from PostgreSQL (the caller is in the tenant's scope). */
    TenantState load(String tenant) {
        List<Rel> releases = store.serving().stream().map(RolloutController::rel).toList();
        List<Pin> pins = store.pins().stream()
                .map(p -> new Pin(p.getString("slot"), emptyToNull(p.getString("customer")), p.getString("app_id"), p.getString("version")))
                .toList();
        TenantState s = new TenantState(System.nanoTime(), releases, pins);
        TenantState before = tenants.put(tenant, s);
        if (before != null) logChanges(tenant, before, s);
        return s;
    }

    /**
     * Marks the current tenant's cached state stale, so the next call re-reads it (after a submit on this pod). The
     * tenant stays in {@link #knownTenants()} and its releases stay referenced, so the rollout job keeps judging it and
     * does not unload the plugins it still serves — the state is refreshed, not dropped.
     */
    void invalidate() {
        tenants.computeIfPresent(TenantContext.require(), (t, s) -> new TenantState(0, s.releases(), s.pins()));
    }

    Set<String> knownTenants() {
        return Set.copyOf(tenants.keySet());
    }

    /** SHA-256 of every artifact a cached tenant may still call. */
    Set<String> referencedArtifacts() {
        return tenants.values().stream().flatMap(t -> t.releases().stream()).map(Rel::sha256)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** Drops the SPI views and artifact bytes of releases no cached tenant serves any more. */
    void forgetUnreferenced() {
        Set<String> live = new HashSet<>();
        tenants.forEach((tenant, s) -> s.releases().forEach(r -> live.add(tenant + "/" + r.name())));
        views.keySet().removeIf(k -> !live.contains(k));
        Set<String> shas = referencedArtifacts();
        artifacts.keySet().removeIf(k -> !shas.contains(k));
        for (LoadedPlugin p : jvm.loaded()) {
            if (!shas.contains(p.sha256())) {
                Thread.ofVirtual().name("unload-" + p.identity()).start(() -> jvm.unload(p, java.time.Duration.ofSeconds(2)));
            }
        }
    }

    Map<String, Health> health() {
        return health;
    }

    Health health(String tenant, String release) {
        return health.computeIfAbsent(tenant + "/" + release, k -> new Health());
    }

    private byte[] bytes(Rel r) {
        return artifacts.computeIfAbsent(r.sha256(), k -> Base64.getDecoder().decode(r.artifact()));
    }

    private void logChanges(String tenant, TenantState before, TenantState after) {
        Map<String, String> was = before.releases().stream().collect(Collectors.toMap(Rel::name, r -> r.stage() + r.canaryPods(), (a, b) -> a));
        for (Rel r : after.releases()) {
            String now = r.stage() + r.canaryPods();
            if (!now.equals(was.get(r.name()))) {
                LOG.infof("rollout %s: %s %s@%s slot %s is %s on pods %s (this pod %s)", tenant, r.name(), r.app(), r.version(),
                        r.slot(), r.stage(), r.canaryPods(), pod.name());
            }
        }
    }

    static Rel rel(Document d) {
        String pods = d.getString("canary_pods");
        Set<String> canaryPods = pods == null || pods.isBlank() ? Set.of()
                : Arrays.stream(pods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        String sha = d.getString("artifact_sha256");
        String artifact = d.getString("artifact");
        if (sha == null && artifact != null) sha = WasmSandbox.sha256(Base64.getDecoder().decode(artifact));
        return new Rel(d.name(), d.getString("slot"), d.getString("tier"), d.getString("app_id"), d.getString("version"),
                emptyToNull(d.getString("customer")), d.getString("stage"), canaryPods, sha, artifact);
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String emptyToNull(String s) { return blank(s) ? null : s; }

    /** This pod's calls, errors and latencies of one release since the last flush. */
    static final class Health {
        private long calls;
        private long errors;
        private final List<Long> nanos = new ArrayList<>();

        synchronized void record(boolean ok, long elapsed) {
            calls++;
            if (!ok) errors++;
            if (nanos.size() < 10_000) nanos.add(elapsed);
        }

        /** Returns {calls, errors, p99 in µs} and starts a new window. */
        synchronized long[] drain() {
            long[] out = {calls, errors, 0};
            if (!nanos.isEmpty()) {
                List<Long> sorted = new ArrayList<>(nanos);
                sorted.sort(null);
                out[2] = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.99) - 1)) / 1_000;
            }
            calls = 0;
            errors = 0;
            nanos.clear();
            return out;
        }
    }
}
