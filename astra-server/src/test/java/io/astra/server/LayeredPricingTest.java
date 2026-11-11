package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.pluginhost.rollout.RolloutJob;
import io.astra.spi.script.ScriptException;
import io.astra.spi.script.ScriptRuntime;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prices in layers: Drools rules as data (Tier 0, edited on the Desk, released like code), then a customer's own Wasm
 * plugin (Tier 2) that inherits the rule price and declares only its differences.
 */
@QuarkusTest
class LayeredPricingTest {

    private static final String REMOTE = "{\"customer\":\"%s\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\",\"receiver_address\":\"x\","
            + "\"zone\":\"Remote\",\"service\":\"%s\",\"weight_kg\":2,\"cod_amount\":%d,\"length_cm\":40,\"width_cm\":30,\"height_cm\":30}";
    private static final String SURCHARGE = """

            rule "remote-surcharge"
                salience 40
            when
                QuoteRequest(zone == "Remote")
            then
                lines.add("remote_fee", "Phụ phí vùng xa (bắt buộc)", 3000);
                lines.lock("remote_fee");
            end
            """;

    @Inject DocumentStore store;
    @Inject RolloutJob rollout;
    @Inject ScriptRuntime scripts;

    private static RequestSpecification in(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    static String drl(String name) throws Exception {
        try (InputStream in = LayeredPricingTest.class.getClassLoader().getResourceAsStream("META-INF/astra/rules/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String wasm(String name) throws Exception {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("../astra-apps/express/wasm/modules/" + name + ".wasm")));
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Saves a rule set on the Desk, submits it (a draft release), approves the release; returns the release stage. */
    private static String releaseRules(String tenant, String version, String rules) {
        String rs = in(tenant).body("{\"ruleset_name\":\"standard-tariff\",\"version\":\"" + version + "\",\"rules\":\"" + json(rules) + "\"}")
                .post("/api/doc/PricingRuleset").then().statusCode(201).extract().path("name");
        String release = in(tenant).post("/api/doc/PricingRuleset/" + rs + "/submit").then().statusCode(200).extract().path("data.release");
        return in(tenant).post("/api/doc/AppRelease/" + release + "/submit").then().statusCode(200).extract().path("data.stage");
    }

    private static JsonPath order(String tenant, String customer, String service, long cod) {
        return in(tenant).body(REMOTE.formatted(customer, service, cod)).post("/api/doc/CustomerOrder").then().statusCode(201).extract().jsonPath();
    }

    private static Map<String, Object> line(JsonPath order, String code) {
        return order.getList("data.price_breakdown", Map.class).stream().filter(l -> code.equals(l.get("code")))
                .findFirst().map(m -> (Map<String, Object>) m).orElse(null);
    }

    private void shopAbc(String tenant) {
        ExpressFixtures.tenant(store, tenant);
        TenantContext.run(tenant, () -> {
            if (store.get("Customer", "ABC").isPresent()) return;
            var c = store.insert("Customer", Map.of("code", "ABC", "customer_name", "Shop ABC", "segment", "B2B"), "fixture");
            store.updateSystemFields("Customer", "ABC", c.version(), Map.of("monthly_orders", 1350, "return_rate", 0.031));
        });
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/price-a/admin")
    void rulesAreDataEditedOnTheDeskCheckedOnSaveAndReleasedWithoutARestart() throws Exception {
        ExpressFixtures.tenant(store, "price-a");
        in("price-a").body(REMOTE.formatted("C001", "Express", 500_000)).post("/api/doc/CustomerOrder").then().statusCode(201)
                .body("data.priced_by", equalTo("Standard tariff"))
                .body("data.shipping_fee", equalTo(94_000))                    // 59,000 × 1.5 + 5,000, rounded up
                .body("data.price_breakdown.source", hasItem("tariff"));

        // refused on save, with the reason
        in("price-a").body("{\"ruleset_name\":\"x\",\"version\":\"0\",\"rules\":\"" + json(drl("standard-tariff.drl")
                        .replace("lines.add(\"express\"", "System.exit(0); lines.add(\"express\"")) + "\"}")
                .post("/api/doc/PricingRuleset").then().statusCode(422).body(containsString("'System' is not allowed"));
        in("price-a").body("{\"ruleset_name\":\"x\",\"version\":\"0\",\"rules\":\"" + json(drl("standard-tariff.drl").replace("QuoteRequest(express == true)", "QuoteRequest(express == true")) + "\"}")
                .post("/api/doc/PricingRuleset").then().statusCode(422);

        // the standard tariff as rules: same fee, now line by line with the rule that produced each line
        in("price-a").body("{\"ruleset_name\":\"standard-tariff\",\"version\":\"0.9\",\"rules\":\"" + json(drl("standard-tariff.drl")) + "\"}")
                .post("/api/doc/PricingRuleset").then().statusCode(201)
                .body("data.rule_count", equalTo(5)).body("data.self_test", startsWith("5 rules; sample 2 kg Domestic COD 500k = 45000 VND"));
        assertEquals("Live", releaseRules("price-a", "1.0.0", drl("standard-tariff.drl")));
        JsonPath ruled = order("price-a", "C001", "Express", 500_000);
        assertEquals(94_000, ruled.getInt("data.shipping_fee"));
        assertTrue(ruled.getString("data.priced_by").startsWith("Rules standard-tariff@1.0.0"), ruled.getString("data.priced_by"));
        assertEquals("rule:base-remote", line(ruled, "base").get("source"));
        assertEquals("rule:express", line(ruled, "express").get("source"));
        assertEquals("rule:cod-fee", line(ruled, "cod_fee").get("source"));

        // the business adds a rule: canary on this pod right after approval, no restart
        assertEquals("Canary", releaseRules("price-a", "1.1.0", drl("standard-tariff.drl") + SURCHARGE));
        JsonPath surcharged = order("price-a", "C001", "Standard", 0);
        assertEquals("rule:remote-surcharge", line(surcharged, "remote_fee").get("source"));
        assertEquals(true, line(surcharged, "remote_fee").get("locked"));
        assertEquals(62_000, surcharged.getInt("data.shipping_fee"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/price-b/admin")
    void aCustomerPluginInheritsTheRulePriceAndOnlyDeclaresItsDifferences() throws Exception {
        shopAbc("price-b");
        releaseRules("price-b", "1.0.0", drl("standard-tariff.drl"));
        String plugin = in("price-b").body("{\"slot\":\"pricing.customer\",\"tier\":\"wasm\",\"app_id\":\"khaigia-shopabc\",\"version\":\"1.0.0\","
                        + "\"customer\":\"ABC\",\"artifact\":\"" + wasm("khaigia-shopabc-1.0.0") + "\"}")
                .post("/api/doc/AppRelease").then().statusCode(201)
                .body("data.self_test", containsString("operations")).extract().path("name");
        in("price-b").post("/api/doc/AppRelease/" + plugin + "/submit").then().statusCode(200).body("data.stage", equalTo("Live"));

        // Shop ABC: 1,350 orders a month (−8 % base), 3.1 % returns (no COD fee), a 6 kg-volumetric 2 kg parcel (bulky, capped +40 %)
        JsonPath abc = order("price-b", "ABC", "Standard", 320_000);
        assertEquals("scale 0.92 by wasm:khaigia-shopabc@1.0.0", line(abc, "base").get("op"));
        assertEquals(54_280, ((Number) line(abc, "base").get("amount")).intValue());
        assertEquals(0, ((Number) line(abc, "cod_fee").get("amount")).intValue());
        assertEquals(8_000, ((Number) line(abc, "bulky").get("amount")).intValue());
        assertEquals(63_000, abc.getInt("data.shipping_fee"));
        assertTrue(abc.getString("data.priced_by").contains("khaigia-shopabc@1.0.0 on pod-a"), abc.getString("data.priced_by"));
        // another customer: the rule price only
        JsonPath other = order("price-b", "C001", "Standard", 320_000);
        assertEquals(64_000, other.getInt("data.shipping_fee"));        // 59,000 + 5,000 COD fee
        assertTrue(other.getList("data.price_breakdown.op").stream().allMatch(o -> o == null));

        // the business changes the rules; Shop ABC's price inherits it without anyone touching the plugin
        releaseRules("price-b", "1.1.0", drl("standard-tariff.drl") + SURCHARGE);
        JsonPath inherited = order("price-b", "ABC", "Standard", 320_000);
        assertEquals("rule:remote-surcharge", line(inherited, "remote_fee").get("source"));
        assertEquals(null, line(inherited, "remote_fee").get("op"));
        assertEquals("scale 0.92 by wasm:khaigia-shopabc@1.0.0", line(inherited, "base").get("op"));
        assertEquals(66_000, inherited.getInt("data.shipping_fee"));

        // contract annex v2 (the 11.11 week keeps the best tier): a few changed lines, hot-reloaded as a canary
        String v2 = in("price-b").body("{\"slot\":\"pricing.customer\",\"tier\":\"wasm\",\"app_id\":\"khaigia-shopabc\",\"version\":\"1.1.0\","
                        + "\"customer\":\"ABC\",\"artifact\":\"" + wasm("khaigia-shopabc-1.1.0") + "\"}")
                .post("/api/doc/AppRelease").then().statusCode(201).extract().path("name");
        in("price-b").post("/api/doc/AppRelease/" + v2 + "/submit").then().statusCode(200).body("data.stage", equalTo("Canary"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        boolean elevenElevenWeek = today.getMonthValue() == 11 && today.getDayOfMonth() >= 9 && today.getDayOfMonth() <= 15;
        JsonPath annex2 = order("price-b", "ABC", "Standard", 320_000);
        assertEquals(elevenElevenWeek ? "scale 0.85 by wasm:khaigia-shopabc@1.1.0" : "scale 0.92 by wasm:khaigia-shopabc@1.1.0",
                line(annex2, "base").get("op"));
    }

    @Test
    void theBytesAbiChecksEveryPointerAndStillHasADeadline() throws Exception {
        byte[] probe = Base64.getDecoder().decode(wasm("abi-probe"));
        byte[] input = "ctx\tzone=Remote\n".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(input, scripts.callBytes(probe, "echo", 50, input));
        assertEquals("invalid", assertThrows(ScriptException.class, () -> scripts.callBytes(probe, "out_of_bounds", 50, input)).reason());
        assertEquals("invalid", assertThrows(ScriptException.class, () -> scripts.callBytes(probe, "too_long", 50, input)).reason());
        long started = System.nanoTime();
        assertEquals("timeout", assertThrows(ScriptException.class, () -> scripts.callBytes(probe, "spin", 50, input)).reason());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000);
        assertEquals("invalid", assertThrows(ScriptException.class, () -> scripts.callBytes(probe, "echo", 50, new byte[70_000])).reason());
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/price-c/admin")
    void theAnalystsToolsProfileTheCustomerAndBacktestADraftModule() throws Exception {
        shopAbc("price-c");
        for (int i = 0; i < 3; i++) {
            String o = order("price-c", "ABC", "Standard", 320_000).getString("name");
            in("price-c").post("/api/doc/CustomerOrder/" + o + "/submit").then().statusCode(200);
        }
        String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"%s\",\"arguments\":%s}}";
        in("price-c").body(call.formatted("customer_pricing_profile", "{\"customer\":\"ABC\"}")).post("/mcp").then().statusCode(200)
                .body("result.structuredContent.orders_30_days", equalTo(3))
                .body("result.structuredContent.orders_by_zone.Remote", equalTo(3))
                .body("result.structuredContent.bulky_share", equalTo(1.0f));
        in("price-c").body(call.formatted("quote_backtest", "{\"customer\":\"ABC\",\"module_base64\":\"" + wasm("khaigia-shopabc-1.0.0") + "\"}"))
                .post("/mcp").then().statusCode(200)
                .body("result.isError", equalTo(false))
                .body("result.structuredContent.orders", equalTo(3))
                .body("result.structuredContent.revenue_rule_layer_vnd", equalTo(192_000))
                .body("result.structuredContent.revenue_with_module_vnd", equalTo(189_000))
                .body("result.structuredContent.operations.'scale base'", equalTo(3))
                .body("result.structuredContent.samples[0].lines", not(List.of()));
    }
}
