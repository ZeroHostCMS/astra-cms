package io.astra.pluginhost.rollout;

import io.astra.spi.app.AstraApp;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The release control plane's own DocTypes: {@code AppRelease} (what can be rolled out) and {@code AppPin}. */
@ApplicationScoped
public class ReleasesApp implements AstraApp {

    @Override
    public String id() { return "io.astra.releases"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public List<String> doctypeResources() {
        return List.of("META-INF/astra/doctypes/app-release.doctype.yaml", "META-INF/astra/doctypes/app-pin.doctype.yaml");
    }
}
