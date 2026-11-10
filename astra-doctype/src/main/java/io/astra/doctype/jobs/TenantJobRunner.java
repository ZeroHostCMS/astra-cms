package io.astra.doctype.jobs;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.store.Tenants;
import io.astra.spi.job.TenantJob;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Runs every {@link TenantJob} once per tenant on each tick. A failing job is logged and does not stop the others. */
@ApplicationScoped
public class TenantJobRunner {

    private static final Logger LOG = Logger.getLogger(TenantJobRunner.class);

    @Inject Instance<TenantJob> jobs;
    @Inject AgroalDataSource dataSource;

    @Scheduled(every = "${astra.jobs.every:2s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = Scheduled.ApplicationNotRunning.class)
    void tick() {
        if (jobs.isUnsatisfied()) return;
        try {
            runAll();
        } catch (RuntimeException e) {
            LOG.warnf("tenant jobs skipped: %s", e.getMessage());
        }
    }

    /** One tick for every tenant; also called directly by tests. */
    public void runAll() {
        for (String tenant : Tenants.list(dataSource)) runFor(tenant);
    }

    public void runFor(String tenant) {
        for (TenantJob job : jobs) {
            try {
                TenantContext.run(tenant, job::run);
            } catch (RuntimeException e) {
                LOG.warnf("job %s failed in tenant %s: %s", job.name(), tenant, e.toString());
            }
        }
    }
}
