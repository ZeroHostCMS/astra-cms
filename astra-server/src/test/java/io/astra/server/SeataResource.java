package io.astra.server;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;

/** A real Apache Seata coordinator and Redis for the global-transaction tests. */
public class SeataResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> seata;
    private GenericContainer<?> redis;

    @Override
    public Map<String, String> start() {
        seata = new GenericContainer<>("apache/seata-server:2.6.0.jdk25")
                .withExposedPorts(8091)
                .withEnv("SEATA_IP", "127.0.0.1")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        seata.start();
        redis.start();
        return Map.of(
                "astra.seata.server", seata.getHost() + ":" + seata.getMappedPort(8091),
                "quarkus.redis.hosts", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Override
    public void stop() {
        if (seata != null) seata.stop();
        if (redis != null) redis.stop();
    }
}
