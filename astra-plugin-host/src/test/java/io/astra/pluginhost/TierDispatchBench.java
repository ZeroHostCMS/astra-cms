package io.astra.pluginhost;

import io.astra.pluginhost.jvm.IsolatedJvmHost;
import io.astra.pluginhost.jvm.LoadedPlugin;
import io.astra.pluginhost.jvm.TestPlugins;
import io.astra.spi.integration.CarrierAdapter;
import io.astra.spi.integration.CarrierAdapter.Booking;
import io.astra.spi.integration.CarrierAdapter.Shipment;
import io.quarkus.bootstrap.logging.InitialConfigurator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Cost of one call per tier, for the comparison in video 22. Not part of the regular build (no *Test suffix):
 * {@code ./mvnw -pl astra-plugin-host test -Dtest=TierDispatchBench}. Each tier does the same trivial work, so the
 * numbers are dispatch overhead: a direct call (Tier 0), a MethodHandle bound at load called through the bulkhead on
 * a virtual thread with a deadline (Tier 1), a fresh Chicory instance of a 421-byte module per call (Tier 2).
 */
class TierDispatchBench {

    private static final int WARMUP = 20_000;
    private static final int CALLS = 50_000;
    private static final Shipment PARCEL = new Shipment("WB-1", "ORD-1", "C001", "Domestic", "Standard", 1.5, 0);

    /** The Tier 0 equivalent: a class compiled into the image, called directly. */
    static final class CompiledIn implements CarrierAdapter {
        @Override public Booking book(Shipment s) { return new Booking("fastship", s.waybill(), 30_000, "STD", ""); }
    }

    @Test
    void dispatchCostPerTier() throws Exception {
        InitialConfigurator.DELAYED_HANDLER.setHandlers(new java.util.logging.Handler[] {new java.util.logging.ConsoleHandler()});
        CarrierAdapter direct = new CompiledIn();
        LoadedPlugin loaded = new IsolatedJvmHost().load(TestPlugins.carrier("1.0.0", "", "1s", 64,
                "return new Booking(\"fastship\", s.waybill(), 30000, \"STD\", \"\");"));
        CarrierAdapter isolated = loaded.as(CarrierAdapter.class, LoadedPlugin.Outcome.NONE);
        WasmSandbox wasm = new WasmSandbox();
        wasm.maxPages = 16;
        wasm.maxTimeoutMillis = 200;
        byte[] module = Files.readAllBytes(Path.of("../astra-apps/express/wasm/modules/tet-promo.wasm"));

        System.out.println();
        System.out.println("tier                          p50 (µs)   p99 (µs)   calls");
        report("Tier 0  direct call", () -> direct.book(PARCEL).fee());
        report("Tier 1  MethodHandle+bulkhead", () -> isolated.book(PARCEL).fee());
        report("Tier 2  Wasm (Chicory, warm)", () -> wasm.callLong(module, "fee", 50, 1_500, 1, 0, 0));
        System.out.println("java " + Runtime.version() + ", " + Runtime.getRuntime().availableProcessors() + " cpus");
    }

    private static void report(String name, LongSupplier call) {
        long sink = 0;
        for (int i = 0; i < WARMUP; i++) sink += call.getAsLong();
        long[] nanos = new long[CALLS];
        for (int i = 0; i < CALLS; i++) {
            long t = System.nanoTime();
            sink += call.getAsLong();
            nanos[i] = System.nanoTime() - t;
        }
        Arrays.sort(nanos);
        System.out.printf("%-29s %9.2f  %9.2f  %6d%s%n", name, nanos[CALLS / 2] / 1000.0, nanos[(int) (CALLS * 0.99)] / 1000.0, CALLS,
                sink == 42 ? " " : "");
    }
}
