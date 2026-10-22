package io.astra.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SCIM 2.0 against a real Keycloak: tokens come from the tenant-scoped {@code scim-acme} and
 * {@code scim-globex} clients of the imported realm, users and memberships land in Keycloak, and the
 * provisioned user's own token shows the effect on the Astra API.
 */
@QuarkusTest
@TestProfile(KeycloakProfile.class)
class ScimTest {

    private static final String SCIM = "application/scim+json";
    private static final String SCIM_UTF8 = SCIM + "; charset=UTF-8";
    private static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String PATCH = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    private static final String PASSWORD = "Scim@12345";

    @BeforeAll
    static void scimJsonIsJson() {
        RestAssured.registerParser(SCIM, Parser.JSON);
    }

    @Test
    void discoveryEndpointsDescribeWhatIsSupported() {
        given().when().get("/scim/v2/ServiceProviderConfig").then().statusCode(200)
                .contentType(startsWith(SCIM))
                .body("patch.supported", equalTo(true))
                .body("filter.supported", equalTo(true))
                .body("bulk.supported", equalTo(false))
                .body("etag.supported", equalTo(false))
                .body("authenticationSchemes[0].type", equalTo("oauthbearertoken"));
        given().when().get("/scim/v2/ResourceTypes").then().statusCode(200)
                .body("totalResults", equalTo(2))
                .body("Resources.endpoint", containsInAnyOrder("/Users", "/Groups"));
        given().when().get("/scim/v2/Schemas/" + USER).then().statusCode(200)
                .body("attributes.name", hasItem("userName"));
        given().when().get("/scim/v2/Schemas/urn:nope").then().statusCode(404)
                .body("schemas", hasItem(ERROR)).body("status", equalTo("404"));
    }

    @Test
    void userLifecycleWithFilterRolesAndDeactivation() {
        String acme = clientToken("scim-acme");
        String userName = "scim-" + run();

        // create
        ExtractableResponse<Response> created = scim(acme).body(newUser(userName, "Chi", "Trần"))
                .when().post("/scim/v2/Users").then().statusCode(201)
                .contentType(startsWith(SCIM))
                .header("Location", containsString("/scim/v2/Users/"))
                .body("userName", equalTo(userName))
                .body("active", equalTo(true))
                .body("name.familyName", equalTo("Trần"))
                .body("emails[0].value", equalTo(userName + "@acme.test"))
                .body("groups", empty())
                .body("meta.resourceType", equalTo("User"))
                .body("$", not(hasKey("password")))
                .extract();
        String id = created.path("id");
        assertEquals(created.header("Location"), created.path("meta.location"));

        // uniqueness
        scim(acme).body(newUser(userName, "Chi", "Trần")).when().post("/scim/v2/Users").then().statusCode(409)
                .body("scimType", equalTo("uniqueness"));

        // filter (userName is case-insensitive) and get
        scim(acme).queryParam("filter", "userName eq \"" + userName.toUpperCase() + "\"").when().get("/scim/v2/Users")
                .then().statusCode(200)
                .body("schemas", hasItem("urn:ietf:params:scim:api:messages:2.0:ListResponse"))
                .body("totalResults", equalTo(1))
                .body("Resources[0].id", equalTo(id));
        scim(acme).queryParam("filter", "userName eq \"nobody-" + run() + "\"").when().get("/scim/v2/Users")
                .then().statusCode(200).body("totalResults", equalTo(0)).body("Resources", empty());
        scim(acme).when().get("/scim/v2/Users").then().statusCode(200).body("Resources.id", hasItem(id));
        scim(acme).when().get("/scim/v2/Users/" + id).then().statusCode(200).body("userName", equalTo(userName));

        // replace
        scim(acme).body(newUser(userName, "Chi", "Lý")).when().put("/scim/v2/Users/" + id).then().statusCode(200)
                .body("name.familyName", equalTo("Lý")).body("displayName", equalTo("Chi Lý"));

        // signed in without a tenant role: no tenant access
        String before = userToken(userName);
        given().auth().oauth2(before).when().get("/api/iam/me").then().statusCode(200).body("tenants", empty());
        given().auth().oauth2(before).header("X-Astra-Tenant", "acme").when().get("/api/doc/Article").then().statusCode(403);

        // group membership is the tenant role
        String editor = groupId(acme, "editor");
        scim(acme).body(patch("{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + id + "\"}]}"))
                .when().patch("/scim/v2/Groups/" + editor).then().statusCode(200)
                .body("displayName", equalTo("editor"))
                .body("members.value", hasItem(id));
        scim(acme).when().get("/scim/v2/Users/" + id).then().statusCode(200)
                .body("groups.display", hasItem("editor")).body("groups[0].value", equalTo(editor));

        Map<String, Object> tokens = passwordGrant(userName).then().statusCode(200).extract().as(Map.class);
        String after = (String) tokens.get("access_token");
        given().auth().oauth2(after).when().get("/api/iam/me").then().statusCode(200)
                .body("tenants.find { it.tenant == 'acme' }.roles", hasItem("editor"));
        given().auth().oauth2(after).header("X-Astra-Tenant", "acme").contentType("application/json")
                .body("{\"title\":\"Provisioned by SCIM\"}").when().post("/api/doc/Article")
                .then().statusCode(201).body("owner", notNullValue());

        // deactivate: no new sign-in, sessions (refresh tokens) end at once
        scim(acme).body(patch("{\"op\":\"Replace\",\"path\":\"active\",\"value\":\"False\"}"))
                .when().patch("/scim/v2/Users/" + id).then().statusCode(200).body("active", equalTo(false));
        passwordGrant(userName).then().statusCode(400).body("error", equalTo("invalid_grant"));
        given().formParam("grant_type", "refresh_token").formParam("client_id", "astra-dev-cli")
                .formParam("refresh_token", tokens.get("refresh_token"))
                .when().post(keycloak() + "/auth/realms/astra/protocol/openid-connect/token")
                .then().statusCode(400).body("error", equalTo("invalid_grant"));

        // remove from the role, then delete
        scim(acme).body(patch("{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + id + "\\\"]\"}"))
                .when().patch("/scim/v2/Groups/" + editor).then().statusCode(200)
                .body("members.value", not(hasItem(id)));
        scim(acme).when().delete("/scim/v2/Users/" + id).then().statusCode(204);
        scim(acme).when().get("/scim/v2/Users/" + id).then().statusCode(404).body("schemas", hasItem(ERROR));
    }

    @Test
    void groupsAreTheTenantRoles() {
        String acme = clientToken("scim-acme");
        scim(acme).when().get("/scim/v2/Groups").then().statusCode(200)
                .body("totalResults", equalTo(4))
                .body("Resources.displayName", containsInAnyOrder("admin", "editor", "author", "reader"));
        scim(acme).queryParam("filter", "displayName eq \"Reader\"").queryParam("excludedAttributes", "members")
                .when().get("/scim/v2/Groups").then().statusCode(200)
                .body("totalResults", equalTo(1))
                .body("Resources[0].displayName", equalTo("reader"))
                .body("Resources[0]", not(hasKey("members")));
        String reader = groupId(acme, "reader");
        scim(acme).body(patch("{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"boss\"}"))
                .when().patch("/scim/v2/Groups/" + reader).then().statusCode(400).body("scimType", equalTo("mutability"));
        scim(acme).body("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],\"displayName\":\"boss\"}")
                .when().post("/scim/v2/Groups").then().statusCode(501).body("schemas", hasItem(ERROR));
    }

    @Test
    void tenantComesFromTheTokenAndCrossTenantAccessFailsClosed() {
        String acme = clientToken("scim-acme");
        String globex = clientToken("scim-globex");
        String userName = "scim-x-" + run();
        String id = scim(acme).body(newUser(userName, "Xuân", "Mai")).when().post("/scim/v2/Users")
                .then().statusCode(201).extract().path("id");
        try {
            // globex's token cannot see or touch acme's user
            scim(globex).when().get("/scim/v2/Users/" + id).then().statusCode(404).body("schemas", hasItem(ERROR));
            scim(globex).queryParam("filter", "userName eq \"" + userName + "\"").when().get("/scim/v2/Users")
                    .then().statusCode(200).body("totalResults", equalTo(0));
            scim(globex).body(patch("{\"op\":\"replace\",\"path\":\"active\",\"value\":false}"))
                    .when().patch("/scim/v2/Users/" + id).then().statusCode(404);
            scim(globex).when().delete("/scim/v2/Users/" + id).then().statusCode(404);
            scim(globex).body(patch("{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + id + "\"}]}"))
                    .when().patch("/scim/v2/Groups/" + groupId(globex, "editor"))
                    .then().statusCode(400).body("scimType", equalTo("invalidValue"));
            // nor reach acme's groups, nor name another tenant
            scim(globex).when().get("/scim/v2/Groups/" + groupId(acme, "admin")).then().statusCode(404);
            scim(globex).header("X-Astra-Tenant", "acme").when().get("/scim/v2/Users").then().statusCode(403)
                    .body("schemas", hasItem(ERROR))
                    .body("detail", containsString("bound to tenant globex"));
            scim(acme).when().get("/scim/v2/Users/" + id).then().statusCode(200).body("active", equalTo(true));

            // a tenant user's own token is not a SCIM token; no token at all is 401
            given().auth().oauth2(userToken("lan", "Astra@123")).accept(SCIM).when().get("/scim/v2/Users")
                    .then().statusCode(403).body("schemas", hasItem(ERROR));
            given().accept(SCIM).when().get("/scim/v2/Users").then().statusCode(401)
                    .contentType(startsWith(SCIM))
                    .header("WWW-Authenticate", startsWith("Bearer"))
                    .body("status", equalTo("401"));
            // and a SCIM token gives no access to tenant documents
            given().auth().oauth2(acme).header("X-Astra-Tenant", "acme").when().get("/api/doc/Article").then().statusCode(403);
        } finally {
            scim(acme).when().delete("/scim/v2/Users/" + id);
        }
    }

    @Test
    void errorsUseTheScimErrorSchema() {
        String acme = clientToken("scim-acme");
        scim(acme).body("{not json").when().post("/scim/v2/Users").then().statusCode(400)
                .contentType(startsWith(SCIM))
                .body("schemas", equalTo(List.of(ERROR)))
                .body("status", equalTo("400"))
                .body("scimType", equalTo("invalidSyntax"))
                .body("detail", notNullValue());
        scim(acme).body("{\"userName\":\"no-schemas\"}").when().post("/scim/v2/Users").then().statusCode(400)
                .body("scimType", equalTo("invalidSyntax"));
        scim(acme).body("{\"schemas\":[\"" + USER + "\"]}").when().post("/scim/v2/Users").then().statusCode(400)
                .body("scimType", equalTo("invalidValue"));
        scim(acme).queryParam("filter", "userName co \"a\"").when().get("/scim/v2/Users").then().statusCode(400)
                .body("scimType", equalTo("invalidFilter"));
        scim(acme).when().get("/scim/v2/Users/" + UUID.randomUUID()).then().statusCode(404)
                .body("status", equalTo("404"));
        scim(acme).body("{\"schemas\":[\"" + PATCH + "\"],\"Operations\":[{\"op\":\"replace\",\"path\":\"active\"}]}")
                .when().patch("/scim/v2/Users/" + UUID.randomUUID()).then().statusCode(400)
                .body("scimType", equalTo("invalidSyntax"));
    }

    // ---- helpers ----

    private static RequestSpecification scim(String token) {
        return given().auth().oauth2(token).contentType(SCIM_UTF8).accept(SCIM);
    }

    private static String groupId(String token, String role) {
        return scim(token).queryParam("filter", "displayName eq \"" + role + "\"").when().get("/scim/v2/Groups")
                .then().statusCode(200).extract().path("Resources[0].id");
    }

    private static String newUser(String userName, String given, String family) {
        return "{\"schemas\":[\"" + USER + "\"],\"userName\":\"" + userName + "\","
                + "\"name\":{\"givenName\":\"" + given + "\",\"familyName\":\"" + family + "\"},"
                + "\"emails\":[{\"value\":\"" + userName + "@acme.test\",\"type\":\"work\",\"primary\":true}],"
                + "\"active\":true,\"password\":\"" + PASSWORD + "\",\"externalId\":\"ext-" + userName + "\"}";
    }

    private static String patch(String operation) {
        return "{\"schemas\":[\"" + PATCH + "\"],\"Operations\":[" + operation + "]}";
    }

    static String keycloak() {
        return ConfigProvider.getConfig().getValue(KeycloakResource.URL_PROPERTY, String.class);
    }

    static String clientToken(String clientId) {
        return given().formParam("grant_type", "client_credentials").formParam("client_id", clientId)
                .formParam("client_secret", clientId + "-local-secret")
                .when().post(keycloak() + "/auth/realms/astra/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
    }

    private static Response passwordGrant(String username) {
        return passwordGrant(username, PASSWORD);
    }

    private static Response passwordGrant(String username, String password) {
        return given().formParam("grant_type", "password").formParam("client_id", "astra-dev-cli")
                .formParam("scope", "openid").formParam("username", username).formParam("password", password)
                .when().post(keycloak() + "/auth/realms/astra/protocol/openid-connect/token");
    }

    private static String userToken(String username) {
        return userToken(username, PASSWORD);
    }

    private static String userToken(String username, String password) {
        return passwordGrant(username, password).then().statusCode(200).extract().path("access_token");
    }

    private static String run() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
