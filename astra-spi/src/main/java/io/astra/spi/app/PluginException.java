package io.astra.spi.app;

/**
 * A call into a Tier 1 plugin did not produce a result. {@link #reason()} is one of {@code rejected} (bulkhead full),
 * {@code open} (circuit breaker open), {@code timeout} (deadline passed), {@code denied} (capability refused) or
 * {@code failed} (the plugin threw). Callers keep working without the plugin's answer and report the reason.
 */
public class PluginException extends RuntimeException {

    private final String reason;

    public PluginException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
