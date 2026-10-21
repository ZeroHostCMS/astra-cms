package io.astra.doctype.store;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.spi.doctype.FieldDefinition;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@code projected: true} fields into expression indexes on the shared JSONB table, built with
 * {@code CREATE INDEX CONCURRENTLY} so declaring a new projection never takes a write lock and never
 * runs {@code ALTER TABLE}. Indexes are keyed per field name, so DocTypes that share a field share
 * the index; list filters use the same expression, which is what lets the planner pick it up.
 */
@ApplicationScoped
public class ProjectionIndexer {

    private static final Logger LOG = Logger.getLogger(ProjectionIndexer.class);

    @Inject AgroalDataSource dataSource;
    @Inject DocTypeRegistry registry;

    /** The SQL expression for a field; field names are restricted to snake_case identifiers at load time. */
    static String expression(FieldDefinition field) {
        return "(data->>'" + field.name() + "')";
    }

    static String indexName(String field) {
        String n = "astra_doc_p_" + field;
        return n.length() > 63 ? n.substring(0, 63) : n;
    }

    void onStart(@Observes StartupEvent ev) {
        Map<String, FieldDefinition> fields = projectedFields();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            conn.setAutoCommit(true); // CREATE INDEX CONCURRENTLY cannot run inside a transaction block
            for (FieldDefinition f : fields.values()) {
                try {
                    st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + indexName(f.name())
                            + " ON astra_doc (tenant_id, doctype, " + expression(f) + ")");
                    LOG.infof("Projection %s: expression index %s ready (online, no ALTER TABLE)", f.name(), indexName(f.name()));
                } catch (SQLException e) {
                    // Another pod may be building the same index right now; the next start retries.
                    LOG.warnf("Projection %s: index %s not built yet: %s", f.name(), indexName(f.name()), e.getMessage());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot build projection indexes", e);
        }
    }

    /** Index status for each projected field of one DocType, read from the PostgreSQL catalog. */
    public List<Map<String, Object>> status(String doctype) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT i.indisvalid, pg_get_indexdef(i.indexrelid) FROM pg_index i "
                             + "JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = ?")) {
            for (FieldDefinition f : registry.require(doctype).fields()) {
                if (!f.projected()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("field", f.name());
                row.put("index", indexName(f.name()));
                ps.setString(1, indexName(f.name()));
                try (ResultSet rs = ps.executeQuery()) {
                    boolean exists = rs.next();
                    row.put("valid", exists && rs.getBoolean(1));
                    row.put("definition", exists ? rs.getString(2) : null);
                }
                out.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read projection indexes", e);
        }
        return out;
    }

    private Map<String, FieldDefinition> projectedFields() {
        Map<String, FieldDefinition> byName = new LinkedHashMap<>();
        registry.all().forEach(def -> def.fields().stream().filter(FieldDefinition::projected)
                .forEach(f -> byName.putIfAbsent(f.name(), f)));
        return byName;
    }
}
