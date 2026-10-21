package io.astra.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Proves the tenancy boundary end to end: header → membership check → ScopedValue → set_config →
 * row-level security. The test user is an editor in both tenants, so only RLS separates them.
 */
@QuarkusTest
@TestSecurity(user = "u-multi", roles = {"/tenants/acme/editor", "/tenants/globex/editor"})
class TenantIsolationTest {

    @Test
    void documentsOfOneTenantAreInvisibleToAnother() {
        String name = given().header("X-Astra-Tenant", "acme").contentType("application/json")
                .body("{\"title\":\"Xin chào Đà Nẵng\",\"body\":\"hello\"}")
                .when().post("/api/doc/Article")
                .then().statusCode(201)
                .body("data.slug", equalTo("xin-chao-da-nang"))
                .body("data.status", equalTo("Draft"))
                .extract().path("name");

        given().header("X-Astra-Tenant", "acme")
                .when().get("/api/doc/Article")
                .then().statusCode(200).body("name", hasItem(name));

        given().header("X-Astra-Tenant", "globex")
                .when().get("/api/doc/Article")
                .then().statusCode(200).body("name", not(hasItem(name)));

        given().header("X-Astra-Tenant", "globex")
                .when().get("/api/doc/Article/" + name)
                .then().statusCode(404);
    }

    @Test
    void validationRejectsUnknownFieldsAndBadSelectValues() {
        given().header("X-Astra-Tenant", "acme").contentType("application/json")
                .body("{\"title\":\"x\",\"status\":\"Bogus\",\"nope\":1}")
                .when().post("/api/doc/Article")
                .then().statusCode(422)
                .body("errors", hasItem("unknown field 'nope' for DocType Article"))
                .body("errors", hasItem("field 'status' must be one of [Draft, Published]"));
    }

    @Test
    void requestsWithoutTenantAreRejectedForUsersOfSeveralTenants() {
        given().when().get("/api/doc/Article").then().statusCode(400);
    }
}
