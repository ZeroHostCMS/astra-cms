package io.astra.spi.release;

import java.util.Optional;

/**
 * Which approved release serves an extension slot for the current tenant, on this pod. Order of precedence:
 * a pin for the customer, a pin for the tenant, then the slot's canary when this pod is one of its canary pods,
 * otherwise the slot's live release. Releases scoped to a customer serve only that customer's calls.
 */
public interface Releases {

    /** This pod's name, the value of the {@code X-Astra-Pod} response header. */
    String pod();

    /**
     * The Tier 1 plugin serving {@code slot}, as its SPI interface. Calls through the returned target run in the
     * plugin's bulkhead and count towards the release's health; failures surface as
     * {@link io.astra.spi.app.PluginException}.
     *
     * @param customer the customer the call is for, or {@code null}
     */
    <T> Optional<Bound<T>> plugin(String slot, String customer, Class<T> spi);

    /** The Tier 2 module or rule set serving {@code slot}; the caller runs it and reports with {@link #record}. */
    Optional<Artifact> artifact(String slot, String customer);

    /** One call of an artifact, for the release's health (calls, errors, p99) and the canary decision. */
    void record(Artifact artifact, boolean ok, long nanos);

    record Bound<T>(String release, String app, String version, String pod, T target) {
        /** {@code app@version on pod}, what documents record in their {@code …_by} field. */
        public String label() { return app + "@" + version + " on " + pod; }
    }

    record Artifact(String release, String app, String version, String tier, String sha256, byte[] bytes, String pod) {
        public String label() { return app + "@" + version + " on " + pod; }
    }
}
