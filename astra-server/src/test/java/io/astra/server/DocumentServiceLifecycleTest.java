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

/**
 * Submit and cancel through the app SPI: hooks of the documents a hook submits still run, and the signed-in
 * caller's permissions still apply, so a cascade cannot do what its caller could not (see {@link CascadeTestApp}).
 */
@QuarkusTest
class DocumentServiceLifecycleTest {

    @Inject DocumentStore store;

    private static RequestSpecification in(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    private void batch(String tenant, String code) {
        TenantContext.run(tenant, () -> {
            store.insert("TestBatch", Map.of("code", code), "fixture");
            store.insert("TestBatchItem", Map.of("code", code + "-1", "batch", code), "fixture");
            store.insert("TestBatchItem", Map.of("code", code + "-2", "batch", code), "fixture");
        });
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/cascade-a/admin")
    void aHookSubmitsAndCancelsOtherDocumentsWithTheirOwnHooks() {
        batch("cascade-a", "B1");
        in("cascade-a").post("/api/doc/TestBatch/B1/submit").then().statusCode(200);
        in("cascade-a").get("/api/doc/TestBatchItem/B1-2").then().body("docstatus", equalTo(1))
                .body("data.stamp", equalTo("submitted with its batch"));
        in("cascade-a").post("/api/doc/TestBatch/B1/cancel").then().statusCode(200);
        in("cascade-a").get("/api/doc/TestBatchItem/B1-1").then().body("docstatus", equalTo(2));
    }

    @Test
    @TestSecurity(user = "u-clerk", roles = "/tenants/cascade-b/clerk")
    void aCascadeIsBoundByTheCallersPermissionsAndRollsBackWhole() {
        batch("cascade-b", "B2");
        in("cascade-b").post("/api/doc/TestBatch/B2/submit").then().statusCode(403)
                .body("error", equalTo("not permitted: submit TestBatchItem"));
        in("cascade-b").get("/api/doc/TestBatch/B2").then().body("docstatus", equalTo(0));
        in("cascade-b").get("/api/doc/TestBatchItem/B2-1").then().body("docstatus", equalTo(0));
    }

    @Test
    @TestSecurity(user = "u-clerk", roles = "/tenants/cascade-c/clerk")
    void cancellingThroughAHookNeedsTheCallersCancelPermission() {
        batch("cascade-c", "B3");
        TenantContext.run("cascade-c", () -> store.submit("TestBatch", "B3"));   // no signed-in caller: kernel fixture
        in("cascade-c").post("/api/doc/TestBatch/B3/cancel").then().statusCode(403)
                .body("error", equalTo("not permitted: cancel TestBatchItem"));
        in("cascade-c").get("/api/doc/TestBatchItem/B3-1").then().body("docstatus", equalTo(1));
    }
}
