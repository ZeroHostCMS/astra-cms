package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import io.astra.spi.ledger.GeneralLedger;
import io.astra.spi.ledger.GeneralLedger.Entry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes a slip on every save of a draft, and on submit posts it to the ledger in the same transaction:
 * Dr each earning's expense account (gross), Cr each deduction's liability account (statutory insurance),
 * Cr 334 the net pay owed to the employee. Cancelling posts the reversal. One submitted slip per employee
 * and period start.
 */
@ApplicationScoped
public class SalarySlipHook implements DocLifecycleHook {

    /** Account 334 of Circular 200: Phải trả người lao động (net salary owed to employees). */
    static final String SALARY_PAYABLE = "334";

    /**
     * Slips a payroll batch has already computed, by employee, bound only while {@link PayrollEntryHook} writes
     * them; the binding is in-process, so a client cannot supply computed values.
     */
    static final ScopedValue<Map<String, Map<String, Object>>> PRECOMPUTED = ScopedValue.newInstance();

    @Inject DocumentService docs;
    @Inject PayrollInputs inputs;
    @Inject SalaryCalculator calculator;
    @Inject GeneralLedger ledger;

    @Override
    public String doctype() { return "SalarySlip"; }

    @Override
    public void beforeSave(Document slip) {
        LocalDate start = Values.requireDate(slip, "start_date");
        LocalDate end = Values.requireDate(slip, "end_date");
        if (end.isBefore(start)) throw new DocValidationException("end_date is before start_date");
        if (Values.date(slip, "posting_date") == null) slip.set("posting_date", end.toString());
        String entryName = slip.getString("payroll_entry");
        if (entryName != null && !entryName.isBlank()) {
            Document entry = docs.get("PayrollEntry", entryName)
                    .orElseThrow(() -> new DocValidationException("field 'payroll_entry': PayrollEntry '" + entryName + "' not found"));
            if (entry.docstatus() != 0) {
                throw new DocValidationException("payroll entry " + entryName + " is " + (entry.docstatus() == 1 ? "submitted" : "cancelled")
                        + "; its slips can no longer change");
            }
            if (!start.equals(Values.date(entry, "start_date")) || !end.equals(Values.date(entry, "end_date"))) {
                throw new DocValidationException("the period must match payroll entry " + entryName);
            }
        }
        requireNoOtherSubmitted(slip);
        String employee = slip.getString("employee");
        Map<String, Object> computed = PRECOMPUTED.isBound() && PRECOMPUTED.get().containsKey(employee)
                ? PRECOMPUTED.get().get(employee)
                : calculator.compute(inputs.load(EmployeeHook.require(docs, slip), start, end)
                        .orElseThrow(() -> new DocValidationException(employee + " has no submitted salary structure assignment by " + end)));
        computed.forEach(slip::set);
    }

    @Override
    public void onSubmit(Document slip) {
        requireNoOtherSubmitted(slip);
        String employee = slip.getString("employee");
        Map<String, BigDecimal> expense = byAccount(slip, "earnings");
        Map<String, BigDecimal> liability = byAccount(slip, "deductions");
        List<Entry> entries = new ArrayList<>();
        expense.forEach((account, amount) -> entries.add(Entry.debit(account, employee, amount)));
        liability.forEach((account, amount) -> entries.add(Entry.credit(account, employee, amount)));
        entries.add(Entry.credit(SALARY_PAYABLE, employee, Values.vnd(slip.get("net_pay"))));
        ledger.post("SalarySlip", slip.name(), Values.requireDate(slip, "posting_date"), entries,
                "Salary " + employee + " " + slip.getString("start_date") + " – " + slip.getString("end_date"));
    }

    @Override
    public void onCancel(Document slip) {
        ledger.reverse("SalarySlip", slip.name());
    }

    private void requireNoOtherSubmitted(Document slip) {
        docs.find("SalarySlip", Map.of("employee", slip.getString("employee"), "start_date", slip.getString("start_date"),
                        "docstatus", "1"), 5).stream()
                .filter(other -> !other.name().equals(slip.name()))
                .findFirst()
                .ifPresent(other -> {
                    throw new DocValidationException(slip.getString("employee") + " already has submitted salary slip "
                            + other.name() + " for the period from " + slip.getString("start_date"));
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BigDecimal> byAccount(Document slip, String field) {
        Map<String, BigDecimal> out = new TreeMap<>();
        Object rows = slip.get(field);
        if (rows instanceof List<?> list) {
            for (Object r : list) {
                Map<String, Object> row = (Map<String, Object>) r;
                out.merge(row.get("account").toString(), Values.vnd(row.get("amount")), BigDecimal::add);
            }
        }
        return out;
    }
}
