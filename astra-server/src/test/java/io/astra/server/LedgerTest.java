package io.astra.server;

import io.agroal.api.AgroalDataSource;
import io.astra.doctype.store.DocumentStore;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.ledger.GeneralLedger;
import io.astra.spi.ledger.GeneralLedger.Entry;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The append-only general ledger: postings from the courier flow, reversals, COD payouts, maker/checker. */
@QuarkusTest
class LedgerTest {

    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\","
            + "\"receiver_address\":\"x\",\"zone\":\"Domestic\",\"service\":\"Express\",\"weight_kg\":2.2,\"cod_amount\":500000}";

    @Inject DocumentStore store;
    @Inject GeneralLedger ledger;
    @Inject AgroalDataSource dataSource;

    private static RequestSpecification in(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/gl-order/admin")
    void submitPostsTheFeeAndCancelPostsTheReversal() {
        ExpressFixtures.tenant(store, "gl-order");
        String order = in("gl-order").body(ORDER).post("/api/doc/CustomerOrder").then().statusCode(201).extract().path("name");
        in("gl-order").post("/api/doc/CustomerOrder/" + order + "/submit").then().statusCode(200);

        in("gl-order").get("/api/ledger/entries?voucher_no=" + order).then().statusCode(200).body("$", hasSize(2))
                .body("find { it.account == '131' }.debit", equalTo(73000.0f))
                .body("find { it.account == '511' }.credit", equalTo(73000.0f))
                .body("[0].partition", containsString("astra_ledger_"));

        in("gl-order").post("/api/doc/CustomerOrder/" + order + "/cancel").then().statusCode(200);
        in("gl-order").get("/api/ledger/entries?voucher_no=" + order).then().body("$", hasSize(4))
                .body("is_reversal", hasItem(true));
        in("gl-order").get("/api/ledger/trial-balance").then().statusCode(200)
                .body("find { it.account == '131' }.balance", equalTo(0.0f));
    }

    @Test
    @TestSecurity(user = "u-khoa", roles = "/tenants/gl-cod/accountant")
    void deliveryRecordsCodAndPayoutsAreCappedAndCheckedByAnotherAccountant() {
        deliveredOrder("gl-cod");
        in("gl-cod").get("/api/ledger/trial-balance").then()
                .body("find { it.account == '3388' }.balance", equalTo(-500000.0f))
                .body("find { it.account == '1388' }.balance", equalTo(500000.0f));

        in("gl-cod").body("{\"customer\":\"C001\",\"amount\":900000}").post("/api/doc/CODRemittance").then().statusCode(422)
                .body("errors[0]", containsString("exceeds the COD owed to C001 (500000"));
        String payout = in("gl-cod").body("{\"customer\":\"C001\",\"amount\":500000,\"bank_reference\":\"VCB-001\"}")
                .post("/api/doc/CODRemittance").then().statusCode(201).body("data.outstanding", equalTo(500000))
                .extract().path("name");
        in("gl-cod").post("/api/doc/CODRemittance/" + payout + "/submit").then().statusCode(403)
                .body("error", containsString("segregation of duties"));
    }

    @Test
    @TestSecurity(user = "u-thu", roles = "/tenants/gl-check/accountant")
    void aSecondAccountantApprovesThePayout() {
        deliveredOrder("gl-check");
        String payout = TenantContext.call("gl-check", () -> store.insert("CODRemittance",
                Map.of("customer", "C001", "amount", 500000), "u-khoa").name());
        in("gl-check").post("/api/doc/CODRemittance/" + payout + "/submit").then().statusCode(200).body("docstatus", equalTo(1));
        in("gl-check").get("/api/ledger/trial-balance").then()
                .body("find { it.account == '3388' }.balance", equalTo(0.0f))
                .body("find { it.account == '112' }.balance", equalTo(-500000.0f));
    }

    @Test
    void unbalancedVouchersAndUnknownAccountsAreRefused() {
        ExpressFixtures.tenant(store, "gl-rules");
        TenantContext.run("gl-rules", () -> {
            assertThrows(DocValidationException.class, () -> ledger.post("JournalEntry", "JE-1", LocalDate.now(), List.of(
                    Entry.debit("131", null, new BigDecimal("10")), Entry.credit("511", null, new BigDecimal("9"))), null));
            assertThrows(DocValidationException.class, () -> ledger.post("JournalEntry", "JE-2", LocalDate.now(), List.of(
                    Entry.debit("999", null, BigDecimal.TEN), Entry.credit("511", null, BigDecimal.TEN)), null));
        });
    }

    @Test
    void theLedgerIsAppendOnlyEvenForItsOwner() throws SQLException {
        ExpressFixtures.tenant(store, "gl-sql");
        TenantContext.run("gl-sql", () -> ledger.post("JournalEntry", "JE-1", LocalDate.now(), List.of(
                Entry.debit("131", null, BigDecimal.ONE), Entry.credit("511", null, BigDecimal.ONE)), null));
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("SELECT set_config('astra.tenant', 'gl-sql', false)");
            SQLException update = assertThrows(SQLException.class, () -> st.executeUpdate("UPDATE astra_ledger SET debit = 1000"));
            assertTrue(update.getMessage().contains("append-only"), update.getMessage());
            assertThrows(SQLException.class, () -> st.executeUpdate("DELETE FROM astra_ledger"));
            st.execute("SELECT set_config('astra.tenant', '', false)");
        }
    }

    /** A submitted order whose waybill went all the way to Delivered, as admin through the kernel. */
    private void deliveredOrder(String tenant) {
        ExpressFixtures.tenant(store, tenant);
        TenantContext.run(tenant, () -> {
            String order = store.insert("CustomerOrder", Map.of("customer", "C001", "receiver_name", "A",
                    "receiver_phone", "0901234567", "receiver_address", "x", "weight_kg", 1, "cod_amount", 500000), "u-admin").name();
            String waybill = store.submit("CustomerOrder", order).orElseThrow().getString("waybill");
            for (Map<String, Object> scan : List.<Map<String, Object>>of(Map.of("ops_status", "Picked Up"),
                    Map.of("ops_status", "At Hub", "current_hub", "HCM"), Map.of("ops_status", "Out for Delivery"),
                    Map.of("ops_status", "Delivered"))) {
                store.update("Waybill", waybill, scan);
            }
        });
    }
}
