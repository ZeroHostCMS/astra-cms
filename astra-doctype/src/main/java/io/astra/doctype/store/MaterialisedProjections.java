package io.astra.doctype.store;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.doctype.FieldDefinition;
import io.astra.spi.doctype.FieldType;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Materialised projections ({@code projected: materialised}): the field's value copied into a typed column
 * {@code m_<field>} of the document table, with a B-tree index, so range filters can use an index under
 * row-level security (casts from JSON text and {@code numeric} comparisons are not leakproof; {@code bigint},
 * {@code float8}, {@code date}, {@code timestamptz} and {@code boolean} comparisons are). Currency is stored as
 * bigint hundredths, so 4,900,000.50 is 490000050.
 *
 * <p>The column is computed by a BEFORE INSERT/UPDATE trigger from {@code data}, so every writer (including an
 * older kernel during a rolling upgrade) keeps it right. The trigger is generated from {@code astra_materialised},
 * the registry of every column any instance declared, so all instances generate the same trigger. Existing rows
 * are backfilled in the background, tenant by tenant, in batches of {@code astra.storage.backfill-batch}; until a
 * column is backfilled, filters on it read the value from {@code data} (correct, but not indexed).
 */
@ApplicationScoped
public class MaterialisedProjections {

    private static final Logger LOG = Logger.getLogger(MaterialisedProjections.class);
    private static final String DATA = "$DATA$";

    /** One materialised column on one table. */
    public record Column(String table, String field, FieldType type, String name, String sqlType) {

        /** SQL computing the column from the JSON document {@code dataRef}; NULL for absent or malformed values, never an error. */
        String expression(String dataRef) {
            return MaterialisedProjections.expression(field, type).replace(DATA, dataRef);
        }

        /** A filter value from a query parameter, converted to the column's type (Currency to hundredths). */
        Object parse(String raw) {
            try {
                return switch (type) {
                    case CURRENCY -> new BigDecimal(raw.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
                    case INT -> new BigDecimal(raw.trim()).setScale(0, RoundingMode.DOWN).longValueExact();
                    case FLOAT -> Double.parseDouble(raw.trim());
                    case DATE -> LocalDate.parse(raw.trim());
                    case DATETIME -> parseInstant(raw.trim());
                    case CHECK -> switch (raw.trim().toLowerCase()) {
                        case "1", "true", "yes" -> true;
                        case "0", "false", "no" -> false;
                        default -> throw new IllegalArgumentException(raw);
                    };
                    default -> throw new IllegalArgumentException(type.name());
                };
            } catch (RuntimeException e) {
                throw new DocValidationException("filter on '" + field + "' must be a " + type.name().toLowerCase() + " value, not '" + raw + "'");
            }
        }
    }

    @Inject AgroalDataSource dataSource;
    @Inject DocTypeRegistry registry;
    @Inject TenantTransactions tx;

    @ConfigProperty(name = "astra.storage.backfill-batch", defaultValue = "2000")
    int backfillBatch;

    private volatile Map<String, Map<String, Column>> byTable = Map.of();
    private final Map<String, Boolean> ready = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    static String columnName(String field) {
        return OnlineIndexes.truncate("m_" + field);
    }

    static String indexName(String table, String field) {
        return OnlineIndexes.truncate(DocTables.indexPrefix(table) + "pm_" + field);
    }

    static String sqlType(FieldType type) {
        return switch (type) {
            case CURRENCY, INT -> "bigint";
            case FLOAT -> "double precision";
            case DATE -> "date";
            case DATETIME -> "timestamptz";
            case CHECK -> "boolean";
            default -> throw new IllegalArgumentException("not materialisable: " + type);
        };
    }

    static String expression(String field, FieldType type) {
        String v = ProjectionIndexer.ACCESSOR + "(" + DATA + ", '" + field + "')";
        return switch (type) {
            case CURRENCY -> "CASE WHEN pg_input_is_valid(" + v + ", 'numeric') THEN CASE WHEN abs((" + v + ")::numeric) < 90000000000000000 "
                    + "THEN round((" + v + ")::numeric * 100)::bigint END END";
            case INT -> "CASE WHEN pg_input_is_valid(" + v + ", 'numeric') THEN CASE WHEN abs((" + v + ")::numeric) < 9000000000000000000 "
                    + "THEN trunc((" + v + ")::numeric)::bigint END END";
            case FLOAT -> "CASE WHEN pg_input_is_valid(" + v + ", 'double precision') THEN (" + v + ")::double precision END";
            case DATE -> "CASE WHEN pg_input_is_valid(" + v + ", 'date') THEN (" + v + ")::date END";
            case DATETIME -> "CASE WHEN pg_input_is_valid(" + v + ", 'timestamptz') THEN (" + v + ")::timestamptz END";
            case CHECK -> "CASE WHEN pg_input_is_valid(" + v + ", 'boolean') THEN (" + v + ")::boolean END";
            default -> throw new IllegalArgumentException("not materialisable: " + type);
        };
    }

    static OffsetDateTime parseInstant(String raw) {
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                return LocalDate.parse(raw).atStartOfDay().atOffset(ZoneOffset.UTC);
            }
        }
    }

    /** The materialised column of {@code field} on {@code table}, if the field is declared materialised there. */
    public Optional<Column> column(String table, String field) {
        return Optional.ofNullable(byTable.getOrDefault(table, Map.of()).get(field));
    }

    /** Whether every existing row has the column filled, so filters may read the column instead of {@code data}. */
    public boolean ready(Column c) {
        return ready.getOrDefault(key(c), false);
    }

    // Before ProjectionIndexer; after MonthPartitions (the partitioned table and its partitions exist).
    void onStart(@Observes @Priority(200) StartupEvent ev) {
        Map<String, Map<String, Column>> columns = declared();
        byTable = columns;
        if (columns.isEmpty()) return;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            conn.setAutoCommit(true);
            st.execute("SELECT pg_advisory_lock(hashtext('astra_materialised'))");
            try {
                st.execute("SET lock_timeout = '10s'");
                for (Map<String, Column> cols : columns.values()) {
                    for (Column c : cols.values()) register(conn, c);
                }
                for (Map<String, Column> cols : columns.values()) {
                    for (Column c : cols.values()) {
                        st.execute("ALTER TABLE " + c.table() + " ADD COLUMN IF NOT EXISTS " + c.name() + " " + c.sqlType());
                    }
                }
                for (String table : columns.keySet()) generateTrigger(conn, table);
            } finally {
                st.execute("RESET lock_timeout");
                st.execute("SELECT pg_advisory_unlock(hashtext('astra_materialised'))");
            }
            for (Map<String, Column> cols : columns.values()) {
                for (Column c : cols.values()) {
                    OnlineIndexes.ensure(conn, c.table(), indexName(c.table(), c.field()),
                            "(tenant_id, doctype, " + c.name() + ") WHERE " + c.name() + " IS NOT NULL");
                }
            }
            refreshReady(conn);
        } catch (SQLException e) {
            // Without the column or trigger, filters keep reading data: slower, never wrong.
            LOG.errorf("Materialised projections not set up (%s); range filters read the JSON document until the next start", e.getMessage());
            return;
        }
        Thread.ofVirtual().name("astra-materialise").start(this::backfillAll);
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
    }

    /** Materialised fields of the registered DocTypes, by table; a field name must keep one type per table. */
    private Map<String, Map<String, Column>> declared() {
        Map<String, Map<String, Column>> out = new LinkedHashMap<>();
        for (DocTypeDefinition def : registry.all()) {
            String table = DocTables.of(def);
            for (FieldDefinition f : def.fields()) {
                if (!f.materialised()) continue;
                Column c = new Column(table, f.name(), f.type(), columnName(f.name()), sqlType(f.type()));
                Column prior = out.computeIfAbsent(table, k -> new LinkedHashMap<>()).putIfAbsent(f.name(), c);
                if (prior != null && !prior.sqlType().equals(c.sqlType())) {
                    throw new IllegalStateException("materialised field '" + f.name() + "' is " + prior.type() + " in one DocType and "
                            + f.type() + " in " + def.name() + "; DocTypes sharing a table must give a materialised field one type");
                }
            }
        }
        return out;
    }

    private static void register(Connection conn, Column c) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO astra_materialised (table_name, column_name, field, sql_type, expression) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (table_name, column_name) DO NOTHING")) {
            ps.setString(1, c.table());
            ps.setString(2, c.name());
            ps.setString(3, c.field());
            ps.setString(4, c.sqlType());
            ps.setString(5, expression(c.field(), c.type()));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sql_type FROM astra_materialised WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, c.table());
            ps.setString(2, c.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (!rs.getString(1).equals(c.sqlType())) {
                    throw new IllegalStateException("materialised column " + c.table() + "." + c.name() + " exists as " + rs.getString(1)
                            + " but " + c.field() + " is now " + c.type() + "; changing the type of a materialised field needs a migration");
                }
            }
        }
    }

    /** Regenerates the table's trigger function from every registered column of that table (all instances agree). */
    private static void generateTrigger(Connection conn, String table) throws SQLException {
        StringBuilder body = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT column_name, expression FROM astra_materialised WHERE table_name = ? ORDER BY column_name")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    body.append("    NEW.").append(rs.getString(1)).append(" := ")
                            .append(rs.getString(2).replace(DATA, "NEW.data")).append(";\n");
                }
            }
        }
        String function = table + "_materialise";
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $fn$\nBEGIN\n"
                    + body + "    RETURN NEW;\nEND $fn$");
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_trigger WHERE tgname = '" + function + "' AND tgrelid = '" + table + "'::regclass")) {
                if (!rs.next()) {
                    st.execute("CREATE TRIGGER " + function + " BEFORE INSERT OR UPDATE OF data ON " + table
                            + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
                }
            }
        }
    }

    private void refreshReady(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name, column_name, backfilled_at IS NOT NULL FROM astra_materialised")) {
            while (rs.next()) ready.put(rs.getString(1) + "." + rs.getString(2), rs.getBoolean(3));
        }
    }

    private static String key(Column c) {
        return c.table() + "." + c.name();
    }

    private boolean allReady() {
        return byTable.values().stream().flatMap(m -> m.values().stream()).allMatch(this::ready);
    }

    /** Backfills every column this instance declared that is not yet complete; then waits for the ones another instance owns. */
    private void backfillAll() {
        for (Map<String, Column> cols : byTable.values()) {
            for (Column c : cols.values()) {
                if (!running) return;
                if (!ready(c)) backfill(c);
            }
        }
        while (running && !allReady()) {
            try {
                Thread.sleep(Duration.ofSeconds(30));
                try (Connection conn = dataSource.getConnection()) {
                    refreshReady(conn);
                }
            } catch (InterruptedException e) {
                return;
            } catch (SQLException e) {
                LOG.warnf("Materialised projections: state not refreshed: %s", e.getMessage());
            }
        }
    }

    /** Fills the column of {@code field} on {@code table} for every existing row now, on the calling thread (operations, tests). */
    public void refill(String table, String field) {
        backfill(column(table, field).orElseThrow(() -> new IllegalArgumentException("no materialised column for " + table + "." + field)));
    }

    /** Fills {@code c} for every existing row, tenant by tenant, DocType by DocType, by name ranges; then marks it ready. */
    void backfill(Column c) {
        String lockKey = "astra_backfill:" + key(c);
        try (Connection lock = dataSource.getConnection(); Statement st = lock.createStatement()) {
            lock.setAutoCommit(true);
            try (ResultSet rs = st.executeQuery("SELECT pg_try_advisory_lock(hashtext('" + lockKey + "'))")) {
                rs.next();
                if (!rs.getBoolean(1)) return;   // another instance is on it
            }
            try {
                List<String> tenants = Tenants.list(dataSource);
                List<String> doctypes = new ArrayList<>();
                for (DocTypeDefinition def : registry.all()) {
                    if (DocTables.of(def).equals(c.table()) && def.field(c.field()).filter(FieldDefinition::materialised).isPresent()) {
                        doctypes.add(def.name());
                    }
                }
                long filled = 0;
                for (String tenant : tenants) {
                    for (String doctype : doctypes) {
                        String last = "";
                        while (running) {
                            String from = last;
                            Object[] batch = TenantContext.call(tenant, () -> tx.inTenantTx(conn -> fillBatch(conn, c, doctype, from)));
                            if (batch == null) break;
                            last = (String) batch[0];
                            filled += (Integer) batch[1];
                        }
                    }
                }
                if (!running) return;
                try (PreparedStatement ps = lock.prepareStatement(
                        "UPDATE astra_materialised SET backfilled_at = now() WHERE table_name = ? AND column_name = ?")) {
                    ps.setString(1, c.table());
                    ps.setString(2, c.name());
                    ps.executeUpdate();
                }
                ready.put(key(c), true);
                LOG.infof("Materialised projection %s.%s ready (%d existing rows filled); range filters use its index", c.table(), c.name(), filled);
            } finally {
                st.execute("SELECT pg_advisory_unlock(hashtext('" + lockKey + "'))");
            }
        } catch (SQLException | IllegalStateException e) {
            LOG.warnf("Materialised projection %s.%s not backfilled (%s); filters read the JSON document until it is", c.table(), c.name(), e.getMessage());
        }
    }

    /** One batch: the next {@code backfill-batch} names after {@code after}; returns {last name, rows filled} or null at the end. */
    private Object[] fillBatch(Connection conn, Column c, String doctype, String after) throws SQLException {
        String last;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT max(name), count(*) FROM (SELECT name FROM " + c.table() + " WHERE doctype = ? AND name > ? ORDER BY name LIMIT ?) b")) {
            ps.setString(1, doctype);
            ps.setString(2, after);
            ps.setInt(3, backfillBatch);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(2) == 0) return null;
                last = rs.getString(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + c.table() + " SET " + c.name() + " = " + c.expression("data") + " WHERE doctype = ? AND name > ? AND name <= ? "
                        + "AND " + c.name() + " IS NULL AND " + ProjectionIndexer.ACCESSOR + "(data, '" + c.field() + "') IS NOT NULL")) {
            ps.setString(1, doctype);
            ps.setString(2, after);
            ps.setString(3, last);
            return new Object[] {last, ps.executeUpdate()};
        }
    }
}
