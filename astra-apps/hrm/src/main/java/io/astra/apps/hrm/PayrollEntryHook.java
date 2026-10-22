package io.astra.apps.hrm;

import io.astra.apps.hrm.PayrollInputs.SalaryInput;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The monthly payroll run. Saving a draft (re)builds a draft slip for every active employee who matches the
 * designation filter and has an assignment in force: inputs are read in this transaction, the slips are
 * computed in parallel on virtual threads, then written here. Submitting submits every draft slip and posts
 * one accrual voucher for the run: Dr each earning's expense account (gross total), Cr each deduction's liability
 * account (statutory insurance totals), Cr 334 the net pay owed to employees. Cancelling cancels every submitted
 * slip and reverses that voucher once. All of it runs in the entry's own transaction: one failing slip rolls
 * the whole run back.
 */
@ApplicationScoped
public class PayrollEntryHook implements DocLifecycleHook {

    private static final System.Logger LOG = System.getLogger(PayrollEntryHook.class.getName());
    private static final int MAX_EMPLOYEES = 1000;
    /** Account 334 of Circular 200: Phải trả người lao động (net salary owed to employees). */
    static final String SALARY_PAYABLE = "334";

    @Inject DocumentService docs;
    @Inject PayrollInputs inputs;
    @Inject SalaryCalculator calculator;
    @Inject GeneralLedger ledger;

    @Override
    public String doctype() { return "PayrollEntry"; }

    @Override
    public void beforeSave(Document entry) {
        LocalDate start = Values.requireDate(entry, "start_date");
        LocalDate end = Values.requireDate(entry, "end_date");
        if (end.isBefore(start)) throw new DocValidationException("end_date is before start_date");
        if (start.plusMonths(1).isBefore(end)) throw new DocValidationException("a payroll period spans at most one month");
        if (Values.date(entry, "posting_date") == null) entry.set("posting_date", end.toString());
        if (entry.name() == null) return;
        // Slips already carry this period; a different period or designation is a different payroll run.
        Optional<Document> saved = docs.get("PayrollEntry", entry.name());
        if (saved.isPresent() && !slips(entry).isEmpty()) {
            for (String f : List.of("start_date", "end_date", "designation")) {
                if (!Objects.equals(saved.get().getString(f), entry.getString(f))) {
                    throw new DocValidationException(f + " cannot change once salary slips exist; create a new payroll entry");
                }
            }
        }
    }

    @Override
    public void afterSave(Document entry) {
        if (entry.docstatus() != 0) return;
        LocalDate start = Values.requireDate(entry, "start_date");
        LocalDate end = Values.requireDate(entry, "end_date");
        String designation = entry.getString("designation");
        Map<String, String> filter = "All".equals(designation) || designation == null
                ? Map.of("status", "Active") : Map.of("status", "Active", "designation", designation);
        List<Document> employees = docs.find("Employee", filter, MAX_EMPLOYEES).stream()
                .filter(e -> !Values.requireDate(e, "date_of_joining").isAfter(end))
                .filter(e -> Values.date(e, "relieving_date") == null || !Values.date(e, "relieving_date").isBefore(start))
                .sorted(Comparator.comparing(Document::name))
                .toList();
        Map<String, Document> existing = slips(entry).stream()
                .collect(Collectors.toMap(s -> s.getString("employee"), Function.identity(), (a, b) -> a));

        List<SalaryInput> batch = new ArrayList<>();
        List<String> unassigned = new ArrayList<>();
        for (Document e : employees) {
            Document slip = existing.get(e.name());
            if (slip != null && slip.docstatus() != 0) continue;   // submitted or cancelled on its own: leave it
            inputs.load(e, start, end).ifPresentOrElse(batch::add, () -> unassigned.add(e.name()));
        }
        if (!unassigned.isEmpty()) LOG.log(System.Logger.Level.INFO, "Payroll {0}: no salary structure assignment for {1}", entry.name(), unassigned);

        List<Map<String, Object>> computed = calculator.computeAll(batch);
        Map<String, Map<String, Object>> byEmployee = new HashMap<>();
        for (int i = 0; i < batch.size(); i++) byEmployee.put(batch.get(i).employee().name(), computed.get(i));

        ScopedValue.where(SalarySlipHook.PRECOMPUTED, byEmployee).run(() -> {
            for (SalaryInput in : batch) {
                String employee = in.employee().name();
                Map<String, Object> period = new LinkedHashMap<>();
                period.put("start_date", start.toString());
                period.put("end_date", end.toString());
                period.put("posting_date", entry.getString("posting_date"));
                Document slip = existing.get(employee);
                if (slip != null) {
                    docs.update("SalarySlip", slip.name(), period);
                } else {
                    period.put("employee", employee);
                    period.put("payroll_entry", entry.name());
                    docs.insert("SalarySlip", period, entry.owner());
                }
            }
        });
    }

    @Override
    public void onSubmit(Document entry) {
        List<Document> slips = slips(entry);
        if (slips.stream().noneMatch(s -> s.docstatus() != 2)) {
            throw new DocValidationException("payroll entry " + entry.name() + " has no salary slips; check the employees' structure assignments");
        }
        List<Document> submitted = new ArrayList<>();
        for (Document slip : slips) {
            if (slip.docstatus() == 0) docs.submit("SalarySlip", slip.name()).ifPresent(submitted::add);
            else if (slip.docstatus() == 1) submitted.add(slip);
        }
        entry.set("employee_count", submitted.size());
        entry.set("total_gross", sum(submitted, "gross_pay"));
        entry.set("total_deduction", sum(submitted, "total_deduction"));
        entry.set("total_net", sum(submitted, "net_pay"));

        List<Entry> lines = new ArrayList<>();
        byAccount(submitted, "earnings").forEach((account, amount) -> lines.add(Entry.debit(account, null, amount)));
        byAccount(submitted, "deductions").forEach((account, amount) -> lines.add(Entry.credit(account, null, amount)));
        lines.add(Entry.credit(SALARY_PAYABLE, null, BigDecimal.valueOf(sum(submitted, "net_pay"))));
        ledger.post("PayrollEntry", entry.name(), Values.requireDate(entry, "posting_date"), lines,
                "Payroll accrual " + entry.getString("start_date") + " – " + entry.getString("end_date")
                        + " (" + submitted.size() + " slips)");
    }

    @Override
    public void onCancel(Document entry) {
        ScopedValue.where(SalarySlipHook.CANCELLING_ENTRY, entry.name()).run(() -> {
            for (Document slip : slips(entry)) {
                if (slip.docstatus() == 1) docs.cancel("SalarySlip", slip.name());
            }
        });
        ledger.reverse("PayrollEntry", entry.name());
    }

    private List<Document> slips(Document entry) {
        return docs.find("SalarySlip", Map.of("payroll_entry", entry.name()), MAX_EMPLOYEES);
    }

    /** Amounts of the slips' earning or deduction rows, totalled per GL account. */
    @SuppressWarnings("unchecked")
    private static Map<String, BigDecimal> byAccount(List<Document> slips, String field) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (Document slip : slips) {
            if (!(slip.get(field) instanceof List<?> rows)) continue;
            for (Object r : rows) {
                Map<String, Object> row = (Map<String, Object>) r;
                out.merge(row.get("account").toString(), Values.vnd(row.get("amount")), BigDecimal::add);
            }
        }
        return out;
    }

    private static long sum(List<Document> slips, String field) {
        return slips.stream().map(s -> Values.vnd(s.get(field))).reduce(BigDecimal.ZERO, BigDecimal::add).longValueExact();
    }
}
