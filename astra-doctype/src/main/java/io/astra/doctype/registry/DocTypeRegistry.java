package io.astra.doctype.registry;

import io.astra.kernel.app.AppRegistry;
import io.astra.spi.doctype.DocTypeDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Global (tenant-independent) DocType metadata, loaded once from every installed app.
 * Per-tenant overlays will layer on top of this registry in a later phase.
 */
@ApplicationScoped
public class DocTypeRegistry {

    private static final Logger LOG = Logger.getLogger(DocTypeRegistry.class);

    private final Map<String, DocTypeDefinition> doctypes = new LinkedHashMap<>();

    @Inject
    public DocTypeRegistry(AppRegistry apps) {
        apps.apps().forEach(app -> app.doctypeResources().forEach(res -> {
            DocTypeDefinition def = DocTypeLoader.load(app.getClass().getClassLoader(), res);
            DocTypeDefinition previous = doctypes.putIfAbsent(def.name(), def);
            if (previous != null) {
                throw new IllegalStateException("DocType " + def.name() + " declared twice (" + app.id() + ")");
            }
            LOG.infof("DocType %s registered by %s (%d fields)", def.name(), app.id(), def.fields().size());
        }));
    }

    public Optional<DocTypeDefinition> find(String name) { return Optional.ofNullable(doctypes.get(name)); }

    public DocTypeDefinition require(String name) {
        return find(name).orElseThrow(() -> new DocTypeNotFoundException(name));
    }

    public Collection<DocTypeDefinition> all() { return doctypes.values(); }

    public static class DocTypeNotFoundException extends RuntimeException {
        public DocTypeNotFoundException(String name) { super("Unknown DocType: " + name); }
    }
}
