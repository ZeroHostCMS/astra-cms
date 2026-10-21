package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.tx.GlobalTransactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * Submitting an order is one global transaction (CustomerOrder is {@code transaction: global}): BMS
 * reserves the shipping fee against the customer's credit limit (Seata AT, in BMS's schema) and OPS
 * reserves a pickup slot for today (Seata TCC, in Redis). If BMS refuses, or anything else in the
 * submit fails, Seata undoes the credit and cancels the slot, and the kernel rolls back its own writes.
 * Without a coordinator the checks are skipped and the order commits locally.
 */
@ApplicationScoped
public class OrderCreditHook implements DocLifecycleHook {

    static final int DAILY_PICKUP_CAPACITY = 200;

    @Inject Instance<GlobalTransactions> transactions;

    @Override
    public String doctype() { return "CustomerOrder"; }

    @Override
    public void onSubmit(Document order) {
        GlobalTransactions tx = coordinator();
        if (tx == null) return;
        CreditLimits.use(tx, order.getString("customer"), fee(order));
        tx.tcc(PickupCapacity.NAME, LocalDate.now().toString(), Map.of("capacity", DAILY_PICKUP_CAPACITY, "order", order.name()));
    }

    @Override
    public void onCancel(Document order) {
        GlobalTransactions tx = coordinator();
        if (tx != null) CreditLimits.release(tx, order.getString("customer"), fee(order));
    }

    private GlobalTransactions coordinator() {
        return transactions.isResolvable() && transactions.get().available() ? transactions.get() : null;
    }

    private static BigDecimal fee(Document order) {
        return BigDecimal.valueOf(Numbers.toDouble(order.get("shipping_fee"))).setScale(2, RoundingMode.HALF_UP);
    }
}
