package io.astra.spi.app;

import java.util.List;

/**
 * An app is the unit of installation on the Astra platform. It contributes DocTypes
 * (as classpath resources) and, through other SPI interfaces, hooks and extension-point
 * implementations. Astra CMS and Astra ERP modules are apps; so is a tenant's custom module.
 */
public interface AstraApp {

    /** Stable, reverse-DNS style identifier, e.g. {@code io.astra.cms}. */
    String id();

    /** Semantic version of this app build. */
    String version();

    /**
     * Classpath resource paths of DocType definitions (YAML) this app contributes,
     * e.g. {@code META-INF/astra/doctypes/article.doctype.yaml}. Explicit listing keeps
     * loading deterministic inside jars and native images.
     */
    List<String> doctypeResources();
}
