package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dispatch copilot over REST (the Desk's path) and the read-your-writes position after replanning, REST and MCP. */
@QuarkusTest
class StmsCopilotRestTest {

    static final String LSN = "[0-9A-F]+/[0-9A-F]+";
    static final String BREAKDOWN = "Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác, ưu tiên khách VIP trước 10h";

    @Inject DocumentStore store;

    private static ValidatableResponse copilot(String tenant, String plan, String instruction) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json")
                .body(Map.of("plan", plan, "instruction", instruction)).post("/api/stms/copilot").then();
    }

    private static ValidatableResponse mcp(String tenant, String tool, String arguments) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                        + "\",\"arguments\":" + arguments + "}}")
                .post("/mcp").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-r/dispatcher")
    void aDispatcherReplansThroughTheCopilotAndGetsTheWritePosition() {
        StmsFixtures.tenant(store, "stms-r", 300, 250, 250);
        String plan = StmsFixtures.solvedPlan(store, "stms-r");

        JsonPath r = copilot("stms-r", plan, BREAKDOWN).statusCode(200)
                .header("X-Tx-LSN", matchesPattern(LSN))
                .body("llm", equalTo("mock"))
                .body("llm_note", containsString("no language model"))
                .body("tool_calls.name", equalTo(List.of("stms_vehicle_unavailable", "stms_set_weights", "stms_explain_plan")))
                .body("tool_calls.type", everyItem(equalTo("tool_use")))
                .body("results.ok", everyItem(equalTo(true)))
                .body("feasible", equalTo(true))
                .body("score_after", startsWith("0hard/"))
                .body("explanation", startsWith("[LLM giả lập"))
                .extract().jsonPath();
        assertFalse(r.getString("score_before").isBlank());
        List<Map<String, Object>> moved = r.getList("moved_stops");
        assertFalse(moved.isEmpty(), "the broken vehicle's stops moved");
        assertTrue(moved.stream().allMatch(m -> !"51C-12345".equals(m.get("to"))), moved::toString);
        assertTrue(r.getList("routes_before.vehicle").contains("51C-12345"), "the baseline used the vehicle");
        assertFalse(r.getList("routes_after.vehicle").contains("51C-12345"), "the replan does not");
        assertEquals(20, r.getList("routes_after").size());
        List<Map<String, Object>> after = r.getList("routes_after");
        for (Map<String, Object> row : after) assertEquals(0, ((Number) row.get("late_min")).intValue(), row::toString);

        given().header("X-Astra-Tenant", "stms-r").get("/api/stms/plans/" + plan + "/events").then().statusCode(200)
                .header("X-Tx-LSN", nullValue())
                .body("data.event_type", hasItems("Copilot", "Vehicle unavailable", "Weights"))
                .body("data.source", everyItem(equalTo("Copilot")));

        // an instruction outside the script changes nothing, and the request is still well-formed
        copilot("stms-r", plan, "Hôm nay trời đẹp").statusCode(200)
                .body("tool_calls.size()", equalTo(0))
                .body("explanation", containsString("Không có kịch bản"));
        given().header("X-Astra-Tenant", "stms-r").contentType("application/json").body(Map.of("plan", plan))
                .post("/api/stms/copilot").then().statusCode(422);
    }

    @Test
    @TestSecurity(user = "u-ops", roles = "/tenants/stms-s/ops")
    void opsMayReadThePlanButNotAskTheCopilot() {
        StmsFixtures.tenant(store, "stms-s", 300, 250, 250);
        String plan = StmsFixtures.solvedPlan(store, "stms-s");
        String score = TenantContext.call("stms-s", () -> store.get("DispatchPlan", plan).orElseThrow().getString("score"));

        copilot("stms-s", plan, BREAKDOWN).statusCode(403)
                .header("X-Tx-LSN", nullValue())
                .body("error", containsString("not permitted: read DispatchEvent"));
        given().header("X-Astra-Tenant", "stms-s").get("/api/stms/plans/" + plan + "/events").then().statusCode(403);
        given().header("X-Astra-Tenant", "stms-s").get("/api/doc/DispatchPlan/" + plan).then().statusCode(200)
                .body("data.score", equalTo(score))
                .body("data.unavailable_vehicles", nullValue());
    }

    @Test
    @TestSecurity(user = "u-both", roles = {"/tenants/stms-t/dispatcher", "/tenants/stms-u/dispatcher"})
    void anotherTenantsPlanIsNotFound() {
        StmsFixtures.tenant(store, "stms-t", 300, 250, 250);
        String plan = StmsFixtures.solvedPlan(store, "stms-t");

        copilot("stms-u", plan, BREAKDOWN).statusCode(404)
                .header("X-Tx-LSN", nullValue())
                .body("error", containsString("not found"));
        given().header("X-Astra-Tenant", "stms-u").get("/api/stms/plans/" + plan + "/events").then().statusCode(404);
        String routes = TenantContext.call("stms-t", () -> String.valueOf(store.get("DispatchPlan", plan).orElseThrow().get("routes")));
        assertTrue(routes.contains("51C-12345"), "the plan in the owning tenant is untouched");
    }

    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-v/dispatcher")
    void mcpReplanningToolsReportTheWritePositionAndReadsDoNot() {
        StmsFixtures.tenant(store, "stms-v", 300, 250, 250);
        String plan = StmsFixtures.solvedPlan(store, "stms-v");
        String p = "\"plan\":\"" + plan + "\"";

        mcp("stms-v", "stms_vehicle_unavailable", "{" + p + ",\"vehicle\":\"51C-12345\"}")
                .header("X-Tx-LSN", matchesPattern(LSN))
                .body("result.isError", equalTo(false));
        mcp("stms-v", "stms_explain_plan", "{" + p + "}")
                .header("X-Tx-LSN", nullValue())
                .body("result.isError", equalTo(false));
        mcp("stms-v", "stms_vehicle_unavailable", "{" + p + ",\"vehicle\":\"99Z-00000\"}")
                .header("X-Tx-LSN", nullValue())                      // refused: nothing was written
                .body("result.isError", equalTo(true))
                .body("result.content[0].text", not(containsString("score_after")));
    }
}
