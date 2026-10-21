package io.astra.spi.tx;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;

/**
 * Distributed transactions across resources the kernel's own database transaction cannot cover: another
 * module's schema (AT branch) and resources with explicit reserve/confirm/cancel steps (TCC branch).
 * The kernel opens a global transaction around the submit or cancel of DocTypes declared
 * {@code transaction: global}; hooks then join it. Absent when no transaction coordinator is configured.
 */
public interface GlobalTransactions {

    /** Whether a coordinator is connected; hooks fall back to local behaviour when it is not. */
    boolean available();

    /** The id of the global transaction bound to the current call, if any. */
    Optional<String> currentXid();

    /**
     * A module's own datasource, proxied so that statements run inside a global transaction become an AT
     * branch: their before/after images are logged and undone automatically if the global transaction rolls back.
     */
    DataSource atDataSource(String name);

    /** Registers a TCC branch for {@code participant} and runs its prepare step now; commit or rollback follows in phase 2. */
    void tcc(String participant, String key, Map<String, Object> arguments);
}
