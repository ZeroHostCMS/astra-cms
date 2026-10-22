package io.astra.apps.hrm;

import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP tool: totals of a payroll run and its slips; employee names pass through the kernel's PII masking. */
@ApplicationScoped
public class PayrollSummaryTool implements McpTool {

    private static final String[] STATUS = {"Draft", "Submitted", "Cancelled"};

    @Inject DocumentService docs;

    @Override public String name() { return "payroll_summary"; }

    @Override public String description() {
        return "Summary of a payroll entry (period, status, gross, deductions, net) with one line per salary slip; "
                + "omit payroll_entry for the most recent entries.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties",
                Map.of("payroll_entry", Map.of("type", "string", "description", "payroll entry name, e.g. PAY-2609-001")));
    }

    @Override public List<String> reads() { return List.of("PayrollEntry", "SalarySlip"); }

    @Override public Object call(Map<String, Object> arguments) {
        Object only = arguments.get("payroll_entry");
        if (only != null) {
            Document entry = docs.get("PayrollEntry", only.toString())
                    .orElseThrow(() -> new DocValidationException("payroll entry " + only + " not found"));
            return summary(entry, true);
        }
        return docs.find("PayrollEntry", Map.of(), 5).stream().map(e -> summary(e, false)).toList();
    }

    private Map<String, Object> summary(Document entry, boolean withSlips) {
        List<Document> slips = docs.find("SalarySlip", Map.of("payroll_entry", entry.name()), 1000);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payroll_entry", entry.name());
        m.put("period", entry.getString("start_date") + " – " + entry.getString("end_date"));
        m.put("status", STATUS[entry.docstatus()]);
        m.put("slips", slips.size());
        m.put("gross_vnd", total(slips, "gross_pay"));
        m.put("deductions_vnd", total(slips, "total_deduction"));
        m.put("net_vnd", total(slips, "net_pay"));
        if (withSlips) {
            m.put("items", slips.stream().map(s -> {
                Map<String, Object> data = new LinkedHashMap<>();
                for (String f : List.of("employee", "employee_name", "designation", "payment_days", "gross_pay", "total_deduction", "net_pay")) {
                    data.put(f, s.get(f));
                }
                data.put("status", STATUS[s.docstatus()]);
                return Map.of("doctype", "SalarySlip", "name", s.name(), "data", data);
            }).toList());
        }
        return m;
    }

    private static long total(List<Document> slips, String field) {
        return slips.stream().filter(s -> s.docstatus() != 2).mapToLong(s -> Values.vnd(s.get(field)).longValueExact()).sum();
    }
}
