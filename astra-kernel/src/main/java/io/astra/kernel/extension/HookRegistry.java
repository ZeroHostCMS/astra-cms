package io.astra.kernel.extension;

import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Collects every {@link DocLifecycleHook} contributed by installed apps and precomputes the
 * dispatch chain per DocType once, so the hot path is a list iteration with no lookups by name.
 */
@ApplicationScoped
public class HookRegistry {

    private static final Logger LOG = Logger.getLogger(HookRegistry.class);

    private final Map<String, List<DocLifecycleHook>> chains;

    @Inject
    public HookRegistry(Instance<DocLifecycleHook> hooks) {
        this.chains = hooks.stream().collect(Collectors.groupingBy(DocLifecycleHook::doctype));
        chains.forEach((doctype, chain) ->
                LOG.infof("DocType %s: %d lifecycle hook(s) [%s]", doctype, chain.size(),
                        chain.stream().map(h -> h.getClass().getSimpleName()).collect(Collectors.joining(", "))));
    }

    public void beforeSave(Document doc) {
        for (DocLifecycleHook hook : chains.getOrDefault(doc.doctype(), List.of())) {
            hook.beforeSave(doc);
        }
    }

    public void afterSave(Document doc) {
        for (DocLifecycleHook hook : chains.getOrDefault(doc.doctype(), List.of())) {
            hook.afterSave(doc);
        }
    }

    public void onSubmit(Document doc) {
        for (DocLifecycleHook hook : chains.getOrDefault(doc.doctype(), List.of())) {
            hook.onSubmit(doc);
        }
    }

    public void onCancel(Document doc) {
        for (DocLifecycleHook hook : chains.getOrDefault(doc.doctype(), List.of())) {
            hook.onCancel(doc);
        }
    }
}
