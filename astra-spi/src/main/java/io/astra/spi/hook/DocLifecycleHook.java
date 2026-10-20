package io.astra.spi.hook;

import io.astra.spi.doc.Document;

/**
 * Extension point: lifecycle hooks for one DocType. Implementations are discovered by the kernel,
 * validated at startup, and dispatched in a precomputed chain. A hook may mutate the document or
 * throw {@link io.astra.spi.doc.DocValidationException} to reject the operation.
 */
public interface DocLifecycleHook {

    /** The DocType this hook applies to, e.g. {@code "Article"}. */
    String doctype();

    /** Runs before a document is inserted or updated, after kernel validation of declared fields. */
    default void beforeSave(Document doc) { }

    /** Runs after the document has been persisted in the same transaction. */
    default void afterSave(Document doc) { }
}
