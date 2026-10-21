package io.astra.doctype.sync;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * WAL positions for read-your-writes across modules. A write reports the position after its commit
 * ({@code X-Tx-LSN}); a later read from a replica can demand that position ({@code X-Required-LSN}) and
 * is answered only once the CDC worker's watermark has reached it.
 */
@ApplicationScoped
public class SyncWatermark {

    @Inject AgroalDataSource dataSource;

    /** The current WAL insert position; read after a commit it covers that commit. */
    public String currentLsn() {
        return single("SELECT pg_current_wal_lsn()::text").orElseThrow();
    }

    /** Whether everything up to {@code required} has been applied to the replicas. */
    public boolean reached(String required) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT coalesce(max(last_lsn) >= ?::pg_lsn, false) FROM _cdc_sync_watermark")) {
            ps.setString(1, required);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("invalid LSN '" + required + "'");
        }
    }

    /** Worker progress: replicated position, lag of the last applied change, and bytes still to replay. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pg_current_wal_lsn()::text, w.slot, w.last_lsn::text, w.events, w.lag_ms, w.updated_at, "
                             + "pg_wal_lsn_diff(pg_current_wal_lsn(), w.last_lsn)::bigint "
                             + "FROM (SELECT 1) one LEFT JOIN LATERAL (SELECT * FROM _cdc_sync_watermark ORDER BY updated_at DESC LIMIT 1) w ON true");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            m.put("currentLsn", rs.getString(1));
            m.put("slot", rs.getString(2));
            m.put("replicatedLsn", rs.getString(3));
            m.put("events", rs.getObject(4));
            m.put("lastLagMs", rs.getObject(5));
            m.put("updatedAt", rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant().toString());
            m.put("bytesBehind", rs.getObject(7));
            m.put("workerReporting", rs.getTimestamp(6) != null && System.currentTimeMillis() - rs.getTimestamp(6).getTime() < 5_000);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the sync watermark", e);
        }
        return m;
    }

    private Optional<String> single(String sql) {
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the WAL position", e);
        }
    }
}
