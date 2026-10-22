package io.astra.apps.hrm;

import io.astra.apps.hrm.PayrollInputs.SalaryInput;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Turns a {@link SalaryInput} into the computed fields of a salary slip.
 *
 * <p>Payment days = working days of the period (Monday–Saturday) − working days outside the employment
 * − Absent − ½ × Half Day − Leave Without Pay. Unmarked working days count as worked. Components flagged
 * {@code depends_on_payment_days} are prorated by payment days ÷ working days; amounts are whole VND.
 * Earnings come first, then deductions, which may depend on the gross; net = gross − deductions.
 */
@ApplicationScoped
public class SalaryCalculator {

    static final String KPI_FUNCTION = "kpi";

    @Inject ScriptRuntime scripts;

    /**
     * Computes a payroll batch with one virtual thread per slip; the inputs are already loaded, so the threads
     * only calculate (the Wasm formulas dominate) and touch no storage. Results keep the input order.
     */
    List<Map<String, Object>> computeAll(List<SalaryInput> inputs) {
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> futures = inputs.stream().map(in -> threads.submit(() -> compute(in))).toList();
            List<Map<String, Object>> out = new ArrayList<>(futures.size());
            for (Future<Map<String, Object>> f : futures) {
                try {
                    out.add(f.get());
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException re) throw re;
                    throw new IllegalStateException("salary calculation failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("payroll batch interrupted", e);
                }
            }
            return out;
        }
    }

    Map<String, Object> compute(SalaryInput in) {
        Document employee = in.employee();
        LocalDate joined = Values.date(employee, "date_of_joining");
        LocalDate relieved = Values.date(employee, "relieving_date");
        LocalDate from = joined != null && joined.isAfter(in.start()) ? joined : in.start();
        LocalDate to = relieved != null && relieved.isBefore(in.end()) ? relieved : in.end();

        int working = Values.workingDays(in.start(), in.end());
        double outside = working - (from.isAfter(to) ? 0 : Values.workingDays(from, to));
        double lost = 0;
        long pieces = 0;
        for (Document att : in.attendance()) {
            LocalDate day = Values.requireDate(att, "attendance_date");
            pieces += Math.round(Values.number(att.get("delivered_pieces")));
            if (!Values.workingDay(day) || day.isBefore(from) || day.isAfter(to)) continue;
            lost += switch (att.getString("status")) {
                case "Absent" -> 1;
                case "Half Day" -> 0.5;
                case "On Leave" -> "Leave Without Pay".equals(att.getString("leave_type")) ? 1 : 0;
                default -> 0;
            };
        }
        double paymentDays = Math.max(0, working - outside - lost);
        Context ctx = new Context(Values.vnd(in.assignment().get("base")), working, paymentDays, pieces);

        List<Map<String, Object>> earnings = new ArrayList<>();
        for (Map<String, Object> row : Values.rows(in.structure(), "earnings")) {
            earnings.add(line(in.components().get(row.get("component").toString()), row.get("amount"), ctx, null));
        }
        additional(in, "Earning", earnings);
        BigDecimal gross = total(earnings);

        List<Map<String, Object>> deductions = new ArrayList<>();
        for (Map<String, Object> row : Values.rows(in.structure(), "deductions")) {
            deductions.add(line(in.components().get(row.get("component").toString()), row.get("amount"), ctx, gross));
        }
        additional(in, "Deduction", deductions);
        BigDecimal deducted = total(deductions);
        BigDecimal net = gross.subtract(deducted);
        if (net.signum() < 0) {
            throw new DocValidationException(employee.name() + ": deductions " + Values.plain(deducted) + " exceed gross pay " + Values.plain(gross));
        }

        Map<String, Object> slip = new LinkedHashMap<>();
        slip.put("employee_name", employee.getString("employee_name"));
        slip.put("designation", employee.getString("designation"));
        slip.put("salary_structure", in.structure().name());
        slip.put("base", ctx.base().longValueExact());
        slip.put("working_days", working);
        slip.put("payment_days", paymentDays);
        slip.put("delivered_pieces", pieces);
        slip.put("earnings", earnings);
        slip.put("deductions", deductions);
        slip.put("gross_pay", gross.longValueExact());
        slip.put("total_deduction", deducted.longValueExact());
        slip.put("net_pay", net.longValueExact());
        return slip;
    }

    /** {@code kpi(pieces, payment_days × 10, working_days)} of a Wasm component, under the component's deadline. */
    static long kpi(ScriptRuntime scripts, Document component, long pieces, long paymentDaysX10, long workingDays) {
        byte[] module = Base64.getDecoder().decode(component.getString("module_wasm"));
        long timeout = Math.round(Values.number(component.get("timeout_ms")));
        return scripts.callLong(module, KPI_FUNCTION, timeout <= 0 ? 50 : timeout, pieces, paymentDaysX10, workingDays);
    }

    private record Context(BigDecimal base, int workingDays, double paymentDays, long pieces) { }

    private Map<String, Object> line(Document c, Object override, Context ctx, BigDecimal gross) {
        BigDecimal rate = BigDecimal.valueOf(Values.number(c.get("rate")));
        BigDecimal amount;
        String basis;
        switch (c.getString("calculation")) {
            case "Percent of Base" -> {
                BigDecimal base = ctx.base();
                BigDecimal cap = Values.vnd(c.get("base_cap"));
                boolean capped = cap.signum() > 0 && base.compareTo(cap) > 0;
                if (capped) base = cap;
                amount = base.multiply(rate).divide(BigDecimal.valueOf(100));
                basis = Values.plain(rate) + "% of " + Values.plain(base) + (capped ? " (capped base)" : " (base)");
            }
            case "Percent of Gross" -> {
                if (gross == null) throw new DocValidationException("component " + c.name() + ": Percent of Gross is only for deductions");
                amount = gross.multiply(rate).divide(BigDecimal.valueOf(100));
                basis = Values.plain(rate) + "% of gross " + Values.plain(gross);
            }
            case "Wasm" -> {
                long v;
                try {
                    v = kpi(scripts, c, ctx.pieces(), Math.round(ctx.paymentDays() * 10), ctx.workingDays());
                } catch (ScriptException e) {
                    throw new DocValidationException("component " + c.name() + ": formula failed (" + e.reason() + "): " + e.getMessage());
                }
                if (v < 0) throw new DocValidationException("component " + c.name() + ": formula returned " + v);
                amount = BigDecimal.valueOf(v);
                basis = "Wasm kpi(" + ctx.pieces() + " pieces, " + Values.plain(ctx.paymentDays()) + "/" + ctx.workingDays() + " days)";
            }
            default -> {
                amount = Values.vnd(override != null ? override : c.get("amount"));
                basis = "fixed";
            }
        }
        if (Boolean.parseBoolean(String.valueOf(c.get("depends_on_payment_days"))) && ctx.paymentDays() < ctx.workingDays()) {
            amount = ctx.workingDays() == 0 ? BigDecimal.ZERO
                    : amount.multiply(BigDecimal.valueOf(ctx.paymentDays())).divide(BigDecimal.valueOf(ctx.workingDays()), 6, RoundingMode.HALF_UP);
            basis += " × " + Values.plain(ctx.paymentDays()) + "/" + ctx.workingDays() + " days";
        }
        return row(c, amount, basis);
    }

    private static void additional(SalaryInput in, String type, List<Map<String, Object>> rows) {
        for (Document ads : in.additional()) {
            Document c = in.components().get(ads.getString("salary_component"));
            if (!type.equals(c.getString("component_type"))) continue;
            rows.add(row(c, Values.vnd(ads.get("amount")), "additional salary " + ads.name()
                    + (ads.getString("reason") == null ? "" : ": " + ads.getString("reason"))));
        }
    }

    private static Map<String, Object> row(Document c, BigDecimal amount, String basis) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("component", c.name());
        r.put("component_name", c.getString("component_name"));
        r.put("account", c.getString("account"));
        r.put("amount", amount.setScale(0, RoundingMode.HALF_UP).longValueExact());
        r.put("basis", basis);
        return r;
    }

    private static BigDecimal total(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> BigDecimal.valueOf(((Number) r.get("amount")).longValue())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
