package io.astra.spi.app;

import io.astra.spi.integration.PartnerHttp;

import java.util.Map;

/**
 * What a Tier 1 plugin receives when it starts. Deliberately small: no document access, no ledger, no kernel types,
 * so an isolated-JVM plugin cannot write the books; it can only answer the call it was loaded for.
 */
public interface PluginContext {

    /** {@code id@version} of this plugin. */
    String identity();

    /** HTTP limited to the hosts the manifest grants. */
    PartnerHttp http();

    /** Plugin settings from the manifest's {@code config} block. */
    Map<String, String> config();

    System.Logger log();
}
