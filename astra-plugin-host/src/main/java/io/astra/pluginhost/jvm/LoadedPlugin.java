package io.astra.pluginhost.jvm;

import io.astra.spi.app.AstraPlugin;
import io.astra.spi.app.PluginContext;
import io.astra.spi.app.PluginException;
import io.astra.spi.integration.CapabilityDeniedException;
import io.astra.spi.integration.PartnerHttp;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One loaded version of a Tier 1 plugin. The SPI methods are bound as {@link MethodHandle}s once, at load; every call
 * then goes through the bulkhead: a {@link Semaphore} of {@code max-concurrency} permits (a full bulkhead rejects at
 * once instead of queueing), a circuit breaker on the failure rate in the manifest's window, and a deadline enforced
 * on a virtual thread that is interrupted when it passes. The plugin's identity is bound as a {@link ScopedValue}.
 */
public final class LoadedPlugin {

    private final PluginManifest manifest;
    private final String sha256;
    private final Class<?> spi;
    private volatile Object instance; // dropped on unload with the handles and the loader
    private final Map<Method, MethodHandle> handles = new HashMap<>();
    private final Semaphore permits;
    private final ExecutorService threads;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final CircuitBreaker breaker;
    private volatile ClassLoader loader; // dropped on unload so only the WeakReference in the host remains

    LoadedPlugin(PluginManifest manifest, String sha256, PluginClassLoader loader, ExecutorService threads) {
        this.manifest = manifest;
        this.sha256 = sha256;
        this.loader = loader;
        this.threads = threads;
        this.permits = new Semaphore(Math.max(1, manifest.maxConcurrency()));
        this.breaker = new CircuitBreaker(manifest.failureRate(), manifest.failureWindow());
        try {
            this.spi = Class.forName(manifest.spi(), false, loader);
            if (!spi.isInterface() || !spi.getName().startsWith(PluginClassLoader.SPI_PACKAGE)) {
                throw new IllegalArgumentException("spi must be an io.astra.spi interface: " + manifest.spi());
            }
            Class<?> entry = Class.forName(manifest.entrypoint(), true, loader);
            if (!spi.isAssignableFrom(entry)) {
                throw new IllegalArgumentException(manifest.entrypoint() + " does not implement " + manifest.spi());
            }
            this.instance = entry.getConstructor().newInstance();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            for (Method m : spi.getMethods()) {
                if (m.isDefault() || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                handles.put(m, lookup.unreflect(m).bindTo(instance));
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalArgumentException("cannot load " + manifest.identity() + ": " + e, e);
        }
        if (this.instance instanceof AstraPlugin plugin) {
            PartnerHttp http = new GrantedPartnerHttp(manifest);
            PluginContext context = new Context(manifest, http);
            onPluginLoader(loader, () -> ScopedValue.where(ScopedPluginIdentity.CURRENT, manifest.identity()).run(() -> plugin.start(context)));
        }
    }

    /** Runs {@code body} with the plugin's class loader as the context class loader, then restores it. */
    private static void onPluginLoader(ClassLoader pluginLoader, Runnable body) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(pluginLoader);
        try {
            body.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public PluginManifest manifest() { return manifest; }

    public String identity() { return manifest.identity(); }

    public String sha256() { return sha256; }

    public int inFlight() { return inFlight.get(); }

    /** True once {@link #stop()} has run: its MethodHandles are cleared, so a cached view must be rebuilt. */
    public boolean stopped() { return loader == null; }

    ClassLoader loader() { return loader; }

    /**
     * A view of the plugin as its SPI interface, for kernel and app code. Each call on the view goes through
     * {@link #invoke}; {@code onOutcome} sees every call's result for health accounting.
     */
    public <T> T as(Class<T> type, Outcome onOutcome) {
        if (!type.getName().equals(spi.getName())) throw new IllegalArgumentException(identity() + " implements " + spi.getName());
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return method.invoke(this, args);
            long started = System.nanoTime();
            try {
                Object result = invoke(method, args == null ? new Object[0] : args);
                onOutcome.record(this, true, null, System.nanoTime() - started);
                return result;
            } catch (PluginException e) {
                onOutcome.record(this, false, e.reason(), System.nanoTime() - started);
                throw e;
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    /** Calls an SPI method through the bulkhead. */
    Object invoke(Method method, Object[] args) {
        MethodHandle handle = handles.get(method);
        if (handle == null) throw new PluginException("failed", identity() + " has no method " + method.getName(), null);
        if (!breaker.allows()) throw new PluginException("open", identity() + ": circuit breaker open after repeated failures", null);
        if (!permits.tryAcquire()) {
            throw new PluginException("rejected", identity() + ": max-concurrency " + manifest.maxConcurrency() + " reached", null);
        }
        inFlight.incrementAndGet();
        Duration timeout = manifest.timeout();
        ClassLoader pluginLoader = loader;
        Future<Object> call;
        try {
            // The permit and in-flight count are released by the task itself (below), not by this caller: a call that
            // times out keeps holding its slot while its thread is still running, so the bulkhead bounds running work,
            // not waiting callers, and a drain before unload sees it as still in flight.
            call = threads.submit(() -> {
                Thread thread = Thread.currentThread();
                ClassLoader previous = thread.getContextClassLoader();
                thread.setContextClassLoader(pluginLoader);   // plugin code (ServiceLoader, Jackson) sees only its own loader
                try {
                    return ScopedValue.where(ScopedPluginIdentity.CURRENT, identity()).call(() -> invokeHandle(handle, args));
                } finally {
                    thread.setContextClassLoader(previous);
                    inFlight.decrementAndGet();
                    permits.release();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            permits.release();
            throw new PluginException("rejected", identity() + ": host is shutting down", e);
        }
        try {
            Object result = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            breaker.record(true);
            return result;
        } catch (TimeoutException e) {
            call.cancel(true);
            breaker.record(false);
            throw new PluginException("timeout", identity() + " exceeded its " + timeout.toMillis() + " ms deadline", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CapabilityDeniedException denied) {
                breaker.record(false);
                throw new PluginException("denied", denied.getMessage(), denied);
            }
            breaker.record(false);
            throw new PluginException("failed", identity() + " failed: " + cause, cause);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new PluginException("timeout", identity() + ": interrupted", e);
        }
    }

    private static Object invokeHandle(MethodHandle handle, Object[] args) throws Exception {
        try {
            return handle.invokeWithArguments(args);
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Stops the plugin and forgets its classes; the caller has already drained in-flight calls. */
    void stop() {
        ClassLoader pluginLoader = loader;
        if (instance instanceof AstraPlugin plugin && pluginLoader != null) {
            try {
                onPluginLoader(pluginLoader, () -> ScopedValue.where(ScopedPluginIdentity.CURRENT, identity()).run(plugin::stop));
            } catch (RuntimeException ignored) {
                // a plugin that fails to stop is unloaded anyway
            }
        }
        handles.clear();
        instance = null;
        loader = null;
    }

    /** Health accounting hook: one record per call through {@link #as}. */
    @FunctionalInterface
    public interface Outcome {
        Outcome NONE = (plugin, ok, reason, nanos) -> { };

        void record(LoadedPlugin plugin, boolean ok, String reason, long nanos);
    }

    private record Context(PluginManifest manifest, PartnerHttp http) implements PluginContext {
        @Override public String identity() { return manifest.identity(); }
        @Override public Map<String, String> config() { return manifest.config(); }
        @Override public System.Logger log() { return System.getLogger("plugin." + manifest.id()); }
    }

    /** Opens when the failure rate in the current window reaches the threshold (after at least 10 calls), for one window. */
    static final class CircuitBreaker {
        private static final int MIN_CALLS = 10;
        private final double failureRate;
        private final long windowNanos;
        private final AtomicLong windowStart = new AtomicLong(System.nanoTime());
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntil = System.nanoTime();

        CircuitBreaker(double failureRate, Duration window) {
            this.failureRate = failureRate;
            this.windowNanos = window.toNanos();
        }

        boolean allows() {
            return System.nanoTime() - openUntil >= 0;
        }

        void record(boolean ok) {
            long now = System.nanoTime();
            long start = windowStart.get();
            if (now - start > windowNanos && windowStart.compareAndSet(start, now)) {
                calls.set(0);
                failures.set(0);
            }
            int c = calls.incrementAndGet();
            int f = ok ? failures.get() : failures.incrementAndGet();
            if (c >= MIN_CALLS && (double) f / c >= failureRate) {
                openUntil = now + windowNanos;
                calls.set(0);
                failures.set(0);
            }
        }
    }
}
