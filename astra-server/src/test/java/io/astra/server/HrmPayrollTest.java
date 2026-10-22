package io.astra.server;

import io.astra.doctype.ledger.LedgerStore;
import io.astra.doctype.store.DocumentStore;
import io.astra.spi.script.ScriptRuntime;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Payroll end to end: structure assignment → attendance → payroll entry → salary slips with payment-day
 * proration, statutory insurance and the Wasm delivery bonus → submit accrues the run in one balanced voucher →
 * cancel reverses it once.
 *
 * <p>September 2026 has 26 working days (Monday–Saturday). Expected slips:
 * NV001 courier, 7,000,000 base, full month, 1,100 pieces: 7,000,000 + 730,000 + 500,000 + KPI 2,450,000 = 10,680,000 gross;
 * insurance 560,000 + 105,000 + 70,000 = 735,000; net 9,945,000.
 * NV002 courier, 6,500,000 base, one day absent and one half day (24.5 days), 520 pieces: 6,125,000 + 687,885 + 471,154
 * + KPI 780,000 = 8,064,039 gross; insurance 682,500; net 7,381,539.
 * NV003 office, 12,000,000 base, joined 14 Sep (15 working days) with one day of unpaid leave (14 days):
 * 6,461,538 + 393,077 + bonus 500,000 = 7,354,615 gross; insurance 1,260,000; net 6,094,615.
 */
@QuarkusTest
class HrmPayrollTest {

    @Inject DocumentStore store;
    @Inject LedgerStore ledger;
    @Inject ScriptRuntime scripts;

    private static RequestSpecification in(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    /** Creates and submits a document as the signed-in user; returns its name. */
    private static String submitted(String tenant, String doctype, String json) {
        String name = in(tenant).body(json).post("/api/doc/" + doctype).then().statusCode(201).extract().path("name");
        in(tenant).post("/api/doc/" + doctype + "/" + name + "/submit").then().statusCode(200);
        return name;
    }

    private static void mark(String tenant, String employee, String date, String status, int pieces) {
        submitted(tenant, "Attendance", "{\"employee\":\"" + employee + "\",\"attendance_date\":\"" + date
                + "\",\"status\":\"" + status + "\",\"delivered_pieces\":" + pieces + "}");
    }

    private BigDecimal balance(String tenant, String account) {
        return TenantContext.call(tenant, () -> ledger.balance(account, null));
    }

    @Test
    void theCourierKpiFormulaPaysByTierAndRewardsFullAttendance() throws Exception {
        byte[] kpi = HrmFixtures.module("courier-kpi");
        assertEquals(2_450_000, scripts.callLong(kpi, "kpi", 50, 1_100, 260, 26));   // 600 × 1,500 + 400 × 2,000 + 100 × 2,500 + 500,000
        assertEquals(780_000, scripts.callLong(kpi, "kpi", 50, 520, 245, 26));       // 520 × 1,500, a day and a half lost
        assertEquals(0, scripts.callLong(kpi, "kpi", 50, 0, 200, 26));
    }

    @Test
    @TestSecurity(user = "u-lan", roles = "/tenants/hrm-pay/hr")
    void payrollRunsFromAttendanceToTheLedgerAndBack() throws Exception {
        String t = "hrm-pay";
        HrmFixtures.tenant(store, t);
        mark(t, "NV001", "2026-09-01", "Present", 400);
        mark(t, "NV001", "2026-09-02", "Present", 400);
        mark(t, "NV001", "2026-09-03", "Present", 300);
        submitted(t, "Attendance", "{\"employee\":\"NV001\",\"attendance_date\":\"2026-09-04\",\"status\":\"On Leave\"}"); // paid leave
        mark(t, "NV002", "2026-09-01", "Present", 500);
        mark(t, "NV002", "2026-09-13", "Absent", 0);       // a Sunday: not a working day, costs nothing
        mark(t, "NV002", "2026-09-15", "Absent", 0);
        mark(t, "NV002", "2026-09-22", "Half Day", 20);
        submitted(t, "Attendance", "{\"employee\":\"NV003\",\"attendance_date\":\"2026-09-29\",\"status\":\"On Leave\",\"leave_type\":\"Leave Without Pay\"}");
        submitted(t, "AdditionalSalary", "{\"employee\":\"NV003\",\"salary_component\":\"BONUS\",\"amount\":500000,"
                + "\"payroll_date\":\"2026-09-30\",\"reason\":\"Thưởng hoàn thành hội nhập\"}");

        // A second mark for the same day is refused on submit.
        String dup = in(t).body("{\"employee\":\"NV002\",\"attendance_date\":\"2026-09-15\",\"status\":\"Present\"}")
                .post("/api/doc/Attendance").then().statusCode(201).extract().path("name");
        in(t).post("/api/doc/Attendance/" + dup + "/submit").then().statusCode(422)
                .body("errors[0]", containsString("NV002 is already marked for 2026-09-15"));

        // Saving the payroll entry creates the draft slips (NV004 has no assignment and is skipped).
        String run = in(t).body("{\"start_date\":\"" + HrmFixtures.START + "\",\"end_date\":\"" + HrmFixtures.END + "\"}")
                .post("/api/doc/PayrollEntry").then().statusCode(201).body("data.posting_date", equalTo(HrmFixtures.END))
                .extract().path("name");
        JsonPath slips = in(t).get("/api/doc/SalarySlip?payroll_entry=" + run).then().statusCode(200)
                .body("$", hasSize(3)).body("docstatus", equalTo(List.of(0, 0, 0))).extract().jsonPath();
        assertSlip(slips, "NV001", 26.0f, 10_680_000, 735_000, 9_945_000);
        assertSlip(slips, "NV002", 24.5f, 8_064_039, 682_500, 7_381_539);
        assertSlip(slips, "NV003", 14.0f, 7_354_615, 1_260_000, 6_094_615);
        assertEquals(2_450_000, slips.getInt("find { it.data.employee == 'NV001' }.data.earnings.find { it.component == 'KPI' }.amount"));
        assertEquals(1_100, slips.getInt("find { it.data.employee == 'NV001' }.data.delivered_pieces"));
        assertEquals("100% of 6500000 (base) × 24.5/26 days",
                slips.get("find { it.data.employee == 'NV002' }.data.earnings.find { it.component == 'BASIC' }.basis"));
        assertEquals("8% of 7000000 (base)",
                slips.get("find { it.data.employee == 'NV001' }.data.deductions.find { it.component == 'BHXH' }.basis"));

        // A client cannot write computed values.
        String slip = slips.get("find { it.data.employee == 'NV001' }.name");
        in(t).body("{\"net_pay\":1}").put("/api/doc/SalarySlip/" + slip).then().statusCode(422)
                .body("errors[0]", equalTo("field 'net_pay' is read-only"));

        // Submit: every slip is submitted and the run is accrued in one voucher, in the entry's transaction.
        in(t).post("/api/doc/PayrollEntry/" + run + "/submit").then().statusCode(200)
                .body("docstatus", equalTo(1)).body("data.employee_count", equalTo(3))
                .body("data.total_gross", equalTo(26_098_654)).body("data.total_deduction", equalTo(2_677_500))
                .body("data.total_net", equalTo(23_421_154));
        in(t).get("/api/doc/SalarySlip?payroll_entry=" + run).then().body("docstatus", equalTo(List.of(1, 1, 1)));
        assertEquals(0, balance(t, "642").compareTo(new BigDecimal("26098654")));
        assertEquals(0, balance(t, "3383").compareTo(new BigDecimal("-2040000")));
        assertEquals(0, balance(t, "3384").compareTo(new BigDecimal("-382500")));
        assertEquals(0, balance(t, "3386").compareTo(new BigDecimal("-255000")));
        assertEquals(0, balance(t, "334").compareTo(new BigDecimal("-23421154")));
        List<Map<String, Object>> voucher = TenantContext.call(t, () -> ledger.entries(run, null, 20));
        assertEquals(5, voucher.size());   // Dr 642 · Cr 3383, 3384, 3386 and 334: totals of the run
        assertTrue(voucher.stream().allMatch(e -> "PayrollEntry".equals(e.get("voucher_type"))));
        assertEquals(0, voucher.stream().map(e -> ((BigDecimal) e.get("debit")).subtract((BigDecimal) e.get("credit")))
                .reduce(BigDecimal.ZERO, BigDecimal::add).signum());
        assertEquals(0, ((BigDecimal) voucher.stream().filter(e -> "334".equals(e.get("account"))).findFirst().orElseThrow()
                .get("credit")).compareTo(new BigDecimal("23421154")));
        assertTrue(TenantContext.call(t, () -> ledger.entries(slip, null, 20)).isEmpty());   // slips do not post on their own
        in(t).get("/api/doc/SalarySlip/" + slip).then().body("data.accrual_voucher", equalTo(run));

        // Slips of a submitted run are immutable, are cancelled only with their run, and a period is paid once.
        in(t).body("{\"posting_date\":\"2026-10-05\"}").put("/api/doc/SalarySlip/" + slip).then().statusCode(422);
        in(t).post("/api/doc/SalarySlip/" + slip + "/cancel").then().statusCode(422)
                .body("errors[0]", containsString("cancel the payroll entry instead"));
        in(t).body("{\"start_date\":\"" + HrmFixtures.START + "\",\"end_date\":\"" + HrmFixtures.END + "\"}")
                .post("/api/doc/PayrollEntry").then().statusCode(422)
                .body("errors[0]", startsWith("NV001 already has submitted salary slip " + slip));

        // Cancel: the slips are cancelled and the accrual voucher is reversed once.
        in(t).post("/api/doc/PayrollEntry/" + run + "/cancel").then().statusCode(200).body("docstatus", equalTo(2));
        in(t).get("/api/doc/SalarySlip?payroll_entry=" + run).then().body("docstatus", equalTo(List.of(2, 2, 2)));
        for (String account : List.of("642", "3383", "3384", "3386", "334")) {
            assertEquals(0, balance(t, account).signum(), account);
        }
        List<Map<String, Object>> after = TenantContext.call(t, () -> ledger.entries(run, null, 20));
        assertEquals(10, after.size());
        assertEquals(5, after.stream().filter(e -> Boolean.TRUE.equals(e.get("is_reversal"))).count());
    }

    @Test
    @TestSecurity(user = "u-lan", roles = "/tenants/hrm-rules/hr")
    void aRunWithoutSlipsCannotBeSubmittedAndARunawayFormulaCannotBeSaved() throws Exception {
        String t = "hrm-rules";
        HrmFixtures.tenant(store, t);
        String drivers = in(t).body("{\"start_date\":\"2026-09-01\",\"end_date\":\"2026-09-30\",\"designation\":\"Driver\"}")
                .post("/api/doc/PayrollEntry").then().statusCode(201).extract().path("name");
        in(t).post("/api/doc/PayrollEntry/" + drivers + "/submit").then().statusCode(422)
                .body("errors[0]", containsString("has no salary slips"));

        String runaway = Base64.getEncoder().encodeToString(HrmFixtures.module("runaway-kpi"));
        in(t).body("{\"abbr\":\"LOOP\",\"component_name\":\"Vòng lặp\",\"component_type\":\"Earning\",\"calculation\":\"Wasm\","
                        + "\"account\":\"642\",\"module_wasm\":\"" + runaway + "\"}")
                .post("/api/doc/SalaryComponent").then().statusCode(422)
                .body("errors[0]", containsString("module rejected (timeout)"));
        in(t).get("/api/doc/SalaryComponent/KPI").then().statusCode(200)
                .body("data.self_test", startsWith("kpi(1000 pieces, 26/26 days) = 2200000 VND"));
    }

    @Test
    @TestSecurity(user = "u-lan", roles = "/tenants/hrm-mcp/hr")
    void anAgentGetsPayrollTotalsWithoutEmployeeNames() throws Exception {
        HrmFixtures.tenant(store, "hrm-mcp");
        String run = in("hrm-mcp").body("{\"start_date\":\"2026-09-01\",\"end_date\":\"2026-09-30\",\"designation\":\"Courier\"}")
                .post("/api/doc/PayrollEntry").then().statusCode(201).extract().path("name");
        in("hrm-mcp").header("Accept", "application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"payroll_summary\","
                        + "\"arguments\":{\"payroll_entry\":\"" + run + "\"}}}")
                .post("/mcp").then().statusCode(200)
                .body("result.isError", equalTo(false))
                .body("result.structuredContent.slips", equalTo(2))
                .body("result.structuredContent.status", equalTo("Draft"))
                .body("result.structuredContent.net_vnd", equalTo(7_995_000 + 7_547_500))   // nothing marked: full month, 0 pieces + attendance bonus
                .body("result.structuredContent.items.find { it.data.employee == 'NV001' }.data.employee_name", equalTo("N*** V*** A***"));
    }

    @Test
    @TestSecurity(user = "u-tuan", roles = "/tenants/hrm-perm/sales")
    void salesSeesNoPayroll() throws Exception {
        HrmFixtures.tenant(store, "hrm-perm");
        in("hrm-perm").get("/api/doc/SalarySlip").then().statusCode(403);
        in("hrm-perm").body("{\"start_date\":\"2026-09-01\",\"end_date\":\"2026-09-30\"}").post("/api/doc/PayrollEntry")
                .then().statusCode(403).body("error", equalTo("not permitted: create PayrollEntry"));
    }

    @Test
    @TestSecurity(user = "u-quang", roles = "/tenants/hrm-perm/ops")
    void opsRecordsAttendanceButCannotRunPayroll() throws Exception {
        HrmFixtures.tenant(store, "hrm-perm");
        mark("hrm-perm", "NV001", "2026-10-05", "Present", 42);
        in("hrm-perm").get("/api/doc/SalarySlip").then().statusCode(403);
        in("hrm-perm").body("{\"start_date\":\"2026-10-01\",\"end_date\":\"2026-10-31\"}").post("/api/doc/PayrollEntry").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "u-khoa", roles = "/tenants/hrm-perm/accountant")
    void accountantsReadPayrollButCannotSubmitIt() throws Exception {
        HrmFixtures.tenant(store, "hrm-perm");
        String run = TenantContext.call("hrm-perm", () -> store.insert("PayrollEntry",
                Map.of("start_date", "2026-08-01", "end_date", "2026-08-31"), "u-lan").name());
        in("hrm-perm").get("/api/doc/SalarySlip?payroll_entry=" + run).then().statusCode(200).body("$", hasSize(2)); // NV003 joined in September
        in("hrm-perm").post("/api/doc/PayrollEntry/" + run + "/submit").then().statusCode(403)
                .body("error", equalTo("not permitted: submit PayrollEntry"));
    }

    private static void assertSlip(JsonPath slips, String employee, float paymentDays, int gross, int deduction, int net) {
        String s = "find { it.data.employee == '" + employee + "' }.data.";
        assertEquals(paymentDays, (float) slips.getFloat(s + "payment_days"), employee);
        assertEquals(gross, slips.getInt(s + "gross_pay"), employee + " gross");
        assertEquals(deduction, slips.getInt(s + "total_deduction"), employee + " deductions");
        assertEquals(net, slips.getInt(s + "net_pay"), employee + " net");
    }
}
