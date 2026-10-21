package io.astra.spi.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Double-entry postings to the append-only ledger of the current tenant. Called from lifecycle hooks,
 * a posting joins the triggering document's transaction: the voucher and its entries commit together.
 * Entries are never changed; {@link #reverse} posts mirror entries instead.
 */
public interface GeneralLedger {

    /** One line of a voucher: exactly one of {@code debit} and {@code credit} is positive. */
    record Entry(String account, String party, BigDecimal debit, BigDecimal credit) {

        public static Entry debit(String account, String party, BigDecimal amount) {
            return new Entry(account, party, amount, BigDecimal.ZERO);
        }

        public static Entry credit(String account, String party, BigDecimal amount) {
            return new Entry(account, party, BigDecimal.ZERO, amount);
        }
    }

    /** Posts a balanced voucher (total debit = total credit); zero-amount lines are skipped. */
    void post(String voucherType, String voucherNo, LocalDate postingDate, List<Entry> entries, String remarks);

    /** Posts the mirror of every entry of a voucher, dated today; refuses a voucher already reversed. */
    void reverse(String voucherType, String voucherNo);

    /** Debit minus credit of an account, optionally for one party. */
    BigDecimal balance(String account, String party);
}
