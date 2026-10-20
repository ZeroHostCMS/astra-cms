package io.astra.doctype.store;

import io.astra.spi.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Naming series such as {@code ART-.#####}: the literal prefix, then a zero-padded counter whose width
 * is the number of {@code #}. Counters live per tenant in {@code astra_series}; the row lock taken by
 * the upsert makes the sequence gap-free within the caller's transaction and safe across pods.
 */
@ApplicationScoped
public class SeriesService {

    String next(Connection conn, String pattern) throws SQLException {
        int hashAt = pattern.indexOf('#');
        String prefix = (hashAt < 0 ? pattern : pattern.substring(0, hashAt)).replace(".", "");
        int width = hashAt < 0 ? 5 : pattern.length() - hashAt;

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO astra_series (tenant_id, series_key, current) VALUES (?, ?, 1) "
                        + "ON CONFLICT (tenant_id, series_key) DO UPDATE SET current = astra_series.current + 1 "
                        + "RETURNING current")) {
            ps.setString(1, TenantContext.require());
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return prefix + String.format("%0" + width + "d", rs.getLong(1));
            }
        }
    }
}
