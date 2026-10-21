package io.astra.tx.seata;

import io.astra.spi.kv.Counters;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

/** {@link Counters} on Redis, with every key prefixed by the current tenant: {@code astra:<tenant>:<key>}. */
@ApplicationScoped
public class RedisCounters implements Counters {

    @Inject RedisDataSource redis;

    @Override
    public long add(String key, long delta, Duration ttl) {
        String k = key(key);
        long v = redis.value(Long.class).incrby(k, delta);
        redis.key().expire(k, ttl);
        return v;
    }

    @Override
    public long get(String key) {
        Long v = redis.value(Long.class).get(key(key));
        return v == null ? 0 : v;
    }

    @Override
    public boolean markOnce(String key, Duration ttl) {
        return redis.value(String.class).setnx(key(key), "1") && redis.key().expire(key(key), ttl);
    }

    private static String key(String key) {
        return "astra:" + TenantContext.require() + ":" + key;
    }
}
