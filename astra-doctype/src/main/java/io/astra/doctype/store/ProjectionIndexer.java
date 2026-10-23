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
 *
 * <p>The expression is {@code astra_ext.astra_field(data, 'f')}, a LEAKPROOF alias of {@code ->>} created by the
 * bootstrap superuser: under row-level security PostgreSQL ignores an index whose expression is not
 * leakproof. Each index ends with {@code modified_at DESC}, the order every list uses, so "field = x,
 * newest first, LIMIT n" reads n index entries without a sort. Each index is partial ({@code WHERE field
 * IS NOT NULL}): documents of DocTypes without the field get no entry, so the index stays small and writing
 * such a document does not touch it (every non-HOT update writes an entry into every index it qualifies
 * for). An equality filter implies the predicate, so the planner still matches it. A {@code created_at}
 * index serves the date-range filters every transaction DocType has.
 */
@ApplicationScoped
public class ProjectionIndexer {

    private static final Logger LOG = Logger.getLogger(ProjectionIndexer.class);

    /** The leakproof accessor, created by the bootstrap superuser (deploy/db/05-astra-field.sql); see {@link RowLevelSecurityGuard}. */
    public static final String ACCESSOR_SCHEMA = "astra_ext";
    public static final String ACCESSOR_NAME = "astra_field";
    public static final String ACCESSOR = ACCESSOR_SCHEMA + "." + ACCESSOR_NAME;

    static final String CREATED_INDEX = "astra_doc_created_idx";

    @Inject AgroalDataSource dataSource;
    @Inject DocTypeRegistry registry;

    /** The SQL expression for a field; field names are restricted to snake_case identifiers at load time. */
    static String expression(FieldDefinition field) {
        return ACCESSOR + "(data, '" + field.name() + "')";
    }

    static String indexName(String field) {
        return truncate("astra_doc_pf_" + field);
    }

    /** Index name of the earlier {@code (data->>'f')} projection, which row-level security made unusable. */
    static String legacyIndexName(String field) {
        return truncate("astra_doc_p_" + field);
    }

    private static String truncate(String n) {
        return n.length() > 63 ? n.substring(0, 63) : n;
    }

    void onStart(@Observes StartupEvent ev) {
        Map<String, FieldDefinition> fields = projectedFields();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            conn.setAutoCommit(true); // CREATE INDEX CONCURRENTLY cannot run inside a transaction block
            ensureIndex(conn, CREATED_INDEX, "(tenant_id, doctype, created_at DESC)");
            for (FieldDefinition f : fields.values()) {
                String name = indexName(f.name());
                if (ensureIndex(conn, name, "(tenant_id, doctype, " + expression(f) + ", modified_at DESC) "
                        + "WHERE " + expression(f) + " IS NOT NULL")) {
                    LOG.infof("Projection %s: index %s ready (online, no ALTER TABLE)", f.name(), name);
                    drop(st, legacyIndexName(f.name()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot build projection indexes", e);
        }
    }

    /**
     * Builds the index online unless a valid one exists. An invalid index left by an interrupted build is
     * dropped and rebuilt, unless another session is building it right now (another pod starting).
     *
     * @return whether a valid index exists afterwards
     */
    private boolean ensureIndex(Connection conn, String name, String columns) {
        try (Statement st = conn.createStatement()) {
            IndexState state = state(conn, name);
            if (state == IndexState.VALID) return true;
            if (state == IndexState.BUILDING) {
                LOG.infof("Index %s is being built by another session; the next start checks it again", name);
                return false;
            }
            if (state == IndexState.INVALID) {
                LOG.warnf("Index %s is invalid (an earlier build was interrupted); rebuilding it", name);
                drop(st, name);
            }
            st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + name + " ON astra_doc " + columns);
            return state(conn, name) == IndexState.VALID;
        } catch (SQLException e) {
            // Another pod may have started the same build a moment earlier; the next start retries.
            LOG.warnf("Index %s not built yet: %s", name, e.getMessage());
            return false;
        }
    }

    private enum IndexState { MISSING, VALID, INVALID, BUILDING }

    private static IndexState state(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT i.indisvalid, EXISTS (SELECT 1 FROM pg_stat_progress_create_index p WHERE p.index_relid = i.indexrelid) "
                        + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE c.relname = ? AND pg_table_is_visible(c.oid)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return IndexState.MISSING;
                if (rs.getBoolean(1)) return IndexState.VALID;
                return rs.getBoolean(2) ? IndexState.BUILDING : IndexState.INVALID;
            }
        }
    }

    private static void drop(Statement st, String name) {
        try {
            st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name);
        } catch (SQLException e) {
            LOG.warnf("Index %s not dropped: %s", name, e.getMessage());
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
