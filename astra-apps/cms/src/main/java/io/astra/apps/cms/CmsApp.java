package io.astra.apps.cms;

import io.astra.spi.app.AstraApp;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Astra CMS: the first app on the platform. Contributes content DocTypes and hooks via the public SPI only. */
@ApplicationScoped
public class CmsApp implements AstraApp {

    @Override
    public String id() { return "io.astra.cms"; }

    @Override
    public String version() { return "0.1.0"; }

    @Override
    public List<String> doctypeResources() {
        return List.of("META-INF/astra/doctypes/article.doctype.yaml");
    }
}
