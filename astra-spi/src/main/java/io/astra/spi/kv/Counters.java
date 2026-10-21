package io.astra.spi.kv;

import java.time.Duration;

/** Atomic counters and one-shot markers in the platform's key-value store (Redis), scoped to the current tenant. */
public interface Counters {

    /** Adds {@code delta} and returns the new value. */
    long add(String key, long delta, Duration ttl);

    long get(String key);

    /** Sets a marker if it is not set yet; returns false when it already existed (idempotency). */
    boolean markOnce(String key, Duration ttl);
}
