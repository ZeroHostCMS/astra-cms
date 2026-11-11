package io.astra.spi.release;

/**
 * Declares an extension slot that releases can fill, and vets an artifact before it can be approved. The app that
 * calls the slot contributes its contract (for example {@code carrier.booking}, Tier 1, {@code CarrierAdapter}).
 */
public interface SlotContract {

    /** Slot name, for example {@code carrier.booking}. */
    String slot();

    /** {@code isolated-jvm}, {@code wasm} or {@code rules}. */
    String tier();

    /** For {@code isolated-jvm} slots: the SPI interface the plugin's entrypoint must implement. */
    default Class<?> spi() { return null; }

    /**
     * Runs the artifact once on sample input. Throw {@link io.astra.spi.doc.DocValidationException} to refuse it;
     * return a one-line summary for the release's {@code self_test} field.
     */
    default String selfTest(byte[] artifact) { return "loaded"; }
}
