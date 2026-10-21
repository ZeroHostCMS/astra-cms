package io.astra.server;

import io.astra.doctype.store.DocumentStore;
import io.astra.spi.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** Authentication, tenant membership and DocType permission rules on the generated API. */
@QuarkusTest
class IamAccessTest {

    @Inject DocumentStore store;

    @Test
    void anonymousCallsToTheApiAreRejected() {
        given().header("X-Astra-Tenant", "acme").when().get("/api/doc/Article").then().statusCode(401);
        given().when().get("/api/iam/me").then().statusCode(401);
    }

    @Test
    void clientBootstrapConfigIsPublic() {
        given().when().get("/api/iam/config").then().statusCode(200)
                .body("clientId", equalTo("astra-mobile"))
                .body("redirectUri", equalTo("io.astra.mobile:/oauthredirect"))
                .body("scopes", hasItem("offline_access"))
                .body("discoveryUrl", containsString("/.well-known/openid-configuration"));
    }

    @Test
    @TestSecurity(user = "u-reader", roles = "/tenants/acme/reader")
    void nonMembersAreForbidden() {
        given().header("X-Astra-Tenant", "globex").when().get("/api/doc/Article")
                .then().statusCode(403).body("error", equalTo("not a member of tenant globex"));
    }

    @Test
    @TestSecurity(user = "u-reader", roles = "/tenants/acme/reader")
    void aSingleTenantUserNeedsNoTenantHeader() {
        given().when().get("/api/doc/Article").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "u-reader", roles = "/tenants/acme/reader")
    void readersCanReadButNotCreate() {
        given().header("X-Astra-Tenant", "acme").when().get("/api/doc/Article").then().statusCode(200);
        given().header("X-Astra-Tenant", "acme").contentType("application/json").body("{\"title\":\"nope\"}")
                .when().post("/api/doc/Article")
                .then().statusCode(403).body("error", equalTo("not permitted: create Article"));
    }

    @Test
    @TestSecurity(user = "u-author", roles = "/tenants/acme/author")
    void authorsWriteOnlyTheirOwnDocuments() {
        String own = given().header("X-Astra-Tenant", "acme").contentType("application/json")
                .body("{\"title\":\"Mine\"}").when().post("/api/doc/Article")
                .then().statusCode(201).body("owner", equalTo("u-author")).extract().path("name");
        given().header("X-Astra-Tenant", "acme").contentType("application/json").body("{\"body\":\"edited\"}")
                .when().put("/api/doc/Article/" + own).then().statusCode(200).body("data.body", equalTo("edited"));

        String others = TenantContext.call("acme",
                () -> store.insert("Article", new HashMap<>(Map.of("title", "Theirs")), "u-someone-else")).name();
        given().header("X-Astra-Tenant", "acme").contentType("application/json").body("{\"body\":\"hijack\"}")
                .when().put("/api/doc/Article/" + others).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "u-lan", roles = {"/tenants/acme/admin", "/tenants/globex/reader"})
    @OidcSecurity(claims = {@Claim(key = "preferred_username", value = "lan"), @Claim(key = "email", value = "lan@acme.test")})
    void meListsMembershipsAndEffectivePermissions() {
        given().when().get("/api/iam/me").then().statusCode(200)
                .body("id", notNullValue())
                .body("username", equalTo("lan"))
                .body("platformAdmin", equalTo(false))
                .body("tenants.find { it.tenant == 'acme' }.roles", hasItem("admin"))
                .body("tenants.find { it.tenant == 'acme' }.permissions.Article.write", equalTo("all"))
                .body("tenants.find { it.tenant == 'globex' }.permissions.Article.create", equalTo(false));
    }

    @Test
    @TestSecurity(user = "u-reader", roles = "/tenants/acme/reader")
    void onlyTenantAdminsListMembers() {
        given().when().get("/api/iam/tenants/acme/members").then().statusCode(403);
    }
}
