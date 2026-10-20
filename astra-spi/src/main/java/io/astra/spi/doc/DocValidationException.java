package io.astra.spi.doc;

import java.util.List;

/** Raised by the kernel or a hook when a document violates its DocType or a business rule. */
public class DocValidationException extends RuntimeException {

    private final List<String> errors;

    public DocValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public DocValidationException(String error) {
        this(List.of(error));
    }

    public List<String> errors() { return errors; }
}
