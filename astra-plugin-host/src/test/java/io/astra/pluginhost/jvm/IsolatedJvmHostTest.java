package io.astra.pluginhost.jvm;

import com.sun.net.httpserver.HttpServer;
import io.astra.spi.app.PluginException;
import io.astra.spi.integration.CarrierAdapter;
import io.astra.spi.integration.CarrierAdapter.Booking;
import io.astra.spi.integration.CarrierAdapter.Shipment;
import io.quarkus.bootstrap.logging.InitialConfigurator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedJvmHostTest {

    private static final Shipment PARCEL = new Shipment("WB-1", "ORD-1", "CUS-1", "Remote", "Standard", 1.2, 0);
    private static final String NO_NETWORK = "";

    /** Without a started Quarkus app the delayed log handler would keep every record (and what it references). */
    @BeforeAll
    static void startLogging() {
        java.util.logging.ConsoleHandler console = new java.util.logging.ConsoleHandler();
        console.setLevel(java.util.logging.Level.INFO);
        InitialConfigurator.DELAYED_HANDLER.setHandlers(new java.util.logging.Handler[] {console});
    }

    private static byte[] fixed(String version, String tracking) {
        return TestPlugins.carrier(version, NO_NETWORK, "300ms", 32,
                "return new Booking(\"fastship\", \"" + tracking + "\", 1000, s.service(), \"\");");
    }

    private static CarrierAdapter adapter(LoadedPlugin p) {
        return p.as(CarrierAdapter.class, LoadedPlugin.Outcome.NONE);
    }

    @Test
    void pluginSeesOnlyTheSpiAndTheJdk() {
        IsolatedJvmHost host = new IsolatedJvmHost();
        LoadedPlugin p = host.load(TestPlugins.carrier("1.0.0", NO_NETWORK, "300ms", 4, """
                StringBuilder seen = new StringBuilder();
                for (String c : new String[] {"io.astra.pluginhost.jvm.IsolatedJvmHost", "io.astra.spi.doc.DocumentService",
                        "com.fasterxml.jackson.databind.ObjectMapper", "org.jboss.logging.Logger", "java.util.List"}) {
                    try { Class.forName(c); seen.append(c.substring(c.lastIndexOf('.') + 1)).append(' '); }
                    catch (ClassNotFoundException e) { seen.append('-').append(' '); }
                }
                return new Booking("x", seen.toString().trim(), 0, "", "");
                """));
        assertEquals("- DocumentService - - List", adapter(p).book(PARCEL).tracking());
    }

    @Test
    void twoVersionsOfOnePluginRunSideBySide() {
        IsolatedJvmHost host = new IsolatedJvmHost();
        LoadedPlugin v1 = host.load(fixed("1.0.0", "v1"));
        LoadedPlugin v2 = host.load(fixed("1.1.0", "v2"));
        assertNotSame(v1.loader(), v2.loader());
        assertEquals("v1", adapter(v1).book(PARCEL).tracking());
        assertEquals("v2", adapter(v2).book(PARCEL).tracking());
        assertSame(v1, host.load(fixed("1.0.0", "v1")), "same bytes load once");
    }

    @Test
    void hotSwapUnderLoadLosesNoCall() throws Exception {
        IsolatedJvmHost host = new IsolatedJvmHost();
        LoadedPlugin v1 = host.load(TestPlugins.carrier("1.0.0", NO_NETWORK, "300ms", 256,
                "Thread.sleep(2); return new Booking(\"fastship\", \"v1\", 1000, s.service(), \"\");"));
        LoadedPlugin v2 = host.load(TestPlugins.carrier("1.1.0", NO_NETWORK, "300ms", 256,
                "Thread.sleep(2); return new Booking(\"fastship\", \"v2\", 1000, s.service(), \"\");"));
        AtomicReference<LoadedPlugin> live = new AtomicReference<>(v1);
        AtomicInteger failures = new AtomicInteger();
        List<String> served = java.util.Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                if (i == 100) {
                    // re-point dispatch while v1 calls are in flight, then drain and unload the old version
                    calls.add(pool.submit(() -> {
                        live.set(v2);
                        host.unload(v1, Duration.ofSeconds(2));
                    }));
                }
                calls.add(pool.submit(() -> {
                    try {
                        served.add(adapter(live.get()).book(PARCEL).tracking());
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                }));
                java.util.concurrent.locks.LockSupport.parkNanos(200_000); // traffic, not a burst
            }
            for (Future<?> f : calls) f.get();
        }
        assertEquals(0, failures.get());
        assertEquals(200, served.size());
        assertTrue(served.contains("v1") && served.contains("v2"), "both versions served: " + new java.util.HashSet<>(served));
    }

    @Test
    void unloadedVersionIsCollected() throws Exception {
        IsolatedJvmHost host = new IsolatedJvmHost();
        LoadedPlugin v1 = host.load(fixed("1.0.0", "v1"));
        assertEquals("v1", adapter(v1).book(PARCEL).tracking());
        host.unload(v1, Duration.ofSeconds(1));
        v1 = null;
        List<String> alive = host.leakCheck();
        for (int i = 0; i < 50 && !alive.isEmpty(); i++) {
            System.gc();
            Thread.sleep(20);
            alive = host.leakCheck();
        }
        assertEquals(List.of(), alive, "class loader of the unloaded version is still reachable");
    }

    @Test
    void deadlineStopsASlowPlugin() {
        IsolatedJvmHost host = new IsolatedJvmHost();
        LoadedPlugin p = host.load(TestPlugins.carrier("1.0.0", NO_NETWORK, "50ms", 4,
                "Thread.sleep(5_000); return null;"));
        long started = System.nanoTime();
        PluginException e = assertThrows(PluginException.class, () -> adapter(p).book(PARCEL));
        assertEquals("timeout", e.reason());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000, "caller waited past the deadline");
    }

    @Test
    void fullBulkheadRejectsAtOnce() throws Exception {
        IsolatedJvmHost host = new IsolatedJvmHost();
        LoadedPlugin p = host.load(TestPlugins.carrier("1.0.0", NO_NETWORK, "2s", 1,
                "Thread.sleep(500); return new Booking(\"x\", \"t\", 0, \"\", \"\");"));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Booking> first = pool.submit(() -> adapter(p).book(PARCEL));
            while (p.inFlight() == 0) Thread.onSpinWait();
            long started = System.nanoTime();
            PluginException e = assertThrows(PluginException.class, () -> adapter(p).book(PARCEL));
            assertEquals("rejected", e.reason());
            assertTrue((System.nanoTime() - started) / 1_000_000 < 100, "rejection queued instead of failing fast");
            assertEquals("t", first.get().tracking());
        }
    }

    @Test
    void partnerHttpReachesOnlyGrantedOrigins() throws Exception {
        HttpServer partner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        partner.createContext("/fastship/bookings", ex -> {
            byte[] body = "FS-123".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        partner.start();
        try {
            String origin = "http://127.0.0.1:" + partner.getAddress().getPort();
            IsolatedJvmHost host = new IsolatedJvmHost();
            LoadedPlugin p = host.load(TestPlugins.carrier("1.0.0", "\"" + origin + "\"", "1s", 4, """
                    String target = s.zone().equals("Remote") ? "https://example.com/" : "%s/fastship/bookings";
                    return new Booking("fastship", ctx.http().postJson(target, "{}").body(), 0, "", "");
                    """.formatted(origin)));
            Shipment local = new Shipment("WB-2", "ORD-2", "CUS-1", "Same city", "Standard", 1, 0);
            assertEquals("FS-123", adapter(p).book(local).tracking());
            PluginException e = assertThrows(PluginException.class, () -> adapter(p).book(PARCEL));
            assertEquals("denied", e.reason());
            assertTrue(e.getMessage().contains("test.carrier@1.0.0 may not call https://example.com"), e.getMessage());
        } finally {
            partner.stop(0);
        }
    }

    @Test
    void circuitBreakerOpensOnRepeatedFailures() {
        IsolatedJvmHost host = new IsolatedJvmHost();
        LoadedPlugin p = host.load(TestPlugins.carrier("1.2.0-bad", NO_NETWORK, "300ms", 4,
                "if (s.zone().equals(\"Remote\")) throw new IllegalStateException(\"no route\"); return null;"));
        for (int i = 0; i < 10; i++) {
            assertEquals("failed", assertThrows(PluginException.class, () -> adapter(p).book(PARCEL)).reason());
        }
        assertEquals("open", assertThrows(PluginException.class, () -> adapter(p).book(PARCEL)).reason());
    }
}
