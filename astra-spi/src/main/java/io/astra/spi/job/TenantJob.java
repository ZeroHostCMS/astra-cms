package io.astra.spi.job;

/**
 * Background work an app runs for every tenant, every few seconds, on every pod. The kernel binds the tenant before
 * calling {@link #run()}. Several pods run the same job at the same time, so a job claims each piece of work before
 * doing it (for example with {@link io.astra.spi.doc.DocumentService#updateSystemFields}) and does calls to the
 * outside world after its claim committed, never inside a user's transaction.
 */
public interface TenantJob {

    /** Short name for logs, for example {@code carrier-booking}. */
    String name();

    void run();
}
