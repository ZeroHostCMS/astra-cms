package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STMS through the generated REST API: the solve action, infeasibility, the dispatch lifecycle and the dispatcher role. */
@QuarkusTest
class StmsDispatchTest {

    @Inject DocumentStore store;

    private static String newPlan(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json")
                .body(Map.of("plan_date", StmsFixtures.DATE, "hub", StmsFixtures.HUB, "solve", true, "solve_seconds", 2))
                .post("/api/doc/DispatchPlan").then().statusCode(201).extract().jsonPath().getString("name");
    }

    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-a/dispatcher")
    void solvingRoutesEveryStopWithinCapacityAndTimeWindowsAndSubmitFreezesThePlan() {
        StmsFixtures.tenant(store, "stms-a", 300, 250, 250);
        String plan = newPlan("stms-a");

        JsonPath doc = given().header("X-Astra-Tenant", "stms-a").get("/api/doc/DispatchPlan/" + plan)
                .then().statusCode(200)
                .body("data.status", equalTo("Solved"))
                .body("data.feasible", equalTo(true))
                .body("data.score", startsWith("0hard/"))
                .body("data.solve", equalTo(false))
                .body("data.route_summary", containsString("51C-"))
                .extract().jsonPath();
        List<Map<String, Object>> routes = doc.getList("data.routes");
        assertEquals(20, routes.size(), "every stop is routed");
        Map<String, Double> load = new HashMap<>();
        for (Map<String, Object> r : routes) {
            load.merge((String) r.get("vehicle"), ((Number) r.get("demand_kg")).doubleValue(), Double::sum);
            assertEquals(0, ((Number) r.get("late_min")).intValue());
            String window = (String) r.get("window");
            assertTrue(((String) r.get("start")).compareTo(window.substring(6)) <= 0, r + " starts after its window");
        }
        assertTrue(load.getOrDefault("51C-12345", 0.0) <= 300 && load.getOrDefault("51C-67890", 0.0) <= 250
                && load.getOrDefault("51D-24680", 0.0) <= 250, "loads " + load);
        assertTrue(doc.getInt("data.solve_ms") <= 2_500, "the time budget bounds solving");

        given().header("X-Astra-Tenant", "stms-a").contentType("application/json").post("/api/doc/DispatchPlan/" + plan + "/submit")
                .then().statusCode(200).body("docstatus", equalTo(1)).body("data.status", equalTo("Dispatched"));
        given().header("X-Astra-Tenant", "stms-a").contentType("application/json").body(Map.of("solve", true))
                .put("/api/doc/DispatchPlan/" + plan)
                .then().statusCode(422).body("errors[0]", containsString("immutable"));
    }

    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-b/dispatcher")
    void anOverloadedFleetIsReportedAsInfeasibleAndCannotBeDispatched() {
        StmsFixtures.tenant(store, "stms-b", 100);
        String plan = newPlan("stms-b");
        String score = given().header("X-Astra-Tenant", "stms-b").get("/api/doc/DispatchPlan/" + plan)
                .then().statusCode(200)
                .body("data.status", equalTo("Infeasible"))
                .body("data.feasible", equalTo(false))
                .body("data.last_change.violations", hasItem(containsString("capacity")))
                .extract().jsonPath().getString("data.score");
        // 452 kg on one 100 kg vehicle: one hard point per kg over capacity, plus any minutes late
        assertTrue(Long.parseLong(score.substring(0, score.indexOf("hard"))) <= -352, score);
        given().header("X-Astra-Tenant", "stms-b").contentType("application/json").post("/api/doc/DispatchPlan/" + plan + "/submit")
                .then().statusCode(422).body("errors[0]", containsString("feasible"));
    }

    @Test
    @TestSecurity(user = "u-ops", roles = "/tenants/stms-a/ops")
    void operationsCanReadPlansButNotSolveThem() {
        StmsFixtures.tenant(store, "stms-a", 300, 250, 250);
        String plan = StmsFixtures.solvedPlan(store, "stms-a");
        given().header("X-Astra-Tenant", "stms-a").get("/api/doc/DispatchPlan/" + plan).then().statusCode(200);
        given().header("X-Astra-Tenant", "stms-a").contentType("application/json").body(Map.of("solve", true))
                .put("/api/doc/DispatchPlan/" + plan).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "u-dispatcher", roles = "/tenants/stms-a/dispatcher")
    void malformedStopsAndSettingsAreRejected() {
        StmsFixtures.tenant(store, "stms-a", 300, 250, 250);
        given().header("X-Astra-Tenant", "stms-a").contentType("application/json")
                .body(Map.of("hub", StmsFixtures.HUB, "plan_date", StmsFixtures.DATE, "label", "x", "latitude", 95,
                        "longitude", 106.7, "demand_kg", 5))
                .post("/api/doc/DeliveryStop").then().statusCode(422).body("errors[0]", containsString("latitude"));
        given().header("X-Astra-Tenant", "stms-a").contentType("application/json")
                .body(Map.of("hub", StmsFixtures.HUB, "plan_date", StmsFixtures.DATE, "label", "x", "latitude", 10.8,
                        "longitude", 106.7, "demand_kg", 5, "window_from", "11:00", "window_to", "09:00"))
                .post("/api/doc/DeliveryStop").then().statusCode(422).body("errors[0]", containsString("window_from"));
        given().header("X-Astra-Tenant", "stms-a").contentType("application/json")
                .body(Map.of("plan_date", StmsFixtures.DATE, "hub", StmsFixtures.HUB, "weights", Map.of("speed", 3)))
                .post("/api/doc/DispatchPlan").then().statusCode(422).body("errors[0]", containsString("unknown weight"));
        given().header("X-Astra-Tenant", "stms-a").contentType("application/json")
                .body(Map.of("plan_date", StmsFixtures.DATE, "hub", StmsFixtures.HUB, "score", "0hard/0soft"))
                .post("/api/doc/DispatchPlan").then().statusCode(422).body("errors[0]", containsString("read-only"));
    }
}
