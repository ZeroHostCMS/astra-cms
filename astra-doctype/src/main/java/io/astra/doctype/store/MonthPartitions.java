package io.astra.doctype.store;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.spi.doctype.DocTypeDefinition;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Keeps {@code astra_doc_txn} (DocTypes with {@code partition: month}) partitioned: the partitions of the current
 * month and the next {@code months-ahead} exist before rows arrive, created at start and re-checked in the
 * background; month boundaries follow the business time zone. Rows outside every partition land in the default
 * partition, so a late check never fails a write.
 *
 * <p>When a DocType that already has documents in {@code astra_doc} is switched to {@code partition: month}, its
 * documents are moved before the kernel serves requests (startup waits), tenant by tenant in batches of
 * {@code move-batch} rows, each batch one transaction. Listing tenants needs {@code astra_ext.tenants()} from the
 * bootstrap superuser; without it the kernel refuses to start rather than hide those documents.
 */
@ApplicationScoped
public class MonthPartitions {

    private static final Logger LOG = Logger.getLogger(MonthPartitions.class);
    private static final String COLUMNS = "id, tenant_id, doctype, name, docstatus, owner, created_at, modified_at, version, data, amended_from";

    @Inject AgroalDataSource dataSource;
    @Inject DocTypeRegistry registry;
    @Inject TenantTransactions tx;

    @ConfigProperty(name = "astra.storage.partition-time-zone", defaultValue = "UTC")
    String timeZone;

    @ConfigProperty(name = "astra.storage.months-ahead", defaultValue = "2")
    int monthsAhead;

    @ConfigProperty(name = "astra.storage.move-batch", defaultValue = "1000")
    int moveBatch;

    private volatile boolean running = true;

    void onStart(@Observes @Priority(100) StartupEvent ev) {
        ensurePartitions();
        moveExistingDocuments();
        Thread.ofVirtual().name("astra-month-partitions").start(() -> {
            while (running) {
                try {
                    Thread.sleep(Duration.ofHours(6));
                    ensurePartitions();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    LOG.warnf("Month partitions not checked: %s", e.getMessage());
                }
            }
        });
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
    }

    /** Creates the partitions of this month and the next {@code months-ahead}; idempotent and safe across instances. */
    public void ensurePartitions() {
        LocalDate today = LocalDate.now(ZoneId.of(timeZone));
        ensureMonths(today, today.plusMonths(monthsAhead));
    }

    /** Creates the partition of every month from {@code from} to {@code to}. A month whose rows already sit in the
     * default partition cannot get its own partition; it is logged and its rows stay in the default partition. */
    void ensureMonths(LocalDate from, LocalDate to) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT pg_advisory_lock(hashtext('astra_doc_txn_partitions'))");
                try (PreparedStatement ps = conn.prepareStatement("SELECT astra_doc_txn_ensure_partition(?, ?)")) {
                    for (LocalDate m = from.withDayOfMonth(1); !m.isAfter(to); m = m.plusMonths(1)) {
                        ps.setObject(1, m);
                        ps.setString(2, timeZone);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                        } catch (SQLException e) {
                            LOG.warnf("Partition of astra_doc_txn for %s not created: %s", m, e.getMessage());
                        }
                    }
                } finally {
                    st.execute("SELECT pg_advisory_unlock(hashtext('astra_doc_txn_partitions'))");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create month partitions of astra_doc_txn", e);
        }
    }

    /** Moves the documents of month-partitioned DocTypes still in astra_doc; runs at start, safe to run again. */
    public void moveExistingDocuments() {
        List<String> doctypes = registry.all().stream().filter(DocTypeDefinition::partitionedByMonth).map(DocTypeDefinition::name).toList();
        if (doctypes.isEmpty()) return;
        List<String> tenants;
        try {
            tenants = Tenants.list(dataSource);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("DocTypes " + doctypes + " use partition: month, but documents already stored in "
                    + "astra_doc cannot be found and moved: " + e.getMessage(), e);
        }
        try (Connection lock = dataSource.getConnection(); Statement st = lock.createStatement()) {
            lock.setAutoCommit(true);
            st.execute("SELECT pg_advisory_lock(hashtext('astra_doc_txn_move'))");   // one instance moves, the others wait
            try {
                for (String tenant : tenants) {
                    for (String doctype : doctypes) {
                        LocalDate[] range = TenantContext.call(tenant, () -> tx.inTenantTx(conn -> createdRange(conn, doctype)));
                        if (range == null) continue;
                        ensureMonths(range[0], range[1]);   // old documents go to their own month, not the default partition
                        long moved = 0;
                        int n;
                        do {
                            n = TenantContext.call(tenant, () -> tx.inTenantTx(conn -> moveBatch(conn, doctype)));
                            moved += n;
                        } while (n > 0);
                        if (moved > 0) LOG.infof("Moved %d %s documents of tenant %s to astra_doc_txn (partition: month)", moved, doctype, tenant);
                    }
                }
            } finally {
                st.execute("SELECT pg_advisory_unlock(hashtext('astra_doc_txn_move'))");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot move documents to astra_doc_txn", e);
        }
    }

    /** First and last creation day (business time zone) of the DocType's documents still in astra_doc, or null. */
    private LocalDate[] createdRange(Connection conn, String doctype) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT (min(created_at) AT TIME ZONE ?)::date, (max(created_at) AT TIME ZONE ?)::date FROM astra_doc WHERE doctype = ?")) {
            ps.setString(1, timeZone);
            ps.setString(2, timeZone);
            ps.setString(3, doctype);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                LocalDate first = rs.getObject(1, LocalDate.class);
                return first == null ? null : new LocalDate[] {first, rs.getObject(2, LocalDate.class)};
            }
        }
    }

    private int moveBatch(Connection conn, String doctype) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "WITH moved AS (DELETE FROM astra_doc WHERE id IN (SELECT id FROM astra_doc WHERE doctype = ? LIMIT ?) RETURNING " + COLUMNS + ") "
                        + "INSERT INTO astra_doc_txn (" + COLUMNS + ") SELECT " + COLUMNS + " FROM moved")) {
            ps.setString(1, doctype);
            ps.setInt(2, moveBatch);
            return ps.executeUpdate();
        }
    }
}
