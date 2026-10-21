package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Tenant roles of the Express app: sales sells, ops scans, care answers, accountants cancel. */
@QuarkusTest
class ExpressPermissionTest {

    private static final String T = "ex-perm";
    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\","
            + "\"receiver_address\":\"x\",\"weight_kg\":1}";

    @Inject DocumentStore store;

    private static RequestSpecification in() {
        return given().header("X-Astra-Tenant", T).contentType("application/json");
    }

    /** A submitted order and its waybill, created directly through the kernel (no permission checks). */
    private String[] submittedOrder() {
        ExpressFixtures.tenant(store, T);
        return TenantContext.call(T, () -> {
            String order = store.insert("CustomerOrder", Map.of("customer", "C001", "receiver_name", "A",
                    "receiver_phone", "0901234567", "receiver_address", "x", "weight_kg", 1), "u-admin").name();
            String waybill = store.submit("CustomerOrder", order).orElseThrow().getString("waybill");
            return new String[] {order, waybill};
        });
    }

    @Test
    @TestSecurity(user = "u-ops", roles = "/tenants/ex-perm/ops")
    void opsScansButCannotSell() {
        String[] o = submittedOrder();
        in().body(ORDER).post("/api/doc/CustomerOrder").then().statusCode(403);
        in().body("{\"ops_status\":\"Picked Up\"}").put("/api/doc/Waybill/" + o[1]).then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "u-sales", roles = "/tenants/ex-perm/sales")
    void salesSellsButCannotScanOrCancel() {
        String[] o = submittedOrder();
        in().body("{\"ops_status\":\"Cancelled\"}").put("/api/doc/Waybill/" + o[1]).then().statusCode(403);
        in().post("/api/doc/CustomerOrder/" + o[0] + "/cancel").then().statusCode(403)
                .body("error", equalTo("not permitted: cancel CustomerOrder"));
        String own = in().body(ORDER).post("/api/doc/CustomerOrder").then().statusCode(201).extract().path("name");
        in().post("/api/doc/CustomerOrder/" + own + "/submit").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "u-accountant", roles = "/tenants/ex-perm/accountant")
    void accountantsCancelButCannotSubmit() {
        String[] o = submittedOrder();
        in().post("/api/doc/CustomerOrder/" + o[0] + "/cancel").then().statusCode(200).body("docstatus", equalTo(2));
        in().post("/api/doc/CustomerOrder/" + o[0] + "/amend").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "u-care", roles = "/tenants/ex-perm/care")
    void careOpensTicketsButCannotTouchOrders() {
        String[] o = submittedOrder();
        in().body("{\"subject\":\"Hỏi giờ giao\",\"waybill\":\"" + o[1] + "\"}").post("/api/doc/SupportTicket")
                .then().statusCode(201).body("data.waybill_status", equalTo("Created"));
        in().post("/api/doc/CustomerOrder/" + o[0] + "/cancel").then().statusCode(403);
    }
}
