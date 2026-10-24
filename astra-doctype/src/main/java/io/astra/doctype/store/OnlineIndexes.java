package io.astra.doctype.store;

import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds indexes without blocking writes. On a plain table: {@code CREATE INDEX CONCURRENTLY}. On a partitioned
 * table, where PostgreSQL does not allow {@code CONCURRENTLY} on the parent: an index {@code ON ONLY} the parent,
 * one concurrent build per partition, each attached to the parent index, which turns valid once all are attached.
 * Partitions created later inherit the parent index. Callers pass an autocommit connection.
 */
final class OnlineIndexes {

    private static final Logger LOG = Logger.getLogger(OnlineIndexes.class);

    enum State { MISSING, VALID, INVALID, BUILDING }

    private OnlineIndexes() { }

    /**
     * Ensures {@code name} exists and is valid on {@code table} with {@code definition} (columns and optional
     * WHERE clause). An invalid index left by an interrupted build is dropped and rebuilt, unless another session
     * is building it right now (another instance starting).
     *
     * @return whether a valid index exists afterwards
     */
    static boolean ensure(Connection conn, String table, String name, String definition) {
        try {
            return partitioned(conn, table) ? ensurePartitioned(conn, table, name, definition) : ensurePlain(conn, table, name, definition);
        } catch (SQLException e) {
            LOG.warnf("Index %s on %s not built yet: %s", name, table, e.getMessage());
            return false;
        }
    }

    private static boolean ensurePlain(Connection conn, String table, String name, String definition) throws SQLException {
        State state = state(conn, name);
        if (state == State.VALID) return true;
        if (state == State.BUILDING) {
            LOG.infof("Index %s is being built by another session; the next start checks it again", name);
            return false;
        }
        try (Statement st = conn.createStatement()) {
            if (state == State.INVALID) {
                LOG.warnf("Index %s is invalid (an earlier build was interrupted); rebuilding it", name);
                drop(conn, name);
            }
            st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + name + " ON " + table + " " + definition);
        }
        return state(conn, name) == State.VALID;
    }

    private static boolean ensurePartitioned(Connection conn, String table, String name, String definition) throws SQLException {
        if (state(conn, name) == State.VALID) return true;
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS " + name + " ON ONLY " + table + " " + definition);
            for (String partition : partitions(conn, table)) {
                if (attachedChild(conn, name, partition) != null) continue;   // already carries an attached index
                String suffix = partition.startsWith(table + "_") ? partition.substring(table.length() + 1)
                        : Integer.toHexString(partition.hashCode());
                String child = truncate(name + "_" + suffix);
                State s = state(conn, child);
                if (s == State.INVALID) drop(conn, child);
                if (s != State.VALID) st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + child + " ON " + partition + " " + definition);
                st.execute("ALTER INDEX " + name + " ATTACH PARTITION " + child);
            }
        }
        return state(conn, name) == State.VALID;
    }

    /** The index of {@code partition} already attached to {@code parentIndex}, or null. */
    private static String attachedChild(Connection conn, String parentIndex, String partition) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_index x ON x.indexrelid = c.oid "
                        + "WHERE i.inhparent = to_regclass(?) AND x.indrelid = to_regclass(?)")) {
            ps.setString(1, parentIndex);
            ps.setString(2, partition);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static boolean partitioned(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT relkind = 'p' FROM pg_class WHERE oid = to_regclass(?)")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    static List<String> partitions(Connection conn, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = to_regclass(?) ORDER BY 1")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    static State state(Connection conn, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT i.indisvalid, EXISTS (SELECT 1 FROM pg_stat_progress_create_index p WHERE p.index_relid = i.indexrelid) "
                        + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE c.relname = ? AND pg_table_is_visible(c.oid)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return State.MISSING;
                if (rs.getBoolean(1)) return State.VALID;
                return rs.getBoolean(2) ? State.BUILDING : State.INVALID;
            }
        }
    }

    static void drop(Connection conn, String name) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name);
        } catch (SQLException e) {
            LOG.warnf("Index %s not dropped: %s", name, e.getMessage());
        }
    }

    static String truncate(String n) {
        return n.length() > 63 ? n.substring(0, 63) : n;
    }
}
