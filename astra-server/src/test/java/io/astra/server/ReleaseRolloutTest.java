package io.astra.server;

import io.agroal.api.AgroalDataSource;
import io.astra.pluginhost.jvm.TestPlugins;
import io.astra.pluginhost.rollout.RolloutJob;
import io.astra.spi.app.PluginException;
import io.astra.spi.integration.CarrierAdapter;
import io.astra.spi.integration.CarrierAdapter.Shipment;
import io.astra.spi.release.Releases;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Releases and the rollout across pods. The test JVM is pod {@code pod-a}; a heartbeat row stands in for {@code pod-b}.
 * The job runs by hand ({@code astra.rollout.every} is off in tests), one call per two-second tick in production.
 */
@QuarkusTest
class ReleaseRolloutTest {

    private static final String T = "rollout-a";
    private static final Shipment REMOTE = new Shipment("WB-1", "ORD-1", "C001", "Remote", "Standard", 1.2, 0);
    private static final Shipment LOCAL = new Shipment("WB-2", "ORD-2", "C001", "Same city", "Standard", 1.0, 0);

    @Inject Releases releases;
    @Inject RolloutJob job;
    @Inject AgroalDataSource dataSource;

    private static RequestSpecification in() {
        return given().header("X-Astra-Tenant", T).contentType("application/json");
    }

    private static String jar(String version, String body) {
        return Base64.getEncoder().encodeToString(TestPlugins.carrier(version, "", "300ms", 32, body));
    }

    private static String fixed(String version) {
        return jar(version, "return new Booking(\"fastship\", \"" + version + "\", 1000, s.service(), \"\");");
    }

    private static String propose(String artifact, String extra) {
        return in().body("{\"slot\":\"test.carrier\",\"tier\":\"isolated-jvm\",\"promote_after\":5,\"artifact\":\"" + artifact + "\"" + extra + "}")
                .post("/api/doc/AppRelease").then().statusCode(201).extract().path("name");
    }

    private static void approve(String release) {
        in().post("/api/doc/AppRelease/" + release + "/submit").then().statusCode(200);
    }

    private String served(Shipment s, String customer) {
        return TenantContext.call(T, () -> releases.plugin("test.carrier", customer, CarrierAdapter.class)
                .map(b -> b.target().book(s).tracking() + " on " + b.pod()).orElse("none"));
    }

    private void calls(int n, Shipment s) {
        for (int i = 0; i < n; i++) {
            try {
                served(s, null);
            } catch (PluginException expected) {
                // counted as an error of the release that served it
            }
        }
    }

    private String stage(String release) {
        return in().get("/api/doc/AppRelease/" + release).then().statusCode(200).extract().path("data.stage");
    }

    private void heartbeat(String pod) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO astra_pod (name, seen_at) VALUES (?, now()) ON CONFLICT (name) DO UPDATE SET seen_at = now()")) {
            ps.setString(1, pod);
            ps.executeUpdate();
        }
    }

    private void patchCanaryPods(String release, String pods) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement set = c.prepareStatement("SELECT set_config('astra.tenant', ?, true)");
                 PreparedStatement ps = c.prepareStatement("UPDATE astra_doc SET data = data || jsonb_build_object('canary_pods', ?::text) "
                         + "WHERE doctype = 'AppRelease' AND name = ?")) {
                set.setString(1, T);
                set.execute();
                ps.setString(1, pods);
                ps.setString(2, release);
                ps.executeUpdate();
            }
            c.commit();
        }
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/rollout-a/admin")
    void canaryByPodPromotesRollsBackAndPinsWin() throws Exception {
        heartbeat("pod-a");
        heartbeat("pod-b");

        // v1: vetted on save, nothing live yet, so approval makes it Live on every pod at once
        String v1 = propose(fixed("1.0.0"), "");
        in().get("/api/doc/AppRelease/" + v1).then()
                .header("X-Astra-Pod", "pod-a")
                .body("data.app_id", equalTo("test.carrier")).body("data.version", equalTo("1.0.0"))
                .body("data.generator", equalTo("human")).body("data.stage", equalTo("Draft"))
                .body("data.self_test", containsString("loaded"));
        approve(v1);
        assertEquals("Live", stage(v1));
        assertEquals("1.0.0 on pod-a", served(LOCAL, null));

        // v2: canary on this pod only; a pod outside the canary keeps v1
        String v2 = propose(fixed("1.1.0"), "");
        approve(v2);
        in().get("/api/doc/AppRelease/" + v2).then().body("data.stage", equalTo("Canary"))
                .body("data.canary_pods", equalTo("pod-a")).body("data.target_pods", equalTo("1/2"));
        assertEquals("1.1.0 on pod-a", served(LOCAL, null));
        patchCanaryPods(v2, "pod-b");
        job.run();                                                    // every pod re-reads stages from PostgreSQL
        assertEquals("1.0.0 on pod-a", served(LOCAL, null), "pod outside the canary serves the previous release");
        patchCanaryPods(v2, "pod-a");
        job.run();

        // 5 healthy calls per canary pod: pod-b joins (2/2); 5 more each: Live, and v1 is retired
        calls(5, LOCAL);
        job.run();
        in().get("/api/doc/AppRelease/" + v2).then().body("data.stage", equalTo("Canary"))
                .body("data.canary_pods", equalTo("pod-a,pod-b")).body("data.target_pods", equalTo("2/2"));
        calls(5, LOCAL);
        job.run();
        assertEquals("Live", stage(v2));
        assertEquals("Retired", stage(v1));
        in().get("/api/doc/AppRelease/" + v2).then().body("data.calls", equalTo(11)).body("data.errors", equalTo(0));   // 10 + the first canary check

        // v3 fails on Remote parcels: over its error budget on the canary pod it is rolled back, and calls go to v2 again
        String v3 = propose(jar("1.2.0-bad", "if (s.zone().equals(\"Remote\")) throw new IllegalStateException(\"no route\"); "
                + "return new Booking(\"fastship\", \"1.2.0-bad\", 1000, s.service(), \"\");"), "");
        approve(v3);
        assertEquals("1.2.0-bad on pod-a", served(LOCAL, null));
        calls(6, REMOTE);
        job.run();
        in().get("/api/doc/AppRelease/" + v3).then().body("data.stage", equalTo("Rolled back"))
                .body("data.stage_reason", containsString("exceeded budget"));
        assertEquals("1.1.0 on pod-a", served(REMOTE, null));

        // pins: the tenant pinned to 1.0.0 (retired, still approved), one customer pinned to 1.1.0
        in().body("{\"slot\":\"test.carrier\",\"app_id\":\"test.carrier\",\"version\":\"1.0.0\"}").post("/api/doc/AppPin").then().statusCode(201);
        in().body("{\"slot\":\"test.carrier\",\"customer\":\"C009\",\"app_id\":\"test.carrier\",\"version\":\"1.1.0\"}")
                .post("/api/doc/AppPin").then().statusCode(201);
        job.run();
        assertEquals("1.0.0 on pod-a", served(LOCAL, null), "tenant pin wins over live");
        assertEquals("1.1.0 on pod-a", served(LOCAL, "C009"), "customer pin wins over tenant pin");
    }

    @Test
    @TestSecurity(user = "u-bot", roles = "/tenants/rollout-a/release-bot")
    void anAiTokenProposesButCannotApprove() {
        String r = propose(fixed("9.0.0"), "");
        in().get("/api/doc/AppRelease/" + r).then().body("data.generator", equalTo("claude-code"));
        in().post("/api/doc/AppRelease/" + r + "/submit").then().statusCode(403);
        assertEquals("Draft", stage(r));

        // the same through MCP: release_propose makes a vetted draft; release_status reads it; there is no approve tool
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"release_propose\",\"arguments\":"
                + "{\"slot\":\"test.carrier\",\"tier\":\"isolated-jvm\",\"app_id\":\"x\",\"version\":\"x\",\"artifact_base64\":\"" + fixed("9.1.0") + "\"}}}";
        String proposed = in().body(body).post("/mcp").then().statusCode(200)
                .body("result.isError", equalTo(false))
                .body("result.structuredContent.generator", equalTo("claude-code"))
                .body("result.structuredContent.stage", equalTo("Draft"))
                .extract().path("result.structuredContent.release");
        in().body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"release_status\",\"arguments\":{\"release\":\""
                + proposed + "\"}}}").post("/mcp").then().statusCode(200)
                .body("result.structuredContent.items[0].version", equalTo("9.1.0"))
                .body("result.structuredContent.items[0].approved", equalTo(false));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/rollout-a/admin")
    void aJarThatDoesNotImplementTheSlotIsRefusedOnSave() {
        String notAJar = Base64.getEncoder().encodeToString("plain text".getBytes());
        in().body("{\"slot\":\"test.carrier\",\"tier\":\"isolated-jvm\",\"artifact\":\"" + notAJar + "\"}")
                .post("/api/doc/AppRelease").then().statusCode(422).body(containsString("plugin rejected"));
        in().body("{\"slot\":\"no.such.slot\",\"tier\":\"wasm\",\"artifact\":\"AA==\"}")
                .post("/api/doc/AppRelease").then().statusCode(422).body(containsString("unknown slot"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/rollout-a/admin")
    void aTier1JarDoesNotRunUntilItIsApproved() throws Exception {
        String sentinel = "astra.test.ran." + System.nanoTime();
        System.clearProperty(sentinel);
        String jar = Base64.getEncoder().encodeToString(TestPlugins.carrierWithStaticInit("7.0.0", sentinel));

        // Saving a draft reads the manifest but defines no class, so the jar's static initializer has not run.
        String r = propose(jar, "");
        in().get("/api/doc/AppRelease/" + r).then().body("data.app_id", equalTo("test.carrier"))
                .body("data.self_test", containsString("after approval"));
        assertNull(System.getProperty(sentinel), "the jar ran on save, before approval");

        // Approval, then a routed call, is the first time the jar's code runs (its static initializer sets the sentinel).
        heartbeat("pod-a");
        approve(r);
        assertEquals("WB-2 on pod-a", served(LOCAL, null));   // this carrier returns the waybill as its tracking number
        assertEquals("ran", System.getProperty(sentinel), "the jar never ran even after approval and a call");
        System.clearProperty(sentinel);
        in().post("/api/doc/AppRelease/" + r + "/cancel").then().statusCode(200);   // leave the shared slot clean
    }
}
