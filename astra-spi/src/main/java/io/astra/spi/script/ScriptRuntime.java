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

    /**
     * Calls an exported function that takes bytes and returns bytes, still without host functions: the host calls the
     * module's {@code alloc(len) -> ptr} export, writes {@code input} into the module's linear memory at {@code ptr},
     * calls {@code function(ptr, len) -> i64} and reads the answer from memory, where the result packs the answer's
     * pointer in its high 32 bits and its length in the low 32. Pointers and lengths are bounds-checked and the answer
     * is capped in size.
     *
     * @throws ScriptException when the module is invalid, traps, answers out of bounds or misses its deadline
     */
    byte[] callBytes(byte[] module, String function, long timeoutMillis, byte[] input);
}
