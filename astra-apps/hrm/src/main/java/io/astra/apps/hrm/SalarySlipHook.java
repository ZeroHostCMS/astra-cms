package io.astra.apps.hrm;

import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.Map;

/**
 * Computes a slip on every save of a draft. A slip does not post to the ledger itself: its payroll entry
 * accrues all of its slips in one voucher on submit (see {@link PayrollEntryHook}), and the slip records that
 * voucher. While the entry stays submitted, its slips can only be cancelled by cancelling the entry, which
 * reverses the voucher once. One submitted slip per employee and period start.
 */
@ApplicationScoped
public class SalarySlipHook implements DocLifecycleHook {

    /**
     * Slips a payroll batch has already computed, by employee, bound only while {@link PayrollEntryHook} writes
     * them; the binding is in-process, so a client cannot supply computed values.
     */
    static final ScopedValue<Map<String, Map<String, Object>>> PRECOMPUTED = ScopedValue.newInstance();

    /** Bound to the entry's name while {@link PayrollEntryHook} cancels its own slips. */
    static final ScopedValue<String> CANCELLING_ENTRY = ScopedValue.newInstance();

    @Inject DocumentService docs;
    @Inject PayrollInputs inputs;
    @Inject SalaryCalculator calculator;

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
        String entry = slip.getString("payroll_entry");
        if (entry != null && !entry.isBlank()) slip.set("accrual_voucher", entry);
    }

    @Override
    public void onCancel(Document slip) {
        String entryName = slip.getString("payroll_entry");
        if (entryName == null || entryName.isBlank()) return;
        if (CANCELLING_ENTRY.isBound() && CANCELLING_ENTRY.get().equals(entryName)) return;
        docs.get("PayrollEntry", entryName).filter(e -> e.docstatus() == 1).ifPresent(e -> {
            throw new DocValidationException("salary slip " + slip.name() + " is accrued by payroll entry " + entryName
                    + "; cancel the payroll entry instead");
        });
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
}
