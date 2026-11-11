package io.astra.server;

import io.astra.doctype.jobs.TenantJobRunner;
import io.astra.doctype.store.DocumentStore;
import io.astra.pluginhost.jvm.TestPlugins;
import io.astra.pluginhost.rollout.RolloutJob;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Market integrations as Tier 1 plugins: the carrier books waybills from a background job (never inside the order's
 * submit), and each tenant issues e-invoices with its own country's plugin, so adding a country is adding a module.
 */
@QuarkusTest
class ExpressIntegrationTest {

    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\","
            + "\"receiver_address\":\"x\",\"zone\":\"Remote\",\"service\":\"Standard\",\"weight_kg\":1,\"cod_amount\":0}";

    @Inject DocumentStore store;
    @Inject TenantJobRunner jobs;
    @Inject RolloutJob rollout;

    private static RequestSpecification in(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    private static String b64(byte[] jar) {
        return Base64.getEncoder().encodeToString(jar);
    }

    /** An e-invoice plugin for {@code country}, or one that always fails. */
    private static byte[] einvoice(String country, String body) {
        String id = "test.einvoice-" + country.toLowerCase();
        return TestPlugins.jar("""
                id: %s
                version: 1.0.0
                tier: isolated-jvm
                entrypoint: test.einvoice.%s.Provider
                spi: io.astra.spi.integration.EInvoiceProvider
                runtime: { timeout: 300ms, max-concurrency: 4 }
                """.formatted(id, country.toLowerCase()), Map.of("test.einvoice." + country.toLowerCase() + ".Provider", """
                package test.einvoice.%s;
                import io.astra.spi.integration.EInvoiceProvider;
                public class Provider implements EInvoiceProvider {
                    public String country() { return "%s"; }
                    public IssuedInvoice issue(InvoiceRequest r) { %s }
                }
                """.formatted(country.toLowerCase(), country, body)));
    }

    private static void release(String tenant, String slot, String artifact) {
        String name = in(tenant).body("{\"slot\":\"" + slot + "\",\"tier\":\"isolated-jvm\",\"artifact\":\"" + artifact + "\"}")
                .post("/api/doc/AppRelease").then().statusCode(201).extract().path("name");
        in(tenant).post("/api/doc/AppRelease/" + name + "/submit").then().statusCode(200).body("data.stage", equalTo("Live"));
    }

    private static String submittedOrder(String tenant) {
        String order = in(tenant).body(ORDER).post("/api/doc/CustomerOrder").then().statusCode(201).extract().path("name");
        in(tenant).post("/api/doc/CustomerOrder/" + order + "/submit").then().statusCode(200)
                .body("data.einvoice_status", equalTo("Pending"));
        return order;
    }

    @Test
    @TestSecurity(user = "u-admin", roles = {"/tenants/int-vn/admin", "/tenants/int-kh/admin"})
    void carrierBooksOutsideTheSubmitAndEachTenantInvoicesWithItsCountryPlugin() {
        ExpressFixtures.tenant(store, "int-vn");
        ExpressFixtures.tenant(store, "int-kh");
        release("int-vn", "carrier.booking", b64(TestPlugins.carrier("1.0.0", "", "300ms", 8,
                "return new Booking(\"FastShip\", \"FS-\" + s.waybill(), 38000, \"STD\", \"\");")));

        // submit only marks the waybill Pending; the carrier is called by the job, after the order committed
        String order = submittedOrder("int-vn");
        String waybill = in("int-vn").get("/api/doc/CustomerOrder/" + order).then().extract().path("data.waybill");
        in("int-vn").get("/api/doc/Waybill/" + waybill).then().body("data.carrier_status", equalTo("Pending"));
        rollout.run();
        jobs.runFor("int-vn");
        in("int-vn").get("/api/doc/Waybill/" + waybill).then()
                .body("data.carrier_status", equalTo("Booked"))
                .body("data.carrier_tracking", equalTo("FS-" + waybill))
                .body("data.booked_by", equalTo("test.carrier@1.0.0 on pod-a"));

        // no e-invoice plugin for the tenant yet: the order waits, nothing fails
        in("int-vn").get("/api/doc/CustomerOrder/" + order).then().body("data.einvoice_status", equalTo("Pending (no provider)"));

        // one module per country, pinned per tenant, both tenants at once, no restart
        release("int-vn", "einvoice.issue", b64(einvoice("VN",
                "return new IssuedInvoice(\"1C26TAS-0001\", \"VN-1\", \"issued\", 38000, 3040, 41040, \"VND\");")));
        release("int-kh", "einvoice.issue", b64(einvoice("KH",
                "return new IssuedInvoice(\"KH-INV-0001\", \"KH-1\", \"issued\", 150, 15, 165, \"USD\");")));
        String khOrder = submittedOrder("int-kh");
        rollout.run();
        jobs.runFor("int-vn");
        jobs.runFor("int-kh");
        in("int-vn").get("/api/doc/CustomerOrder/" + order).then()
                .body("data.einvoice_status", equalTo("Issued")).body("data.einvoice_no", equalTo("1C26TAS-0001"))
                .body("data.einvoice_by", startsWith("test.einvoice-vn@1.0.0 on pod-a"));
        in("int-kh").get("/api/doc/CustomerOrder/" + khOrder).then()
                .body("data.einvoice_no", equalTo("KH-INV-0001"))
                .body("data.einvoice_by", equalTo("test.einvoice-kh@1.0.0 on pod-a · 1.65 USD"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/int-fail/admin")
    void aFailingPluginNeverBlocksTheOrder() {
        ExpressFixtures.tenant(store, "int-fail");
        release("int-fail", "carrier.booking", b64(TestPlugins.carrier("1.2.0-bad", "", "300ms", 8,
                "throw new IllegalArgumentException(\"unknown zone \" + s.zone());")));
        release("int-fail", "einvoice.issue", b64(einvoice("XX", "throw new IllegalStateException(\"tax service down\");")));
        String order = submittedOrder("int-fail");
        rollout.run();
        jobs.runFor("int-fail");
        String waybill = in("int-fail").get("/api/doc/CustomerOrder/" + order).then().statusCode(200)
                .body("docstatus", equalTo(1))
                .body("data.einvoice_status", equalTo("Pending"))
                .body("data.einvoice_by", equalTo("test.einvoice-xx@1.0.0 on pod-a failed: failed"))
                .extract().path("data.waybill");
        in("int-fail").get("/api/doc/Waybill/" + waybill).then()
                .body("data.carrier_status", equalTo("Pending"))
                .body("data.booked_by", equalTo("pending (test.carrier@1.2.0-bad on pod-a failed: failed)"));
    }
}
