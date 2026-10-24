package io.astra.doctype.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The tenants that have documents, for storage maintenance (backfilling a materialised column, moving documents
 * between tables). Row-level security hides other tenants from the kernel's own role, so the list comes from
 * {@code astra_ext.tenants()}, a SECURITY DEFINER function the bootstrap superuser creates
 * (deploy/db/05-astra-field.sql). It returns tenant identifiers only, never document contents.
 */
final class Tenants {

    private Tenants() { }

    static List<String> list(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            conn.setAutoCommit(true);
            try (ResultSet rs = st.executeQuery("SELECT to_regprocedure('astra_ext.tenants()') IS NOT NULL")) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    throw new IllegalStateException("function astra_ext.tenants() is missing; run deploy/db/05-astra-field.sql as the database superuser");
                }
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT t FROM astra_ext.tenants() AS t ORDER BY 1")) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot list tenants: " + e.getMessage(), e);
        }
    }
}
