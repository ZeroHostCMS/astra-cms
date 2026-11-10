package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;

import java.util.Map;
import java.util.Optional;

/** Claims and result writes of background jobs, through {@link DocumentService#updateSystemFields}. */
final class SystemFields {

    private SystemFields() { }

    /** Moves the document on if it is unchanged since {@code seen}; empty when another pod (or a user) got there first. */
    static Optional<Document> claim(DocumentService docs, Document seen, Map<String, Object> fields) {
        return docs.updateSystemFields(seen.doctype(), seen.name(), seen.version(), fields);
    }

    /** Writes a job's result, re-reading the document when something else changed it meanwhile (a scan, a status). */
    static void write(DocumentService docs, String doctype, String name, Map<String, Object> fields) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<Document> current = docs.get(doctype, name);
            if (current.isEmpty()) return;
            if (docs.updateSystemFields(doctype, name, current.get().version(), fields).isPresent()) return;
        }
        throw new IllegalStateException(doctype + " " + name + " keeps changing; result not written: " + fields);
    }
}
