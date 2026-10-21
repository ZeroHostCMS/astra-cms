package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.tenant.TenantContext;
import io.astra.spi.tx.GlobalTransactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The BMS side of a customer's credit, in BMS's own schema. Inside a global transaction these statements
 * are a Seata AT branch: if anything later in the transaction fails, Seata restores the row from its undo log.
 */
final class CreditLimits {

    private CreditLimits() { }

    static String accountKey(String customer) {
        return TenantContext.require() + ":" + customer;
    }

    static void setLimit(GlobalTransactions tx, String customer, BigDecimal limit) {
        run(tx, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO customer_credit (account_key, tenant_id, customer, credit_limit) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (account_key) DO UPDATE SET credit_limit = excluded.credit_limit, updated_at = now()")) {
                ps.setString(1, accountKey(customer));
                ps.setString(2, TenantContext.require());
                ps.setString(3, customer);
                ps.setBigDecimal(4, limit);
                ps.executeUpdate();
            }
        });
    }

    /** Uses {@code amount} of the customer's credit, or refuses; customers without a limit are not checked. */
    static void use(GlobalTransactions tx, String customer, BigDecimal amount) {
        run(tx, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_credit SET used = used + ?, updated_at = now() WHERE account_key = ? AND used + ? <= credit_limit")) {
                ps.setBigDecimal(1, amount);
                ps.setString(2, accountKey(customer));
                ps.setBigDecimal(3, amount);
                if (ps.executeUpdate() == 1) return;
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT credit_limit, used FROM customer_credit WHERE account_key = ?")) {
                ps.setString(1, accountKey(customer));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new DocValidationException("credit limit of " + customer + " exceeded: "
                                + rs.getBigDecimal(2).toPlainString() + " used of " + rs.getBigDecimal(1).toPlainString()
                                + ", this order needs " + amount.toPlainString());
                    }
                }
            }
        });
    }

    static void release(GlobalTransactions tx, String customer, BigDecimal amount) {
        run(tx, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE customer_credit SET used = greatest(used - ?, 0), updated_at = now() WHERE account_key = ?")) {
                ps.setBigDecimal(1, amount);
                ps.setString(2, accountKey(customer));
                ps.executeUpdate();
            }
        });
    }

    private interface Work { void run(Connection conn) throws SQLException; }

    private static void run(GlobalTransactions tx, Work work) {
        try (Connection conn = tx.atDataSource("bms").getConnection()) {
            work.run(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("BMS credit update failed: " + e.getMessage(), e);
        }
    }
}
