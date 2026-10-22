package io.astra.doctype.store;

import io.quarkus.flyway.FlywayConfigurationCustomizer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Runs the tenant isolation guard before Flyway migrates the default datasource at start. Quarkus migrates
 * before {@code StartupEvent} observers run, so without this a kernel started as a superuser would apply the
 * migrations (and own the tables it creates) before refusing to start.
 */
@Singleton
public class RowLevelSecurityFlywayCustomizer implements FlywayConfigurationCustomizer {

    @Inject RowLevelSecurityGuard guard;

    @Override
    public void customize(FluentConfiguration configuration) {
        guard.check(configuration.getDataSource());
    }
}
