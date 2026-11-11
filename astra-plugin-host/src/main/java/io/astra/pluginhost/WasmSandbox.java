package io.astra.pluginhost;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tier 2 sandbox on Chicory, a pure-Java WebAssembly runtime: no native code, no JNI, nothing the
 * module can reach outside its own linear memory. Each call gets a fresh instance (no state survives
 * between calls or leaks between tenants), memory is capped at {@code astra.scripts.max-memory-pages}
 * (64 KiB each), host imports are refused, and the call runs on its own virtual thread that is
 * interrupted at the deadline. Chicory has no instruction metering, so the deadline is the CPU control.
 */
@ApplicationScoped
public class WasmSandbox implements ScriptRuntime {

    private static final Logger LOG = Logger.getLogger(WasmSandbox.class);

    @ConfigProperty(name = "astra.scripts.max-memory-pages", defaultValue = "16")
    int maxPages;

    @ConfigProperty(name = "astra.scripts.max-timeout-ms", defaultValue = "200")
    long maxTimeoutMillis;

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    // Parsed modules by content hash, bounded: a backtest or a draft release feeds arbitrary modules through here, so an
    // unbounded cache would grow without limit. Oldest eviction; re-parsing an evicted module is cheap.
    private static final int MAX_PARSED = 256;
    private final Map<String, WasmModule> parsed = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(MAX_PARSED + 1, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, WasmModule> eldest) { return size() > MAX_PARSED; }
            });

    @Override
    public long callLong(byte[] module, String function, long timeoutMillis, long... args) {
        WasmModule wasm = parse(module);
        long deadline = Math.min(Math.max(timeoutMillis, 1), maxTimeoutMillis);
        long started = System.nanoTime();
        Future<long[]> call = threads.submit(() -> instantiate(wasm).export(function).apply(args));
        try {
            long[] result = call.get(deadline, TimeUnit.MILLISECONDS);
            if (result == null || result.length != 1) {
                throw new ScriptException("invalid", "function '" + function + "' must return exactly one i64", null);
            }
            return result[0];
        } catch (TimeoutException e) {
            call.cancel(true); // interrupts the interpreter loop, which stops at its next check
            LOG.warnf("Wasm script stopped after %d ms (deadline %d ms)", (System.nanoTime() - started) / 1_000_000, deadline);
            throw new ScriptException("timeout", "script exceeded its " + deadline + " ms deadline and was stopped", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ScriptException se) throw se;
            throw new ScriptException("trap", "script failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new ScriptException("timeout", "interrupted", e);
        }
    }

    /** Largest input and answer {@link #callBytes} moves through a module's memory. */
    static final int MAX_OUTPUT = 64 * 1024;

    @Override
    public byte[] callBytes(byte[] module, String function, long timeoutMillis, byte[] input) {
        if (input.length > MAX_OUTPUT) throw new ScriptException("invalid", "input of " + input.length + " bytes exceeds " + MAX_OUTPUT, null);
        WasmModule wasm = parse(module);
        long deadline = Math.min(Math.max(timeoutMillis, 1), maxTimeoutMillis);
        Future<byte[]> call = threads.submit(() -> {
            Instance instance = instantiate(wasm);
            Memory memory = instance.memory();
            if (memory == null) throw new ScriptException("invalid", "module exports no memory", null);
            int ptr = (int) single(instance.export("alloc").apply(input.length), "alloc");
            checkBounds(memory, ptr, input.length, "alloc returned");
            memory.write(ptr, input);
            long packed = single(instance.export(function).apply(ptr, input.length), function);
            int outPtr = (int) (packed >>> 32);
            int outLen = (int) (packed & 0xffffffffL);
            if (outLen < 0 || outLen > MAX_OUTPUT) {
                throw new ScriptException("invalid", function + " answered " + Integer.toUnsignedString(outLen) + " bytes; the limit is " + MAX_OUTPUT, null);
            }
            checkBounds(memory, outPtr, outLen, function + " answered");
            return memory.readBytes(outPtr, outLen);
        });
        long started = System.nanoTime();
        try {
            return call.get(deadline, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            LOG.warnf("Wasm script stopped after %d ms (deadline %d ms)", (System.nanoTime() - started) / 1_000_000, deadline);
            throw new ScriptException("timeout", "script exceeded its " + deadline + " ms deadline and was stopped", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ScriptException se) throw se;
            throw new ScriptException("trap", "script failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new ScriptException("timeout", "interrupted", e);
        }
    }

    private static long single(long[] result, String function) {
        if (result == null || result.length != 1) throw new ScriptException("invalid", "function '" + function + "' must return exactly one value", null);
        return result[0];
    }

    private static void checkBounds(Memory memory, int ptr, int len, String what) {
        long size = (long) memory.pages() * 65_536;
        if (ptr < 0 || len < 0 || (long) ptr + len > size) {
            throw new ScriptException("invalid", what + " [" + Integer.toUnsignedString(ptr) + ", +" + len + ") outside the module's "
                    + size + "-byte memory", null);
        }
    }

    private Instance instantiate(WasmModule wasm) {
        int declaredInitial = wasm.memorySection()
                .filter(s -> s.memoryCount() > 0)
                .map(s -> s.getMemory(0).limits().initialPages())
                .orElse(0);
        return Instance.builder(wasm)
                .withMemoryLimits(new MemoryLimits(declaredInitial, maxPages))
                .build();
    }

    /** Parses and vets a module once per content hash. */
    private WasmModule parse(byte[] module) {
        if (module == null || module.length == 0) throw new ScriptException("invalid", "empty module", null);
        return parsed.computeIfAbsent(sha256(module), k -> {
            WasmModule wasm;
            try {
                wasm = Parser.parse(module);
            } catch (ChicoryException | IllegalArgumentException e) {
                throw new ScriptException("invalid", "not a WebAssembly module: " + e.getMessage(), e);
            }
            if (wasm.importSection().importCount() > 0) {
                throw new ScriptException("invalid", "module imports host functions; Tier 2 scripts must be self-contained", null);
            }
            int initial = wasm.memorySection().filter(s -> s.memoryCount() > 0)
                    .map(s -> s.getMemory(0).limits().initialPages()).orElse(0);
            if (initial > maxPages) {
                throw new ScriptException("invalid", "module asks for " + initial + " memory pages; the limit is " + maxPages, null);
            }
            return wasm;
        });
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
