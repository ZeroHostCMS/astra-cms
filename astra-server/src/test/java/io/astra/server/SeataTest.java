package io.astra.server;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.store.DocumentStore;
import io.astra.spi.kv.Counters;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Global transactions on a real Seata coordinator: submitting a CustomerOrder draws BMS credit (AT branch,
 * schema bms) and books a pickup slot (TCC branch, Redis) together with the kernel's own writes.
 */
@QuarkusTest
@TestProfile(SeataProfile.class)
class SeataTest {

    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\","
            + "\"receiver_address\":\"x\",\"zone\":\"Domestic\",\"service\":\"Express\",\"weight_kg\":2.2,\"cod_amount\":500000}";

    @Inject DocumentStore store;
    @Inject Counters counters;
    @Inject @DataSource("bms") AgroalDataSource bms;

    private static RequestSpecification in(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    /** Customer C001 with a credit limit, mirrored to BMS by the customer hook. */
    private void tenant(String tenant, long creditLimit) {
        TenantContext.run(tenant, () -> {
            for (var a : ExpressFixtures.CHART) {
                store.insert("Account", Map.of("account_number", a.get(0), "account_name", a.get(1), "root_type", a.get(2)), "fixture");
            }
            store.insert("Customer", Map.of("code", "C001", "customer_name", "Shop", "segment", "B2B", "credit_limit", creditLimit), "fixture");
        });
    }

    private String draft(String tenant) {
        return in(tenant).body(ORDER).post("/api/doc/CustomerOrder").then().statusCode(201).extract().path("name");
    }

    private long used(String tenant) throws Exception {
        try (Connection c = bms.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT used FROM customer_credit WHERE account_key = ?")) {
            ps.setString(1, tenant + ":C001");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1).longValueExact();
            }
        }
    }

    private long slots(String tenant, String which) {
        return TenantContext.call(tenant, () -> counters.get("pickup:" + LocalDate.now() + ":" + which));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/st-commit/admin")
    void aSubmitCommitsCreditSlotAndDocumentsTogether() throws Exception {
        tenant("st-commit", 1_000_000);
        String order = draft("st-commit");
        in("st-commit").post("/api/doc/CustomerOrder/" + order + "/submit").then().statusCode(200).body("docstatus", equalTo(1));
        assertEquals(73_000, used("st-commit"));
        assertEquals(1, slots("st-commit", "booked"));
        assertEquals(0, slots("st-commit", "reserved"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/st-credit/admin")
    void bmsRefusalLeavesNothingBehind() throws Exception {
        tenant("st-credit", 100_000);
        in("st-credit").post("/api/doc/CustomerOrder/" + draft("st-credit") + "/submit").then().statusCode(200);
        String second = draft("st-credit");
        in("st-credit").post("/api/doc/CustomerOrder/" + second + "/submit").then().statusCode(422)
                .body("errors[0]", containsString("credit limit of C001 exceeded: 73000.00 used of 100000.00"));
        in("st-credit").get("/api/doc/CustomerOrder/" + second).then().body("docstatus", equalTo(0));
        assertEquals(73_000, used("st-credit"));
        assertEquals(1, slots("st-credit", "booked"));
        assertEquals(0, slots("st-credit", "reserved"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/st-undo/admin")
    void aLaterRefusalUndoesTheCommittedAtBranch() throws Exception {
        tenant("st-undo", 1_000_000);
        TenantContext.run("st-undo", () -> counters.add("pickup:" + LocalDate.now() + ":booked", 200, Duration.ofHours(1)));
        String order = draft("st-undo");
        in("st-undo").post("/api/doc/CustomerOrder/" + order + "/submit").then().statusCode(422)
                .body("errors[0]", containsString("no pickup slot left"));
        assertEquals(0, used("st-undo"));                        // restored from Seata's undo log
        assertEquals(0, slots("st-undo", "reserved"));         // TCC cancel gave the slot back
        in("st-undo").get("/api/ledger/entries?voucher_no=" + order).then().body("size()", equalTo(0));
    }
}
