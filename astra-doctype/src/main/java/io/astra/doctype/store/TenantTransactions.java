package io.astra.doctype.store;

import io.agroal.api.AgroalDataSource;
import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * One database transaction per kernel operation, with the current tenant bound for row-level security.
 * The open connection is carried in a {@link ScopedValue}, so every store that runs inside the scope
 * (documents, ledger) joins the same transaction and commits or rolls back with it.
 */
@ApplicationScoped
public class TenantTransactions {

    private static final ScopedValue<Connection> TX = ScopedValue.newInstance();

    @Inject AgroalDataSource dataSource;

    /**
     * Opens a transaction, binds the tenant for row-level security, runs the work, commits.
     * When a transaction is already open in this scope, the work joins it instead.
     */
    public <T> T inTenantTx(SqlWork<T> work) {
        if (TX.isBound()) {
            try {
                return work.run(TX.get());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        String tenant = TenantContext.require();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('astra.tenant', ?, true)")) {
                    ps.setString(1, tenant);
                    ps.execute();
                }
                T result = ScopedValue.where(TX, conn).call(() -> work.run(conn));
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException re) throw re;
                throw new IllegalStateException(e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("database error", e);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> { T run(Connection conn) throws Exception; }
}
