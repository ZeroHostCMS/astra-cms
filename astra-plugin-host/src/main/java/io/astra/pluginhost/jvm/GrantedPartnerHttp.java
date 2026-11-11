package io.astra.pluginhost.jvm;

import io.astra.spi.integration.CapabilityDeniedException;
import io.astra.spi.integration.PartnerHttp;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/** {@link PartnerHttp} for one plugin version: only the origins in its manifest, within its call deadline. */
final class GrantedPartnerHttp implements PartnerHttp {

    private static final Logger LOG = Logger.getLogger(GrantedPartnerHttp.class);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final String identity;
    private final Set<String> granted;
    private final Duration timeout;

    GrantedPartnerHttp(PluginManifest manifest) {
        this.identity = manifest.identity();
        this.granted = Set.copyOf(manifest.network());
        this.timeout = manifest.timeout();
    }

    @Override
    public Response get(String url) {
        return send(url, HttpRequest.newBuilder(check(url)).GET());
    }

    @Override
    public Response postJson(String url, String json) {
        return send(url, HttpRequest.newBuilder(check(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private URI check(String url) {
        String origin;
        try {
            origin = PluginManifest.origin(url);
        } catch (IllegalArgumentException e) {
            throw new CapabilityDeniedException(identity + " may not call '" + url + "': not an absolute URL");
        }
        if (!granted.contains(origin)) {
            String caller = ScopedPluginIdentity.current().orElse(identity);
            LOG.warnf("PartnerHttp denied %s for %s (granted: %s)", url, caller, granted);
            throw new CapabilityDeniedException(caller + " may not call " + origin + "; capabilities.network grants " + granted);
        }
        return URI.create(url);
    }

    private Response send(String url, HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = CLIENT.send(request.timeout(timeout).header("X-Astra-Plugin", identity).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new UncheckedPartnerException(identity + " could not reach " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedPartnerException(identity + " was interrupted calling " + url, e);
        }
    }

    /** A network failure; plain RuntimeException so the plugin needs no host type to handle it. */
    static final class UncheckedPartnerException extends RuntimeException {
        UncheckedPartnerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
