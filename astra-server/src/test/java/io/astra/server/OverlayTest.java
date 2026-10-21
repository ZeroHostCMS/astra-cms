package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/** Tenant overlays change one tenant's DocType at runtime and leave every other tenant alone. */
@QuarkusTest
class OverlayTest {

    private static final String ORDER = "{\"customer\":\"C001\",\"receiver_name\":\"A\",\"receiver_phone\":\"0901234567\","
            + "\"receiver_address\":\"x\",\"weight_kg\":1";

    @Inject DocumentStore store;

    private static RequestSpecification in(String tenant) {
        return given().header("X-Astra-Tenant", tenant).contentType("application/json");
    }

    private static byte[] utf8(String yaml) {
        return yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String hanoiOverlay() throws Exception {
        return Files.readString(Path.of("../tenants/hanoi-express/overlays/customer-order.overlay.yaml"));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = {"/tenants/ov-a/admin", "/tenants/ov-b/admin"})
    void anOverlayReshapesOneTenantOnly() throws Exception {
        ExpressFixtures.tenant(store, "ov-a");
        ExpressFixtures.tenant(store, "ov-b");
        given().header("X-Astra-Tenant", "ov-a").contentType("application/yaml").body(utf8(hanoiOverlay()))
                .put("/api/overlay/CustomerOrder").then().statusCode(200).body("version", equalTo(1));

        in("ov-a").get("/api/doctype/CustomerOrder").then().statusCode(200)
                .body("fields.name", hasItem("tax_code"))
                .body("fields.find { it.name == 'zone' }.options", hasItem("Island"))
                .body("fields.find { it.name == 'note' }.required", equalTo(true));
        in("ov-b").get("/api/doctype/CustomerOrder").then().statusCode(200)
                .body("fields.name", not(hasItem("tax_code")));

        in("ov-a").body(ORDER + ",\"zone\":\"Island\",\"vat_invoice\":true,\"tax_code\":\"0101234567\",\"note\":\"Gọi trước\"}")
                .post("/api/doc/CustomerOrder").then().statusCode(201).body("data.tax_code", equalTo("0101234567"));
        in("ov-a").body(ORDER + "}").post("/api/doc/CustomerOrder").then().statusCode(422)
                .body("errors", hasItem("field 'note' is required"));
        in("ov-b").body(ORDER + ",\"tax_code\":\"0101234567\"}").post("/api/doc/CustomerOrder").then().statusCode(422)
                .body("errors", hasItem("unknown field 'tax_code' for DocType CustomerOrder"));
        in("ov-b").body(ORDER + "}").post("/api/doc/CustomerOrder").then().statusCode(201);

        given().header("X-Astra-Tenant", "ov-a").delete("/api/overlay/CustomerOrder").then().statusCode(204);
        in("ov-a").get("/api/doctype/CustomerOrder").then().body("fields.name", not(hasItem("tax_code")));
    }

    @Test
    @TestSecurity(user = "u-admin", roles = "/tenants/ov-c/admin")
    void overlaysThatWouldBreakTheAppAreRejected() {
        given().header("X-Astra-Tenant", "ov-c").contentType("application/yaml")
                .body(utf8("fields:\n  modify:\n    - { name: nope, required: true }\n"))
                .put("/api/overlay/CustomerOrder").then().statusCode(422)
                .body("errors[0]", equalTo("cannot modify unknown field 'nope' of CustomerOrder"));
        given().header("X-Astra-Tenant", "ov-c").contentType("application/yaml")
                .body(utf8("fields:\n  add:\n    - { name: zone, type: Data }\n"))
                .put("/api/overlay/CustomerOrder").then().statusCode(422)
                .body("errors[0]", equalTo("field 'zone' already exists in CustomerOrder"));
        given().header("X-Astra-Tenant", "ov-c").contentType("application/yaml")
                .body(utf8("fields:\n  modify:\n    - { name: zone, type: Int }\n"))
                .put("/api/overlay/CustomerOrder").then().statusCode(422)
                .body("errors[0]", containsString("'type' cannot be changed"));
    }

    @Test
    @TestSecurity(user = "u-sales", roles = "/tenants/ov-d/sales")
    void onlyTenantAdminsChangeOverlays() {
        given().header("X-Astra-Tenant", "ov-d").contentType("application/yaml").body(utf8("fields: {}\n"))
                .put("/api/overlay/CustomerOrder").then().statusCode(403);
    }
}
