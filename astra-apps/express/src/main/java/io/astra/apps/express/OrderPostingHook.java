package io.astra.apps.express;

import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.ledger.GeneralLedger;
import io.astra.spi.ledger.GeneralLedger.Entry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Submitting an order bills its shipping fee (Dr 131 / Cr 511); cancelling it posts the reversal. */
@ApplicationScoped
public class OrderPostingHook implements DocLifecycleHook {

    @Inject GeneralLedger ledger;

    @Override
    public String doctype() { return "CustomerOrder"; }

    @Override
    public void onSubmit(Document order) {
        BigDecimal fee = BigDecimal.valueOf(Numbers.toDouble(order.get("shipping_fee")));
        String customer = order.getString("customer");
        ledger.post("CustomerOrder", order.name(), LocalDate.now(), List.of(
                Entry.debit(Accounts.RECEIVABLE, customer, fee),
                Entry.credit(Accounts.REVENUE, customer, fee)), "Shipping fee " + order.name());
    }

    @Override
    public void onCancel(Document order) {
        ledger.reverse("CustomerOrder", order.name());
    }
}
