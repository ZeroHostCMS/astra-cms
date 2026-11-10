package io.astra.pluginhost.jvm;

import io.astra.pluginhost.WasmSandbox;
import io.astra.spi.integration.PartnerHttp;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Tier 1 host: loads plugin jars into their own {@link PluginClassLoader}, keyed by the jar's SHA-256, keeps several
 * versions side by side (a canary and the version it replaces), and unloads a version after its in-flight calls
 * drained. Every unloaded loader is watched through a {@link WeakReference}, so a plugin that leaks its class loader
 * (a static cache in the JDK, a thread it started) shows up as "still reachable" instead of slowly filling Metaspace.
 */
@ApplicationScoped
public class IsolatedJvmHost {

    private static final Logger LOG = Logger.getLogger(IsolatedJvmHost.class);
    private static final int MAX_JAR_BYTES = 16 * 1024 * 1024;

    private final Map<String, LoadedPlugin> loaded = new ConcurrentHashMap<>();
    private final List<Unloaded> unloaded = new CopyOnWriteArrayList<>();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final ClassLoader spiLoader = PartnerHttp.class.getClassLoader();

    /** Loads a jar, or returns the version already loaded from the same bytes. */
    public LoadedPlugin load(byte[] jar) {
        if (jar == null || jar.length == 0) throw new IllegalArgumentException("empty plugin jar");
        if (jar.length > MAX_JAR_BYTES) throw new IllegalArgumentException("plugin jar exceeds " + MAX_JAR_BYTES + " bytes");
        String sha = WasmSandbox.sha256(jar);
        return loaded.computeIfAbsent(sha, k -> {
            Map<String, byte[]> entries = entries(jar);
            byte[] yaml = entries.get(PluginManifest.PATH);
            if (yaml == null) throw new IllegalArgumentException("jar has no " + PluginManifest.PATH);
            PluginManifest manifest = PluginManifest.parse(yaml);
            LoadedPlugin plugin = new LoadedPlugin(manifest, sha, new PluginClassLoader(manifest.identity(), entries, spiLoader), threads);
            LOG.infof("plugin %s loaded (sha256 %s, %d entries, network %s)", manifest.identity(), sha.substring(0, 12),
                    entries.size(), manifest.network());
            return plugin;
        });
    }

    /**
     * Reads a jar's {@code META-INF/astra-app.yaml} without defining, initialising or starting any of its classes.
     * Used to vet a draft release at save time: no plugin code runs until an admin has approved the release and a call
     * is actually routed to it (then {@link #load}). Saving a jar must never run the jar.
     */
    public PluginManifest manifestOf(byte[] jar) {
        if (jar == null || jar.length == 0) throw new IllegalArgumentException("empty plugin jar");
        if (jar.length > MAX_JAR_BYTES) throw new IllegalArgumentException("plugin jar exceeds " + MAX_JAR_BYTES + " bytes");
        byte[] yaml = entries(jar).get(PluginManifest.PATH);
        if (yaml == null) throw new IllegalArgumentException("jar has no " + PluginManifest.PATH);
        return PluginManifest.parse(yaml);
    }

    public List<LoadedPlugin> loaded() {
        return List.copyOf(loaded.values());
    }

    /**
     * Waits up to {@code drain} for the version's in-flight calls, stops it and drops its classes. Callers stop routing
     * new calls to the version first; calls that are still running after the drain period are left to finish on
     * their own, the loader then just becomes collectable later.
     */
    public void unload(LoadedPlugin plugin, Duration drain) {
        if (loaded.remove(plugin.sha256()) == null) return;
        long until = System.nanoTime() + drain.toNanos();
        while (plugin.inFlight() > 0 && System.nanoTime() < until) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        ClassLoader loader = plugin.loader();
        plugin.stop();
        unloaded.add(new Unloaded(plugin.identity(), new WeakReference<>(loader)));
        LOG.infof("plugin %s unloaded after drain (in-flight %d)", plugin.identity(), plugin.inFlight());
    }

    /**
     * Checks the watched loaders of unloaded versions: logs and forgets those the JVM collected, returns the identities
     * still reachable. Called periodically by the rollout job and by tests.
     */
    public List<String> leakCheck() {
        List<String> alive = new ArrayList<>();
        for (Unloaded u : unloaded) {
            if (u.loader().get() == null) {
                unloaded.remove(u);
                LOG.infof("classloader %s collected", u.identity());
            } else {
                alive.add(u.identity());
            }
        }
        return alive;
    }

    private static Map<String, byte[]> entries(byte[] jar) {
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry e;
            while ((e = in.getNextJarEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                total += out.size();
                if (total > 4L * MAX_JAR_BYTES) throw new IllegalArgumentException("plugin jar expands beyond " + 4 * MAX_JAR_BYTES + " bytes");
                entries.put(e.getName(), out.toByteArray());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("not a jar: " + e.getMessage(), e);
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("not a jar, or an empty one");
        return entries;
    }

    private record Unloaded(String identity, WeakReference<ClassLoader> loader) { }
}
