package io.astra.spi.script;

/**
 * Tier 2 extension point: runs untrusted tenant code compiled to WebAssembly in a sandbox with a
 * memory ceiling and a wall-clock deadline. A module sees no host functions: it can compute, nothing else.
 */
public interface ScriptRuntime {

    /**
     * Calls an exported function that takes and returns {@code i64} values.
     *
     * @throws ScriptException when the module is invalid, traps, exceeds its memory limit or misses its deadline
     */
    long callLong(byte[] module, String function, long timeoutMillis, long... args);
}
