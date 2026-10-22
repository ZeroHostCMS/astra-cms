package io.astra.server;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

/** Seata enabled, with the BMS datasource on the Dev Services database (schema bms, role astra_bms). */
public class SeataProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "astra.seata.enabled", "true",
                "quarkus.datasource.bms.active", "true",
                "quarkus.datasource.bms.jdbc.url", "${quarkus.datasource.jdbc.url}",
                "quarkus.datasource.bms.jdbc.additional-jdbc-properties.currentSchema", "bms");
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(SeataResource.class));
    }
}
