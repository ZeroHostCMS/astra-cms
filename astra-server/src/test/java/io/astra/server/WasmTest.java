package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tier 2: tenant pricing scripts compiled from Rust to WebAssembly, run in the Chicory sandbox. */
@QuarkusTest
class WasmTest {

    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\","
            + "\"receiver_address\":\"x\",\"zone\":\"Domestic\",\"service\":\"Express\",\"weight_kg\":3,\"cod_amount\":500000}";

    @Inject ScriptRuntime scripts;
    @Inject DocumentStore store;

    static byte[] module(String name) throws Exception {
        return Files.readAllBytes(Path.of("../astra-apps/express/wasm/modules/" + name + ".wasm"));
    }

    private static RequestSpecification in(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    @Test
    void theTetPromotionPricesParcels() throws Exception {
        byte[] tet = module("tet-promo");
        assertEquals(24_000, scripts.callLong(tet, "fee", 50, 1_000, 1, 0, 0));      // 30,000 − 20 %
        assertEquals(56_000, scripts.callLong(tet, "fee", 50, 3_000, 1, 1, 500_000)); // 50,000 − 20 % × 1.3 + COD 4,000
        assertEquals(0, scripts.callLong(tet, "fee", 50, 1_500, 0, 0, 0));           // free same-city under 2 kg
    }

    @Test
    void aRunawayScriptIsStoppedAtItsDeadlineAndTheKernelCarriesOn() throws Exception {
        long started = System.nanoTime();
        ScriptException e = assertThrows(ScriptException.class, () -> scripts.callLong(module("runaway"), "fee", 50, 1, 1, 0, 0));
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertEquals("timeout", e.reason());
        assertTrue(millis < 1_000, "stopped after " + millis + " ms");
        assertEquals(24_000, scripts.callLong(module("tet-promo"), "fee", 50, 1_000, 1, 0, 0));
    }

    @Test
    void garbageIsNotAModule() {
        ScriptException e = assertThrows(ScriptException.class, () -> scripts.callLong(new byte[] {1, 2, 3}, "fee", 50));
        assertEquals("invalid", e.reason());
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/wasm-a/admin")
    void anActiveRulePricesItsSegmentAndARunawayRuleCannotBeSaved() throws Exception {
        ExpressFixtures.tenant(store, "wasm-a");
        TenantContext.run("wasm-a", () -> store.insert("Customer", Map.of("code", "R001", "customer_name", "Lẻ", "segment", "Retail"), "fixture"));

        String tet = Base64.getEncoder().encodeToString(module("tet-promo"));
        in("wasm-a").body("{\"rule_name\":\"Tết B2B\",\"segment\":\"B2B\",\"active\":true,\"module_wasm\":\"" + tet + "\"}")
                .post("/api/doc/PricingRule").then().statusCode(201)
                .body("data.self_test", startsWith("fee(1 kg, Domestic) = 24000 VND"))
                .body("data.module_bytes", equalTo(module("tet-promo").length));

        in("wasm-a").body(ORDER).post("/api/doc/CustomerOrder").then().statusCode(201)
                .body("data.shipping_fee", equalTo(56000)).body("data.priced_by", containsString("(Wasm)"));
        in("wasm-a").body(ORDER.replace("C001", "R001")).post("/api/doc/CustomerOrder").then().statusCode(201)
                .body("data.priced_by", equalTo("Standard tariff"));

        String runaway = Base64.getEncoder().encodeToString(module("runaway"));
        in("wasm-a").body("{\"rule_name\":\"Runaway\",\"active\":true,\"module_wasm\":\"" + runaway + "\"}")
                .post("/api/doc/PricingRule").then().statusCode(422)
                .body("errors[0]", containsString("module rejected (timeout)"));
    }
}
