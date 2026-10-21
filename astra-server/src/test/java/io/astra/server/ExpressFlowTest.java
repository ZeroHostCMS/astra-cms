package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The courier flow of the Express app: order lifecycle, waybill hand-off, scans, care triage.
 * Each test runs as the admin of its own tenant, so naming series start fresh.
 */
@QuarkusTest
class ExpressFlowTest {

    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"Nguyễn Văn A\",\"receiver_phone\":\"0901234567\","
            + "\"receiver_address\":\"12 Lê Lợi, Q1\",\"zone\":\"Domestic\",\"service\":\"Express\",\"weight_kg\":2.2,\"cod_amount\":500000}";

    private static RequestSpecification as(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    @Inject DocumentStore store;

    private String newTenant(String t) {
        ExpressFixtures.tenant(store, t);
        return t;
    }

    private String draftOrder(String tenant) {
        return as(tenant).body(ORDER).post("/api/doc/CustomerOrder").then().statusCode(201).extract().path("name");
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/ex-price/admin")
    void draftOrderIsPricedNamedByDateAndValidated() {
        String t = newTenant("ex-price");
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        as(t).body(ORDER).post("/api/doc/CustomerOrder").then().statusCode(201)
                .body("name", equalTo("ORD-" + today + "-00001"))
                .body("docstatus", equalTo(0))
                // Domestic 30,000 + 3 x 5,000 for 1.2 kg extra = 45,000; Express x1.5 = 67,500; COD 1% = 5,000 → 73,000
                .body("data.shipping_fee", equalTo(73000));

        as(t).body("{\"code\":\"C001\",\"customer_name\":\"dup\"}").post("/api/doc/Customer")
                .then().statusCode(422).body("errors", hasItem("Customer 'C001' already exists"));
        as(t).body(ORDER.replace("}", ",\"shipping_fee\":1}")).post("/api/doc/CustomerOrder")
                .then().statusCode(422).body("errors", hasItem("field 'shipping_fee' is read-only"));
        as(t).body(ORDER.replace("0901234567", "12345")).post("/api/doc/CustomerOrder")
                .then().statusCode(422).body("errors", hasItem("receiver_phone must be 10 digits starting with 0"));
        as(t).body(ORDER.replace("C001", "C404")).post("/api/doc/CustomerOrder")
                .then().statusCode(422).body("errors", hasItem("field 'customer': Customer 'C404' not found"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = {"/tenants/ex-link-a/admin", "/tenants/ex-link-b/admin"})
    void linksNeverResolveAcrossTenants() {
        newTenant("ex-link-a");
        String other = "ex-link-b";
        as(other).body(ORDER).post("/api/doc/CustomerOrder")
                .then().statusCode(422).body("errors", hasItem("field 'customer': Customer 'C001' not found"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/ex-submit/admin")
    void submitCreatesWaybillAndCancelIsVetoedOnceInNetwork() {
        String t = newTenant("ex-submit");
        String order = draftOrder(t);
        String waybill = as(t).post("/api/doc/CustomerOrder/" + order + "/submit").then().statusCode(200)
                .body("docstatus", equalTo(1))
                .body("data.waybill", matchesPattern("WB-\\d{4}-\\d{6}"))
                .extract().path("data.waybill");

        as(t).body("{\"note\":\"x\"}").put("/api/doc/CustomerOrder/" + order).then().statusCode(422)
                .body("errors", hasItem("submitted documents are immutable; cancel and amend instead"));

        as(t).get("/api/doc/Waybill/" + waybill).then().statusCode(200)
                .body("data.order", equalTo(order)).body("data.ops_status", equalTo("Created"))
                .body("data.scan_log", hasSize(1));

        as(t).body("{\"ops_status\":\"Picked Up\"}").put("/api/doc/Waybill/" + waybill).then().statusCode(200);
        as(t).body("{\"ops_status\":\"At Hub\"}").put("/api/doc/Waybill/" + waybill).then().statusCode(422)
                .body("errors", hasItem("current_hub is required when ops_status is 'At Hub'"));
        as(t).body("{\"ops_status\":\"At Hub\",\"current_hub\":\"HCM-Tan Binh\"}").put("/api/doc/Waybill/" + waybill)
                .then().statusCode(200).body("data.scan_log", hasSize(3));
        as(t).body("{\"ops_status\":\"Delivered\"}").put("/api/doc/Waybill/" + waybill).then().statusCode(422)
                .body("errors", hasItem("ops_status cannot go from 'At Hub' to 'Delivered'"));

        as(t).post("/api/doc/CustomerOrder/" + order + "/cancel").then().statusCode(422)
                .body("errors", hasItem("cannot cancel " + order + ": waybill " + waybill + " is already 'At Hub'"));
        as(t).get("/api/doc/CustomerOrder/" + order).then().body("docstatus", equalTo(1));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/ex-amend/admin")
    void cancelCascadesAndAmendCreatesRevision() {
        String t = newTenant("ex-amend");
        String order = draftOrder(t);
        String waybill = as(t).post("/api/doc/CustomerOrder/" + order + "/submit").then().extract().path("data.waybill");
        as(t).post("/api/doc/CustomerOrder/" + order + "/cancel").then().statusCode(200).body("docstatus", equalTo(2));
        as(t).get("/api/doc/Waybill/" + waybill).then().body("data.ops_status", equalTo("Cancelled"));

        as(t).post("/api/doc/CustomerOrder/" + order + "/amend").then().statusCode(201)
                .body("name", equalTo(order + "-1")).body("docstatus", equalTo(0))
                .body("amendedFrom", equalTo(order)).body("data.waybill", nullValue())
                .body("data.shipping_fee", equalTo(73000));
        as(t).post("/api/doc/CustomerOrder/" + order + "/amend").then().statusCode(422)
                .body("errors", hasItem(order + " was already amended as " + order + "-1"));

        as(t).get("/api/doc/Waybill?ops_status=Cancelled").then().statusCode(200)
                .body("name", hasItem(waybill)).body("data.ops_status", everyItem(equalTo("Cancelled")));
        as(t).get("/api/doc/CustomerOrder?docstatus=0").then().body("name", hasItem(order + "-1"));
        as(t).get("/api/doc/Waybill?nope=1").then().statusCode(422);
        as(t).post("/api/doc/Article/ART-00001/submit").then().statusCode(422)
                .body("errors", hasItem("DocType Article is not submittable"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/ex-ticket/admin")
    void ticketsAreTriagedAndSeeTheWaybill() {
        String t = newTenant("ex-ticket");
        String order = draftOrder(t);
        String waybill = as(t).post("/api/doc/CustomerOrder/" + order + "/submit").then().extract().path("data.waybill");
        as(t).body("{\"subject\":\"Hàng bị vỡ khi nhận\",\"waybill\":\"" + waybill + "\"}").post("/api/doc/SupportTicket")
                .then().statusCode(201)
                .body("name", equalTo("TCK-00001"))
                .body("data.priority", equalTo("High"))
                .body("data.triage_note", equalTo("auto-escalated: mentions 'vỡ'"))
                .body("data.waybill_status", equalTo("Created"))
                .body("data.sla_due", notNullValue());
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/acme/admin")
    void projectedFieldsHaveOnlineIndexes() {
        as("acme").get("/api/doctype/Waybill/projections").then().statusCode(200)
                .body("find { it.field == 'ops_status' }.valid", equalTo(true));
        as("acme").get("/api/app").then().statusCode(200)
                .body("find { it.id == 'io.astra.express' }.doctypes", hasItem("CustomerOrder"));
    }
}
