package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.tx.GlobalTransactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.math.BigDecimal;

/** Mirrors a customer's credit limit into BMS's schema, where order submits draw on it. */
@ApplicationScoped
public class CustomerCreditHook implements DocLifecycleHook {

    @Inject Instance<GlobalTransactions> transactions;

    @Override
    public String doctype() { return "Customer"; }

    @Override
    public void afterSave(Document customer) {
        if (customer.get("credit_limit") == null || !transactions.isResolvable() || !transactions.get().available()) return;
        CreditLimits.setLimit(transactions.get(), customer.name(), BigDecimal.valueOf(Numbers.toDouble(customer.get("credit_limit"))));
    }
}
