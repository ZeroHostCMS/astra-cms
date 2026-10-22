package io.astra.server;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

/** Real OIDC: Astra validates access tokens issued by the {@link KeycloakResource} container. */
public class KeycloakProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.http.test-port", "0");   // a free port: other test runs may hold the default
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(KeycloakResource.class));
    }
}
