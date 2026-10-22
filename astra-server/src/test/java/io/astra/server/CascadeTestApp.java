package io.astra.server;

import io.astra.spi.app.AstraApp;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Test-only app: a batch whose submit and cancel cascade to its items through {@link DocumentService}. */
@ApplicationScoped
public class CascadeTestApp implements AstraApp {

    @Override public String id() { return "io.astra.test.cascade"; }

    @Override public String version() { return "0.0.0"; }

    @Override public List<String> doctypeResources() {
        return List.of("META-INF/astra/doctypes/test-batch.doctype.yaml", "META-INF/astra/doctypes/test-batch-item.doctype.yaml");
    }

    @ApplicationScoped
    public static class BatchHook implements DocLifecycleHook {

        @Inject DocumentService docs;

        @Override public String doctype() { return "TestBatch"; }

        @Override public void onSubmit(Document batch) {
            for (Document item : docs.find("TestBatchItem", Map.of("batch", batch.name(), "docstatus", "0"), 100)) {
                docs.submit("TestBatchItem", item.name());
            }
        }

        @Override public void onCancel(Document batch) {
            for (Document item : docs.find("TestBatchItem", Map.of("batch", batch.name(), "docstatus", "1"), 100)) {
                docs.cancel("TestBatchItem", item.name());
            }
        }
    }

    @ApplicationScoped
    public static class ItemHook implements DocLifecycleHook {

        @Override public String doctype() { return "TestBatchItem"; }

        @Override public void onSubmit(Document item) { item.set("stamp", "submitted with its batch"); }
    }
}
