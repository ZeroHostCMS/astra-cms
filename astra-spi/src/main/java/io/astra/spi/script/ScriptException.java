package io.astra.spi.script;

/** A tenant script failed; {@link #reason()} is one of {@code invalid}, {@code trap}, {@code timeout}. */
public class ScriptException extends RuntimeException {

    private final String reason;

    public ScriptException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
