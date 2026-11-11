package io.astra.pluginhost.jvm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code META-INF/astra-app.yaml} of a Tier 1 jar: identity, the extension point it implements, the network origins
 * it may reach, and its runtime budget.
 */
public record PluginManifest(
        String id,
        String version,
        String entrypoint,
        String spi,
        List<String> network,
        Duration timeout,
        int maxConcurrency,
        double failureRate,
        Duration failureWindow,
        Map<String, String> config) {

    public static final String PATH = "META-INF/astra-app.yaml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public String identity() { return id + "@" + version; }

    public static PluginManifest parse(byte[] yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new IllegalArgumentException("astra-app.yaml is not valid YAML: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("astra-app.yaml is empty");
        String tier = root.path("tier").asText("");
        if (!"isolated-jvm".equals(tier)) throw new IllegalArgumentException("tier must be isolated-jvm, was '" + tier + "'");
        List<String> network = new ArrayList<>();
        for (JsonNode n : root.path("capabilities").path("network")) network.add(origin(n.asText()));
        JsonNode runtime = root.path("runtime");
        JsonNode breaker = runtime.path("circuit-breaker");
        Map<String, String> config = new LinkedHashMap<>();
        root.path("config").properties().forEach(e -> config.put(e.getKey(), e.getValue().asText()));
        return new PluginManifest(
                required(root, "id"), required(root, "version"), required(root, "entrypoint"), required(root, "spi"),
                List.copyOf(network),
                duration(runtime.path("timeout").asText("300ms")),
                runtime.path("max-concurrency").asInt(32),
                breaker.path("failure-rate").asDouble(0.5),
                duration(breaker.path("window").asText("30s")),
                Map.copyOf(config));
    }

    /** {@code scheme://host[:port]} of a URL, the unit {@code capabilities.network} grants. */
    public static String origin(String url) {
        URI uri = URI.create(url.trim());
        if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalArgumentException("not an absolute URL: " + url);
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    static Duration duration(String text) {
        String t = text.trim();
        if (t.endsWith("ms")) return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2)));
        if (t.endsWith("s")) return Duration.ofSeconds(Long.parseLong(t.substring(0, t.length() - 1)));
        throw new IllegalArgumentException("duration must end in ms or s: " + text);
    }

    private static String required(JsonNode root, String field) {
        String v = root.path(field).asText("");
        if (v.isBlank()) throw new IllegalArgumentException("astra-app.yaml: '" + field + "' is required");
        return v;
    }
}
