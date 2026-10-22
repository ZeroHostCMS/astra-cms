package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.tenant.TenantContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Payroll setup every HRM test tenant needs, written straight through the kernel: the accounts payroll posts
 * to, the salary components (Vietnamese employee insurance at the statutory 8 % / 1.5 % / 1 %), two submitted
 * structures, and employees NV001–NV004 of whom NV001–NV003 have a submitted assignment.
 */
final class HrmFixtures {

    static final String START = "2026-09-01";
    static final String END = "2026-09-30";

    static final List<List<String>> CHART = List.of(
            List.of("334", "Phải trả người lao động", "Liability"),
            List.of("3383", "Bảo hiểm xã hội", "Liability"),
            List.of("3384", "Bảo hiểm y tế", "Liability"),
            List.of("3386", "Bảo hiểm thất nghiệp", "Liability"),
            List.of("642", "Chi phí quản lý doanh nghiệp", "Expense"));

    private HrmFixtures() { }

    static byte[] module(String name) throws Exception {
        return Files.readAllBytes(Path.of("../astra-apps/hrm/wasm/modules/" + name + ".wasm"));
    }

    static void tenant(DocumentStore store, String tenant) throws Exception {
        String kpi = Base64.getEncoder().encodeToString(module("courier-kpi"));
        TenantContext.run(tenant, () -> {
            if (store.get("Employee", "NV001").isPresent()) return;
            for (List<String> a : CHART) {
                store.insert("Account", Map.of("account_number", a.get(0), "account_name", a.get(1), "root_type", a.get(2)), "fixture");
            }
            component(store, "BASIC", "Lương cơ bản", "Earning", "Percent of Base", Map.of("rate", 100), true, "642");
            component(store, "LUNCH", "Phụ cấp ăn trưa", "Earning", "Fixed", Map.of("amount", 730_000), true, "642");
            component(store, "FUEL", "Phụ cấp xăng xe", "Earning", "Fixed", Map.of("amount", 500_000), true, "642");
            component(store, "KPI", "Thưởng sản lượng", "Earning", "Wasm", Map.of("module_wasm", kpi), false, "642");
            component(store, "BONUS", "Thưởng", "Earning", "Fixed", Map.of(), false, "642");
            component(store, "BHXH", "BHXH (8%)", "Deduction", "Percent of Base", Map.of("rate", 8, "base_cap", 46_800_000), false, "3383");
            component(store, "BHYT", "BHYT (1.5%)", "Deduction", "Percent of Base", Map.of("rate", 1.5, "base_cap", 46_800_000), false, "3384");
            component(store, "BHTN", "BHTN (1%)", "Deduction", "Percent of Base", Map.of("rate", 1, "base_cap", 106_200_000), false, "3386");
            List<Map<String, Object>> insurance = List.of(Map.of("component", "BHXH"), Map.of("component", "BHYT"), Map.of("component", "BHTN"));
            structure(store, "COURIER", "Bưu tá", List.of(Map.of("component", "BASIC"), Map.of("component", "LUNCH"),
                    Map.of("component", "FUEL"), Map.of("component", "KPI")), insurance);
            structure(store, "OFFICE", "Văn phòng", List.of(Map.of("component", "BASIC"), Map.of("component", "LUNCH")), insurance);

            employee(store, "NV001", "Nguyễn Văn An", "Courier", "2024-03-01");
            employee(store, "NV002", "Trần Thị Bình", "Courier", "2025-06-01");
            employee(store, "NV003", "Lê Hoàng Cường", "Office", "2026-09-14");
            employee(store, "NV004", "Phạm Minh Dũng", "Hub Staff", "2025-01-01");   // no assignment: skipped by payroll
            assign(store, "NV001", "COURIER", 7_000_000);
            assign(store, "NV002", "COURIER", 6_500_000);
            assign(store, "NV003", "OFFICE", 12_000_000);
        });
    }

    private static void component(DocumentStore store, String abbr, String name, String type, String calculation,
                                  Map<String, Object> extra, boolean prorate, String account) {
        Map<String, Object> c = new LinkedHashMap<>(Map.of("abbr", abbr, "component_name", name, "component_type", type,
                "calculation", calculation, "depends_on_payment_days", prorate, "account", account));
        c.putAll(extra);
        store.insert("SalaryComponent", c, "fixture");
    }

    private static void structure(DocumentStore store, String code, String name, List<Map<String, Object>> earnings,
                                  List<Map<String, Object>> deductions) {
        store.insert("SalaryStructure", Map.of("code", code, "structure_name", name, "earnings", earnings, "deductions", deductions), "fixture");
        store.submit("SalaryStructure", code);
    }

    private static void employee(DocumentStore store, String id, String name, String designation, String joined) {
        store.insert("Employee", Map.of("employee_id", id, "employee_name", name, "designation", designation,
                "date_of_joining", joined), "fixture");
    }

    private static void assign(DocumentStore store, String employee, String structure, long base) {
        String name = store.insert("SalaryStructureAssignment", Map.of("employee", employee, "salary_structure", structure,
                "from_date", "2026-01-01", "base", base), "fixture").name();
        store.submit("SalaryStructureAssignment", name);
    }
}
