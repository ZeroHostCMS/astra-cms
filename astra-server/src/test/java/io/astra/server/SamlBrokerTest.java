package io.astra.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SAML 2.0 brokering on a real Keycloak: an Acme Corp employee signs in at the {@code acme-corp-idp} realm
 * (the enterprise IdP), Keycloak validates the signed assertion, maps the {@code memberOf} attribute to the
 * tenant group {@code /tenants/acme/<role>}, and Astra sees that tenant role in the resulting OIDC token.
 */
@QuarkusTest
@TestProfile(KeycloakProfile.class)
class SamlBrokerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void signedAssertionFromTheEnterpriseIdpBecomesATenantRole() throws Exception {
        SamlBrowser browser = new SamlBrowser(ScimTest.keycloak());
        SamlBrowser.Outcome outcome = browser.login("linh", "Astra@123", UnaryOperator.identity());
        assertNotNull(outcome.code(), () -> "no authorization code; Keycloak answered " + outcome.status() + ": " + outcome.body());

        String xml = outcome.samlResponseXml();
        String assertion = xml.substring(xml.indexOf("<saml:Assertion"), xml.indexOf("</saml:Assertion>"));
        assertTrue(assertion.contains("SignatureValue"), "the assertion itself is signed");
        assertTrue(assertion.contains("astra-editors"), "the IdP sends linh's group as memberOf");

        String token = browser.accessToken(outcome.code());
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertEquals("acme-corp.linh", claims.path("preferred_username").asText());
        assertEquals("linh@acme-corp.test", claims.path("email").asText());

        given().auth().oauth2(token).when().get("/api/iam/me").then().statusCode(200)
                .body("username", equalTo("acme-corp.linh"))
                .body("tenants.tenant", contains("acme"))
                .body("tenants[0].roles", contains("editor"));
        given().auth().oauth2(token).header("X-Astra-Tenant", "acme").contentType("application/json")
                .body("{\"title\":\"Signed in through Acme Corp SSO\"}").when().post("/api/doc/Article")
                .then().statusCode(201);
    }

    @Test
    void anAlteredAssertionIsRejected() throws Exception {
        SamlBrowser browser = new SamlBrowser(ScimTest.keycloak());
        SamlBrowser.Outcome outcome = browser.login("bao", "Astra@123",
                xml -> xml.replace("astra-readers", "astra-admins"));   // try to promote a reader to admin
        assertTrue(outcome.samlResponseXml().contains("astra-readers"));
        assertNull(outcome.code(), "Keycloak must not issue a code for a response whose signature no longer verifies");
        assertTrue(outcome.status() >= 400, () -> "expected an error page, got " + outcome.status());
    }

    /** The realm files the stacks import: SAML trust and mapping settings that the flow above relies on. */
    @Test
    void realmFilesDeclareSignedSamlAndTenantScopedScimClients() throws Exception {
        Path realms = KeycloakResource.repoRoot().resolve("deploy/keycloak/realm");
        JsonNode astra = JSON.readTree(realms.resolve("astra-realm.json").toFile());
        JsonNode idpRealm = JSON.readTree(realms.resolve("acme-corp-idp-realm.json").toFile());

        JsonNode idp = find(astra.path("identityProviders"), "alias", "acme-corp");
        assertEquals("saml", idp.path("providerId").asText());
        JsonNode cfg = idp.path("config");
        assertEquals("true", cfg.path("validateSignature").asText());
        assertEquals("true", cfg.path("wantAssertionsSigned").asText());
        assertEquals("urn:astra:sp", cfg.path("entityId").asText());

        Set<String> groupPaths = new HashSet<>();
        collectGroups(astra.path("groups"), "", groupPaths);
        int roleMappers = 0;
        for (JsonNode m : astra.path("identityProviderMappers")) {
            if (!m.path("identityProviderMapper").asText().equals("saml-advanced-group-idp-mapper")) continue;
            String group = m.path("config").path("group").asText();
            assertTrue(groupPaths.contains(group), "mapper target " + group + " exists in the realm");
            assertTrue(group.startsWith("/tenants/acme/"), "Acme Corp SSO maps only into tenant acme: " + group);
            roleMappers++;
        }
        assertEquals(4, roleMappers);

        JsonNode sp = find(idpRealm.path("clients"), "clientId", "urn:astra:sp");
        assertEquals("saml", sp.path("protocol").asText());
        assertEquals("true", sp.path("attributes").path("saml.assertion.signature").asText());
        assertEquals("true", sp.path("attributes").path("saml.server.signature").asText());

        for (String tenant : new String[] {"acme", "globex"}) {
            JsonNode client = find(astra.path("clients"), "clientId", "scim-" + tenant);
            JsonNode claim = find(client.path("protocolMappers"), "protocolMapper", "oidc-hardcoded-claim-mapper").path("config");
            assertEquals("scim_tenant", claim.path("claim.name").asText());
            assertEquals(tenant, claim.path("claim.value").asText());
            assertEquals(false, client.path("directAccessGrantsEnabled").asBoolean(true));
            assertEquals(false, client.path("standardFlowEnabled").asBoolean(true));
            JsonNode account = find(astra.path("users"), "serviceAccountClientId", "scim-" + tenant);
            assertTrue(account.path("realmRoles").toString().contains("\"scim\""));
        }
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) if (value.equals(n.path(field).asText())) return n;
        throw new AssertionError("no element with " + field + "=" + value);
    }

    private static void collectGroups(JsonNode groups, String parent, Set<String> out) {
        for (JsonNode g : groups) {
            String path = parent + "/" + g.path("name").asText();
            out.add(path);
            collectGroups(g.path("subGroups"), path, out);
        }
    }
}
