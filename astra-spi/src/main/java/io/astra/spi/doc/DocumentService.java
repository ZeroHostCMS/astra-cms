package io.astra.spi.doc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Document access for app code (hooks, jobs). Every call runs in the current tenant scope; when it
 * is made from inside a lifecycle hook it joins the caller's transaction, so a hook that writes
 * other documents either commits with the triggering operation or rolls back with it.
 */
public interface DocumentService {

    Optional<Document> get(String doctype, String name);

    /** Documents whose fields equal the given values (or {@code docstatus}), newest first. */
    List<Document> find(String doctype, Map<String, String> filters, int limit);

    Document insert(String doctype, Map<String, Object> data, String owner);

    Optional<Document> update(String doctype, String name, Map<String, Object> patch);

    /**
     * Draft → Submitted, running the DocType's {@code onSubmit} hooks. Called on behalf of a signed-in user,
     * the user's submit permission (and segregation of duties) is checked exactly as for the REST submit,
     * so a hook cannot submit a document its caller could not. Empty when the document does not exist.
     */
    Optional<Document> submit(String doctype, String name);

    /** Submitted → Cancelled, running {@code onCancel} hooks, under the same caller permission check as the REST cancel. */
    Optional<Document> cancel(String doctype, String name);
}
