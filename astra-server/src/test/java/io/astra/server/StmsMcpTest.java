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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STMS for AI agents: replanning tools and the (mock-LLM) copilot run for real, in the caller's tenant and role. */
@QuarkusTest
class StmsMcpTest {

    @Inject DocumentStore store;

    private static ValidatableResponse call(String tenant, String tool, String arguments) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                        + "\",\"arguments\":" + arguments + "}}")
                .post("/mcp").then().statusCode(200);
    }

    private static JsonPath ok(String tenant, String tool, String arguments) {
        return call(tenant, tool, arguments).body("result.isError", equalTo(false))
                .body("result.structuredContent.feasible", equalTo(true))
                .extract().jsonPath().setRootPath("result.structuredContent");
    }

    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-c/dispatcher")
    void everyLiveEventIsReplannedFeasiblyAndLogged() {
        StmsFixtures.tenant(store, "stms-c", 300, 250, 250);
        String plan = StmsFixtures.solvedPlan(store, "stms-c");
        String p = "\"plan\":\"" + plan + "\"";

        given().header("X-Astra-Tenant", "stms-c").contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").post("/mcp").then().statusCode(200)
                .body("result.tools.name", hasItems("stms_optimize_route", "stms_vehicle_unavailable", "stms_add_urgent_stop",
                        "stms_traffic_delay", "stms_change_time_window", "stms_set_weights", "stms_explain_plan", "stms_copilot"));

        // a proposal for the same hub and day, not saved
        ok("stms-c", "stms_optimize_route", "{\"hub\":\"HCM-TB\",\"date\":\"2026-10-23\",\"seconds\":1}")
                .getBoolean("proposal");

        JsonPath broken = ok("stms-c", "stms_vehicle_unavailable", "{" + p + ",\"vehicle\":\"51C-12345\",\"reason\":\"hỏng máy\"}");
        assertTrue(broken.getString("score_after").startsWith("0hard/"));
        assertTrue(broken.getList("moved").size() > 0, "the broken vehicle's stops moved");
        List<String> to = broken.getList("moved.to");
        assertTrue(to.stream().noneMatch("51C-12345"::equals), "nothing is routed on the broken vehicle: " + to);
        assertTrue(broken.getString("event").startsWith("DE-"));

        JsonPath urgent = ok("stms-c", "stms_add_urgent_stop", "{" + p + ",\"label\":\"Đơn gấp Bến Thành\",\"latitude\":10.7725,"
                + "\"longitude\":106.698,\"demand_kg\":12,\"window_from\":\"08:00\",\"window_to\":\"11:00\"}");
        String stop = urgent.getString("stop");
        assertTrue(urgent.getList("moved.stop").contains(stop), "the urgent stop was placed");

        ok("stms-c", "stms_traffic_delay", "{" + p + ",\"label\":\"Thủ Đức\",\"latitude\":10.8494,\"longitude\":106.7537,\"radius_km\":4,\"factor\":2.5}");
        ok("stms-c", "stms_change_time_window", "{" + p + ",\"stop\":\"" + stop + "\",\"window_from\":\"08:00\",\"window_to\":\"10:00\"}");
        JsonPath weights = ok("stms-c", "stms_set_weights", "{" + p + ",\"priority\":500,\"balance\":10}");
        assertEquals(500, weights.getInt("detail.weights.priority"));

        JsonPath plan2 = given().header("X-Astra-Tenant", "stms-c").get("/api/doc/DispatchPlan/" + plan).then().statusCode(200)
                .extract().jsonPath();
        assertEquals(21, plan2.getList("data.routes").size());
        assertEquals(List.of("51C-12345"), plan2.getList("data.unavailable_vehicles"));
        List<Map<String, Object>> rows = plan2.getList("data.routes");
        for (Map<String, Object> r : rows) assertEquals(0, ((Number) r.get("late_min")).intValue(), r::toString);

        call("stms-c", "stms_explain_plan", "{" + p + "}").body("result.isError", equalTo(false))
                .body("result.structuredContent.events.event_type",
                        hasItems("Vehicle unavailable", "Urgent stop", "Traffic delay", "Time window", "Weights"));
    }

    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-e/dispatcher")
    void theCopilotTurnsAnInstructionIntoRealReplanningAndSaysItsLlmIsAMock() {
        StmsFixtures.tenant(store, "stms-e", 300, 250, 250);
        String plan = StmsFixtures.solvedPlan(store, "stms-e");
        JsonPath r = call("stms-e", "stms_copilot", "{\"plan\":\"" + plan + "\",\"instruction\":"
                + "\"Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác, ưu tiên khách VIP trước 10h\"}")
                .body("result.isError", equalTo(false))
                .body("result.structuredContent.llm", equalTo("mock"))
                .body("result.structuredContent.llm_note", containsString("no language model"))
                .body("result.structuredContent.tool_calls.name",
                        equalTo(List.of("stms_vehicle_unavailable", "stms_set_weights", "stms_explain_plan")))
                .body("result.structuredContent.tool_calls.type", everyItem(equalTo("tool_use")))
                .body("result.structuredContent.results.ok", everyItem(equalTo(true)))
                .body("result.structuredContent.score_after", startsWith("0hard/"))
                .body("result.structuredContent.explanation", startsWith("[LLM giả lập"))
                .body("result.structuredContent.explanation", containsString("Xe 51C-12345 ngừng chạy"))
                .extract().jsonPath().setRootPath("result.structuredContent");
        assertTrue(r.getString("explanation").contains("điểm VIP/gấp được phục vụ trước 10:00"), r.getString("explanation"));

        call("stms-e", "stms_copilot", "{\"plan\":\"" + plan + "\",\"instruction\":\"Hôm nay trời đẹp\"}")
                .body("result.structuredContent.tool_calls.size()", equalTo(0))
                .body("result.structuredContent.explanation", containsString("Không có kịch bản"));
    }

    /** The order of scripts/demo-stms.sh, with its five-second budget: every step keeps the hard constraints. */
    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-f/dispatcher")
    void theDemoStorylineStaysFeasibleAtEveryStep() {
        StmsFixtures.tenant(store, "stms-f", 300, 250, 250);
        String plan = TenantContext.call("stms-f", () -> store.insert("DispatchPlan", Map.of("plan_date", StmsFixtures.DATE,
                "hub", StmsFixtures.HUB, "solve", true, "solve_seconds", 5), "fixture").name());
        String p = "\"plan\":\"" + plan + "\"";
        for (String instruction : List.of("Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác",
                "Ưu tiên khách VIP trước 10h, cân bằng tải giữa các xe",
                "Thêm đơn gấp 30kg ở Bến Thành trước 11h")) {
            JsonPath r = call("stms-f", "stms_copilot", "{" + p + ",\"instruction\":\"" + instruction + "\"}")
                    .body("result.isError", equalTo(false))
                    .body("result.structuredContent.results.ok", everyItem(equalTo(true)))
                    .body("result.structuredContent.feasible", equalTo(true))
                    .extract().jsonPath().setRootPath("result.structuredContent");
            System.out.printf("STMS demo | %s | %s → %s | solve_ms %s%n  %s%n", instruction, r.getString("score_before"),
                    r.getString("score_after"), r.getList("results.result.solve_ms"), r.getString("explanation"));
        }
        ok("stms-f", "stms_traffic_delay", "{" + p + ",\"label\":\"Thủ Đức\",\"latitude\":10.8494,\"longitude\":106.7537,\"radius_km\":4,\"factor\":2.5}");
        String stop = TenantContext.call("stms-f", () -> store.find("DeliveryStop", Map.of("label", "Shop 15"), 1).getFirst().name());
        JsonPath last = ok("stms-f", "stms_change_time_window", "{" + p + ",\"stop\":\"" + stop + "\",\"window_from\":\"08:00\",\"window_to\":\"09:00\"}");
        System.out.printf("STMS demo | traffic + window | %s → %s | solve_ms %s%n", last.getString("score_before"),
                last.getString("score_after"), last.getString("solve_ms"));
    }

    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-d/dispatcher")
    void anotherTenantsPlansAndHubsDoNotExistForTheCaller() {
        StmsFixtures.tenant(store, "stms-c", 300, 250, 250);
        String foreign = StmsFixtures.solvedPlan(store, "stms-c");
        TenantContext.run("stms-d", () -> assertTrue(store.get("DispatchPlan", foreign).isEmpty()));
        call("stms-d", "stms_explain_plan", "{\"plan\":\"" + foreign + "\"}")
                .body("result.isError", equalTo(true))
                .body("result.content[0].text", containsString("not found"));
        call("stms-d", "stms_vehicle_unavailable", "{\"plan\":\"" + foreign + "\",\"vehicle\":\"51C-12345\"}")
                .body("result.isError", equalTo(true));
        call("stms-d", "stms_optimize_route", "{\"hub\":\"HCM-TB\",\"date\":\"2026-10-23\"}")
                .body("result.isError", equalTo(true))
                .body("result.content[0].text", containsString("hub HCM-TB not found"));
    }

    @Test
    @TestSecurity(user = "u-ops", roles = "/tenants/stms-c/ops")
    void onlyDispatchersAndAdminsMayReplan() {
        StmsFixtures.tenant(store, "stms-c", 300, 250, 250);
        String plan = StmsFixtures.solvedPlan(store, "stms-c");
        String before = TenantContext.call("stms-c", () -> store.get("DispatchPlan", plan).orElseThrow().getString("score"));
        for (String tool : List.of("stms_vehicle_unavailable", "stms_set_weights", "stms_copilot", "stms_optimize_route")) {
            call("stms-c", tool, "{\"plan\":\"" + plan + "\",\"vehicle\":\"51C-12345\",\"priority\":1,\"instruction\":\"tối ưu lại\"}")
                    .body("result.isError", equalTo(true))
                    .body("result.content[0].text", containsString("not permitted"));
        }
        TenantContext.run("stms-c", () -> {
            assertEquals(before, store.get("DispatchPlan", plan).orElseThrow().getString("score"));
            assertTrue(store.get("DispatchPlan", plan).orElseThrow().get("unavailable_vehicles") == null);
        });
    }

    @Test
    @TestSecurity(user = "u-care", roles = "/tenants/stms-c/care")
    void customerCareSeesNoRoutingTools() {
        call("stms-c", "stms_explain_plan", "{\"plan\":\"DP-0000-0000\"}")
                .body("result.isError", equalTo(true))
                .body("result.content[0].text", containsString("not permitted"))
                .body("result.content[0].text", not(containsString("not found")));
    }
}
