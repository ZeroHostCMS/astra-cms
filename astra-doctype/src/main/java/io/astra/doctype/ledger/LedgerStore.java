package io.astra.doctype.ledger;

import io.astra.doctype.registry.DocTypeRegistry;
import io.astra.doctype.store.TenantTransactions;
import io.astra.kernel.tenant.TenantRequest;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.ledger.GeneralLedger;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The append-only general ledger in {@code astra_ledger}. Postings run in the current tenant
 * transaction (see {@link TenantTransactions}), so a voucher commits or rolls back with its document.
 */
@ApplicationScoped
public class LedgerStore implements GeneralLedger {

    @Inject TenantTransactions tx;
    @Inject DocTypeRegistry registry;

    /** DocType whose documents form the chart of accounts; postings to unknown accounts are refused. */
    @ConfigProperty(name = "astra.ledger.account-doctype", defaultValue = "Account")
    String accountDoctype;

    private final Set<YearMonth> partitions = ConcurrentHashMap.newKeySet();

    @Override
    public void post(String voucherType, String voucherNo, LocalDate postingDate, List<Entry> entries, String remarks) {
        List<Entry> lines = entries.stream().map(LedgerStore::rounded)
                .filter(e -> e.debit().signum() != 0 || e.credit().signum() != 0).toList();
        if (lines.isEmpty()) return;
        BigDecimal debit = lines.stream().map(Entry::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = lines.stream().map(Entry::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debit.compareTo(credit) != 0) {
            throw new DocValidationException("unbalanced voucher " + voucherNo + ": debit " + debit + " ≠ credit " + credit);
        }
        tx.inTenantTx(conn -> {
            requireAccounts(conn, lines);
            ensurePartition(conn, postingDate);
            insert(conn, voucherType, voucherNo, postingDate, lines, false, remarks);
            return null;
        });
    }

    @Override
    public void reverse(String voucherType, String voucherNo) {
        tx.inTenantTx(conn -> {
            List<Entry> original = new ArrayList<>();
            boolean reversed = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT account, party, debit, credit, is_reversal FROM astra_ledger WHERE voucher_type = ? AND voucher_no = ? ORDER BY id")) {
                ps.setString(1, voucherType);
                ps.setString(2, voucherNo);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getBoolean(5)) reversed = true;
                        else original.add(new Entry(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)));
                    }
                }
            }
            if (reversed) throw new DocValidationException("voucher " + voucherNo + " is already reversed");
            if (original.isEmpty()) return null;
            LocalDate today = LocalDate.now();
            ensurePartition(conn, today);
            List<Entry> mirror = original.stream().map(e -> new Entry(e.account(), e.party(), e.credit(), e.debit())).toList();
            insert(conn, voucherType, voucherNo, today, mirror, true, "Reversal of " + voucherNo);
            return null;
        });
    }

    @Override
    public BigDecimal balance(String account, String party) {
        return tx.inTenantTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT coalesce(sum(debit - credit), 0) FROM astra_ledger WHERE account = ? AND (?::text IS NULL OR party = ?)")) {
                ps.setString(1, account);
                ps.setString(2, party);
                ps.setString(3, party);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBigDecimal(1);
                }
            }
        });
    }

    /** Per account: total debit, total credit and balance, for the current tenant. */
    public List<Map<String, Object>> trialBalance() {
        return tx.inTenantTx(conn -> query(conn,
                "SELECT account, sum(debit) AS debit, sum(credit) AS credit, sum(debit - credit) AS balance "
                        + "FROM astra_ledger GROUP BY account ORDER BY account", List.of()));
    }

    public List<Map<String, Object>> entries(String voucherNo, String account, int limit) {
        return tx.inTenantTx(conn -> query(conn,
                "SELECT id, posting_date, account, party, debit, credit, voucher_type, voucher_no, is_reversal, remarks, "
                        + "posted_by, posted_at, tableoid::regclass::text AS partition FROM astra_ledger "
                        + "WHERE (?::text IS NULL OR voucher_no = ?) AND (?::text IS NULL OR account = ?) "
                        + "ORDER BY id DESC LIMIT ?", Arrays.asList(nullable(voucherNo), nullable(voucherNo), nullable(account), nullable(account), limit)));
    }

    /** The partitions of the ledger and how many of this tenant's rows each holds. */
    public List<Map<String, Object>> partitions() {
        return tx.inTenantTx(conn -> query(conn,
                "SELECT c.relname AS partition, pg_get_expr(c.relpartbound, c.oid) AS bounds, "
                        + "(SELECT count(*) FROM astra_ledger l WHERE l.tableoid = c.oid) AS rows "
                        + "FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
                        + "WHERE i.inhparent = 'astra_ledger'::regclass ORDER BY c.relname", List.of()));
    }

    /** Creates monthly partitions ahead of time; called at startup and lazily before a posting. */
    public void ensurePartition(Connection conn, LocalDate date) throws SQLException {
        YearMonth month = YearMonth.from(date);
        if (partitions.contains(month)) return;
        try (PreparedStatement ps = conn.prepareStatement("SELECT astra_ledger_ensure_partition(?)")) {
            ps.setDate(1, Date.valueOf(date));
            ps.execute();
        }
        partitions.add(month);
    }

    private void insert(Connection conn, String voucherType, String voucherNo, LocalDate date, List<Entry> lines,
                        boolean reversal, String remarks) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO astra_ledger (tenant_id, posting_date, account, party, debit, credit, voucher_type, voucher_no, "
                        + "is_reversal, remarks, posted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Entry e : lines) {
                ps.setString(1, TenantContext.require());
                ps.setDate(2, Date.valueOf(date));
                ps.setString(3, e.account());
                ps.setString(4, e.party());
                ps.setBigDecimal(5, e.debit());
                ps.setBigDecimal(6, e.credit());
                ps.setString(7, voucherType);
                ps.setString(8, voucherNo);
                ps.setBoolean(9, reversal);
                ps.setString(10, remarks);
                ps.setString(11, currentUser());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void requireAccounts(Connection conn, List<Entry> lines) throws SQLException {
        if (registry.find(accountDoctype).isEmpty()) return;
        for (String account : lines.stream().map(Entry::account).distinct().toList()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM astra_doc WHERE doctype = ? AND name = ?")) {
                ps.setString(1, accountDoctype);
                ps.setString(2, account);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new DocValidationException("account " + account + " is not in the chart of accounts");
                }
            }
        }
    }

    private static Entry rounded(Entry e) {
        if (e.debit().signum() < 0 || e.credit().signum() < 0) {
            throw new DocValidationException("ledger amounts must not be negative (account " + e.account() + ")");
        }
        return new Entry(e.account(), e.party(), e.debit().setScale(2, RoundingMode.HALF_UP), e.credit().setScale(2, RoundingMode.HALF_UP));
    }

    private static String currentUser() {
        var request = Arc.container().requestContext().isActive() ? Arc.container().instance(TenantRequest.class).get() : null;
        return request != null && request.userId() != null ? request.userId() : "system";
    }

    private static Object nullable(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static List<Map<String, Object>> query(Connection conn, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= n; c++) {
                        Object v = rs.getObject(c);
                        row.put(rs.getMetaData().getColumnLabel(c), v instanceof Date d ? d.toLocalDate().toString() : v);
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }
}
