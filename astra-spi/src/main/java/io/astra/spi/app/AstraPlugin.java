package io.astra.spi.app;

/**
 * Lifecycle of a Tier 1 (isolated-JVM) plugin's entrypoint. The entrypoint class needs a public no-argument
 * constructor and implements this interface plus the extension point named in its manifest's {@code spi}.
 */
public interface AstraPlugin {

    void start(PluginContext context);

    /** Called after the last in-flight call drained, before the plugin's class loader is dropped. */
    default void stop() { }
}
