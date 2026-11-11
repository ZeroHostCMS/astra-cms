package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.pluginhost.rollout.RolloutJob;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-evolving loop of slot {@code ops.anomaly}: an AI token proposes a detector (it cannot approve it), an admin
 * approves, events raise alerts for the role that acts on them, people dismiss noise, and the release's status tells
 * the next version what to change.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnomalyLoopTest {

    private static final String T = "anom-a";
    private static String detector;

    @Inject DocumentStore store;
    @Inject RolloutJob rollout;

    private static RequestSpecification in() {
        return given().header("X-Astra-Tenant", T).contentType("application/json");
    }

    private static ValidatableResponse mcp(String tool, String args) {
        return in().body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + args + "}}")
                .post("/mcp").then().statusCode(200);
    }

    /** A submitted order of {@code kg} with {@code cod}; returns its waybill. */
    private static String parcel(double kg, long cod) {
        String o = in().body("{\"customer\":\"C001\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\",\"receiver_address\":\"x\","
                        + "\"zone\":\"Domestic\",\"service\":\"Standard\",\"weight_kg\":" + kg + ",\"cod_amount\":" + cod + "}")
                .post("/api/doc/CustomerOrder").then().statusCode(201).extract().path("name");
        return in().post("/api/doc/CustomerOrder/" + o + "/submit").then().statusCode(200).extract().path("data.waybill");
    }

    private static void scan(String waybill, String json) {
        in().body(json).put("/api/doc/Waybill/" + waybill).then().statusCode(200);
    }

    private static List<Map<String, Object>> alerts(String kind) {
        return in().queryParam("kind", kind).get("/api/doc/OpsAlert").then().statusCode(200).extract().jsonPath().getList("$");
    }

    @Test
    @Order(1)
    @TestSecurity(user = "u-bot", roles = "/tenants/anom-a/release-bot")
    void theAiProposesADetectorItBacktestedButCannotApproveIt() throws Exception {
        ExpressFixtures.tenant(store, T);
        String module = LayeredPricingTest.wasm("anomaly-ops-1.0.0");
        mcp("anomaly_backtest", "{\"module_base64\":\"" + module + "\"}").body("result.isError", equalTo(false));
        detector = mcp("release_propose", "{\"slot\":\"ops.anomaly\",\"tier\":\"wasm\",\"app_id\":\"anomaly-ops\",\"version\":\"1.0.0\","
                + "\"artifact_base64\":\"" + module + "\",\"notes\":\"weight, COD and courier rules\"}")
                .body("result.structuredContent.generator", equalTo("claude-code"))
                .body("result.structuredContent.self_test", startsWith("check on 3 normal + 3 odd sample events → 0 301 0 201 0 201"))
                .extract().path("result.structuredContent.release");
        in().post("/api/doc/AppRelease/" + detector + "/submit").then().statusCode(403);
    }

    @Test
    @Order(2)
    @TestSecurity(user = "u-admin", roles = "/tenants/anom-a/admin")
    void approvedEventsRaiseAlertsForTheRoleThatActsOnThem() {
        in().post("/api/doc/AppRelease/" + detector + "/submit").then().statusCode(200).body("data.stage", equalTo("Live"));
        rollout.run();

        // weight: declared 1 kg, the hub weighs 2 kg → OPS; 1.02 kg is normal
        String heavy = parcel(1, 0);
        scan(heavy, "{\"ops_status\":\"Picked Up\"}");
        scan(heavy, "{\"ops_status\":\"At Hub\",\"current_hub\":\"HCM-Tân Bình\",\"measured_weight_kg\":2.0}");
        String normal = parcel(1, 0);
        scan(normal, "{\"ops_status\":\"Picked Up\"}");
        scan(normal, "{\"ops_status\":\"At Hub\",\"current_hub\":\"HCM-Tân Bình\",\"measured_weight_kg\":1.02}");
        List<Map<String, Object>> weight = alerts("Weight mismatch");
        assertEquals(1, weight.size());
        Map<?, ?> w = (Map<?, ?>) weight.getFirst().get("data");
        assertEquals(heavy, w.get("ref_name"));
        assertEquals("ops", w.get("assigned_role"));
        assertEquals(3, w.get("severity"));
        assertEquals("anomaly-ops@1.0.0 on pod-a", w.get("raised_by"));

        // a parcel failing a third time → customer care
        String failing = parcel(1, 0);
        scan(failing, "{\"ops_status\":\"Picked Up\"}");
        scan(failing, "{\"ops_status\":\"At Hub\",\"current_hub\":\"HCM-Tân Bình\"}");
        for (int i = 0; i < 3; i++) {
            scan(failing, "{\"ops_status\":\"Out for Delivery\",\"courier\":\"bt05\"}");
            scan(failing, "{\"ops_status\":\"Failed Delivery\"}");
        }
        List<Map<String, Object>> delivery = alerts("Failed delivery");
        assertEquals(1, delivery.size());
        assertEquals("care", ((Map<?, ?>) delivery.getFirst().get("data")).get("assigned_role"));

        // a COD payout of half of what is owed → accounting
        String cod = parcel(1, 1_000_000);
        for (String s : List.of("{\"ops_status\":\"Picked Up\"}", "{\"ops_status\":\"At Hub\",\"current_hub\":\"HCM-Tân Bình\"}",
                "{\"ops_status\":\"Out for Delivery\",\"courier\":\"bt01\"}", "{\"ops_status\":\"Delivered\"}")) scan(cod, s);
        in().body("{\"customer\":\"C001\",\"amount\":500000,\"collected_until\":\"" + java.time.LocalDate.now().minusDays(1) + "\"}")
                .post("/api/doc/CODRemittance").then().statusCode(201);
        List<Map<String, Object>> payout = alerts("COD payout");
        assertEquals(1, payout.size());
        assertEquals("accountant", ((Map<?, ?>) payout.getFirst().get("data")).get("assigned_role"));
    }

    @Test
    @Order(3)
    @TestSecurity(user = "u-ops", roles = "/tenants/anom-a/ops")
    void eachRoleSeesItsOwnAlertsAndDismissesNoise() {
        mcp("my_alerts", "{}").body("result.structuredContent.items", hasSize(1))
                .body("result.structuredContent.items.kind", everyItem(equalTo("Weight mismatch")));
        String alert = (String) alerts("Weight mismatch").getFirst().get("name");
        in().body("{\"status\":\"Dismissed\",\"note\":\"kiện xốp, cân điện tử hub lệch\"}").put("/api/doc/OpsAlert/" + alert).then().statusCode(200);
    }

    @Test
    @Order(4)
    @TestSecurity(user = "u-acc", roles = "/tenants/anom-a/accountant")
    void accountingSeesOnlyPayouts() {
        mcp("my_alerts", "{}").body("result.structuredContent.items", hasSize(1))
                .body("result.structuredContent.items[0].kind", equalTo("COD payout"));
    }

    @Test
    @Order(5)
    @TestSecurity(user = "u-bot", roles = "/tenants/anom-a/release-bot")
    void theReleaseStatusFeedsTheNextVersion() {
        mcp("release_status", "{\"release\":\"" + detector + "\"}")
                .body("result.structuredContent.items[0].stage", equalTo("Live"))
                .body("result.structuredContent.items[0].results.alerts_by_kind.'Weight mismatch'.dismissed", equalTo(1))
                .body("result.structuredContent.items[0].results.dismissal_notes[0]", containsString("kiện xốp"));
        mcp("ops_snapshot", "{}").body("result.structuredContent.hub_weight_checks.'HCM-Tân Bình'.scans", equalTo(2))
                .body("result.structuredContent.cod_payouts.paid_under_90_pct", equalTo(1));
    }

    @Test
    @Order(6)
    @TestSecurity(user = "u-admin", roles = "/tenants/anom-a/admin")
    void aDetectorThatHangsNeverBlocksTheScan() throws Exception {
        String hang = in().body("{\"slot\":\"ops.anomaly\",\"tier\":\"wasm\",\"app_id\":\"abi-probe\",\"version\":\"0.0.1\",\"artifact\":\""
                + LayeredPricingTest.wasm("abi-probe") + "\"}").post("/api/doc/AppRelease").then().statusCode(201).extract().path("name");
        in().post("/api/doc/AppRelease/" + hang + "/submit").then().statusCode(200).body("data.stage", equalTo("Canary"));
        String w = parcel(1, 0);
        scan(w, "{\"ops_status\":\"Picked Up\"}");
        long started = System.nanoTime();
        scan(w, "{\"ops_status\":\"At Hub\",\"current_hub\":\"HN-Long Biên\",\"measured_weight_kg\":7.777}");
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "the scan waited for the detector");
        in().get("/api/doc/Waybill/" + w).then().body("data.ops_status", equalTo("At Hub"));
        rollout.run();
        in().get("/api/doc/AppRelease/" + hang).then().body("data.errors", equalTo(1));
    }
}
