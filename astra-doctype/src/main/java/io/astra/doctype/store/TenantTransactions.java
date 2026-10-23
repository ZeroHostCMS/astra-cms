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
                recordCommitLsn(conn);
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

    /**
     * For a write request that reports {@code X-Tx-LSN}: the WAL position read on this connection right after the
     * commit, so it covers the commit without taking a second connection from the pool.
     */
    private static void recordCommitLsn(Connection conn) {
        io.astra.doctype.sync.CommitLsn holder = io.astra.doctype.sync.CommitLsn.ifWanted();
        if (holder == null) return;
        try {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_current_wal_lsn()::text");
                 java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) holder.record(rs.getString(1));
            }
        } catch (SQLException e) {
            // The work is committed; without a recorded position the response filter reads it the old way.
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> { T run(Connection conn) throws Exception; }
}
