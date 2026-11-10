package io.astra.spi.integration;

/** A plugin asked for something its manifest does not grant, for example a host outside {@code capabilities.network}. */
public class CapabilityDeniedException extends RuntimeException {

    public CapabilityDeniedException(String message) {
        super(message);
    }
}
