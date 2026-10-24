package io.astra.server;

import io.astra.spi.app.AstraApp;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Test-only app: DocTypes stored in the month-partitioned table, for StorageLayoutTest. */
@ApplicationScoped
public class StorageTestApp implements AstraApp {

    @Override public String id() { return "io.astra.test.storage"; }

    @Override public String version() { return "0.0.0"; }

    @Override public List<String> doctypeResources() {
        return List.of("META-INF/astra/doctypes/test-parcel.doctype.yaml", "META-INF/astra/doctypes/test-parcel-code.doctype.yaml");
    }
}
