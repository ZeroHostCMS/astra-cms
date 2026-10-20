package io.astra.kernel.app;

import io.astra.spi.app.AstraApp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/** The set of apps compiled into this image (Tier 0). Runtime tiers will register here later. */
@ApplicationScoped
public class AppRegistry {

    private static final Logger LOG = Logger.getLogger(AppRegistry.class);

    private final List<AstraApp> apps;

    @Inject
    public AppRegistry(Instance<AstraApp> discovered) {
        this.apps = discovered.stream().toList();
        apps.forEach(app -> LOG.infof("App %s %s (tier 0, shared)", app.id(), app.version()));
    }

    public List<AstraApp> apps() { return apps; }
}
