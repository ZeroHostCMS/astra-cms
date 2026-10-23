package io.astra.doctype.store;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns {@code projected: true} fields into expression indexes on the document tables, built online (see
 * {@link OnlineIndexes}) so declaring a new projection never takes a write lock and never runs {@code ALTER TABLE}.
 * Indexes are keyed per field name and table, so DocTypes that share a field share the index; list filters use
 * the same expression, which is what lets the planner pick it up.
 *
 * <p>The expression is {@code astra_ext.astra_field(data, 'f')}, a LEAKPROOF alias of {@code ->>} created by the
 * bootstrap superuser: under row-level security PostgreSQL ignores an index whose expression is not leakproof.
 * Each index ends with {@code modified_at DESC}, the order every list uses, so "field = x, newest first, LIMIT n"
 * reads n index entries without a sort. Each index is partial ({@code WHERE field IS NOT NULL}): documents of
 * DocTypes without the field get no entry, so the index stays small and writing such a document does not touch
 * it. An equality filter implies the predicate, so the planner still matches it. A {@code created_at} index
 * serves the date-range filters every transaction DocType has.
 */
@ApplicationScoped
public class ProjectionIndexer {

    private static final Logger LOG = Logger.getLogger(ProjectionIndexer.class);

    /** The leakproof accessor, created by the bootstrap superuser (deploy/db/05-astra-field.sql); see {@link RowLevelSecurityGuard}. */
    public static final String ACCESSOR_SCHEMA = "astra_ext";
    public static final String ACCESSOR_NAME = "astra_field";
    public static final String ACCESSOR = ACCESSOR_SCHEMA + "." + ACCESSOR_NAME;

    @Inject AgroalDataSource dataSource;
    @Inject DocTypeRegistry registry;

    /** The SQL expression for a field; field names are restricted to snake_case identifiers at load time. */
    static String expression(FieldDefinition field) {
        return ACCESSOR + "(data, '" + field.name() + "')";
    }

    static String indexName(String table, String field) {
        return OnlineIndexes.truncate(DocTables.indexPrefix(table) + "pf_" + field);
    }

    /** Index name of the earlier {@code (data->>'f')} projection, which row-level security made unusable. */
    static String legacyIndexName(String field) {
        return OnlineIndexes.truncate("astra_doc_p_" + field);
    }

    // After MonthPartitions (partitions exist) and MaterialisedProjections (columns exist).
    void onStart(@Observes @Priority(300) StartupEvent ev) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true); // CREATE INDEX CONCURRENTLY cannot run inside a transaction block
            OnlineIndexes.ensure(conn, DocTables.SHARED, "astra_doc_created_idx", "(tenant_id, doctype, created_at DESC)");
            for (Map.Entry<String, Set<String>> e : tablesByField().entrySet()) {
                FieldDefinition f = field(e.getKey());
                for (String table : e.getValue()) {
                    String name = indexName(table, f.name());
                    if (OnlineIndexes.ensure(conn, table, name, "(tenant_id, doctype, " + expression(f) + ", modified_at DESC) "
                            + "WHERE " + expression(f) + " IS NOT NULL")) {
                        LOG.infof("Projection %s: index %s ready (online, no ALTER TABLE)", f.name(), name);
                        if (table.equals(DocTables.SHARED)) OnlineIndexes.drop(conn, legacyIndexName(f.name()));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot build projection indexes", e);
        }
    }

    /** Index status for each projected field of one DocType, read from the PostgreSQL catalog. */
    public List<Map<String, Object>> status(String doctype) {
        List<Map<String, Object>> out = new ArrayList<>();
        DocTypeDefinition def = registry.require(doctype);
        String table = DocTables.of(def);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT i.indisvalid, pg_get_indexdef(i.indexrelid) FROM pg_index i "
                             + "JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = ?")) {
            for (FieldDefinition f : def.fields()) {
                if (!f.projected()) continue;
                String name = f.materialised() ? MaterialisedProjections.indexName(table, f.name()) : indexName(table, f.name());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("field", f.name());
                row.put("kind", f.materialised() ? "materialised" : "expression");
                row.put("table", table);
                row.put("index", name);
                ps.setString(1, name);
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

    /** Expression-projected (not materialised) fields, each with the tables that hold DocTypes declaring it. */
    private Map<String, Set<String>> tablesByField() {
        Map<String, Set<String>> byName = new LinkedHashMap<>();
        registry.all().forEach(def -> def.fields().stream().filter(f -> f.projected() && !f.materialised())
                .forEach(f -> byName.computeIfAbsent(f.name(), k -> new LinkedHashSet<>()).add(DocTables.of(def))));
        return byName;
    }

    private FieldDefinition field(String name) {
        return registry.all().stream().flatMap(d -> d.fields().stream()).filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }
}
