package io.astra.spi.integration;

/**
 * The network access the host grants a Tier 1 plugin: an HTTP client that reaches the origins listed in the plugin
 * manifest's {@code capabilities.network} and nothing else, and logs refusals with the plugin's identity. Java 25 has
 * no SecurityManager, so this is a capability the plugin is given, not a check on sockets plugin code could open with
 * JDK classes; Tier 1 is for trusted code, untrusted code runs in the Tier 2 sandbox.
 */
public interface PartnerHttp {

    /** @throws CapabilityDeniedException when the URL's origin is not granted to the calling plugin */
    Response get(String url);

    /** POSTs a JSON body. @throws CapabilityDeniedException when the URL's origin is not granted to the calling plugin */
    Response postJson(String url, String json);

    record Response(int status, String body) {
        public boolean ok() { return status >= 200 && status < 300; }
    }
}
