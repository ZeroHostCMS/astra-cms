package io.astra.apps.express;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.ledger.GeneralLedger;
import io.astra.spi.ledger.GeneralLedger.Entry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Paying collected COD out to a shop. The amount may not exceed what the ledger says is owed (credit
 * balance of 3388 for that customer); submitting posts Dr 3388 / Cr 112, cancelling reverses it.
 */
@ApplicationScoped
public class RemittanceHook implements DocLifecycleHook {

    @Inject GeneralLedger ledger;

    @Override
    public String doctype() { return "CODRemittance"; }

    @Override
    public void beforeSave(Document doc) {
        doc.set("outstanding", outstanding(doc).longValue());
        check(doc);
    }

    @Override
    public void onSubmit(Document doc) {
        check(doc);
        BigDecimal amount = BigDecimal.valueOf(Numbers.toDouble(doc.get("amount")));
        String customer = doc.getString("customer");
        ledger.post("CODRemittance", doc.name(), LocalDate.now(), List.of(
                Entry.debit(Accounts.COD_PAYABLE, customer, amount),
                Entry.credit(Accounts.BANK, null, amount)), "COD payout to " + customer);
    }

    @Override
    public void onCancel(Document doc) {
        ledger.reverse("CODRemittance", doc.name());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private BigDecimal outstanding(Document doc) {
        String customer = doc.getString("customer");
        return customer == null ? BigDecimal.ZERO : ledger.balance(Accounts.COD_PAYABLE, customer).negate();
    }

    private void check(Document doc) {
        BigDecimal amount = BigDecimal.valueOf(Numbers.toDouble(doc.get("amount")));
        BigDecimal owed = outstanding(doc);
        if (amount.signum() <= 0) throw new DocValidationException("amount must be positive");
        if (amount.compareTo(owed) > 0) {
            throw new DocValidationException("amount " + plain(amount) + " exceeds the COD owed to "
                    + doc.getString("customer") + " (" + plain(owed) + ")");
        }
    }
}
