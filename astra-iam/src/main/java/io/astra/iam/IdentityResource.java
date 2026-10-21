package io.astra.iam;

import io.astra.doctype.permission.DocPermissions;
import io.astra.kernel.security.Memberships;
import io.astra.kernel.tenant.TenantResolverFilter;
import io.astra.kernel.tenant.WithoutTenant;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Identity endpoints for clients: how to sign in, and who the signed-in user is. Not tenant-scoped. */
@Path("/api/iam")
@Produces(MediaType.APPLICATION_JSON)
@WithoutTenant
public class IdentityResource {

    @Inject SecurityIdentity identity;
    @Inject JsonWebToken jwt;
    @Inject DocPermissions permissions;

    @ConfigProperty(name = "astra.iam.issuer")
    String issuer;

    @ConfigProperty(name = "astra.iam.mobile.client-id", defaultValue = "astra-mobile")
    String mobileClientId;

    @ConfigProperty(name = "astra.iam.mobile.redirect-uri", defaultValue = "io.astra.mobile:/oauthredirect")
    String mobileRedirectUri;

    @ConfigProperty(name = "astra.iam.locales", defaultValue = "vi,en")
    List<String> locales;

    /** Public client bootstrap: everything an OIDC client library needs besides its own code. */
    @GET
    @Path("/config")
    @PermitAll
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", issuer);
        m.put("discoveryUrl", issuer + "/.well-known/openid-configuration");
        m.put("clientId", mobileClientId);
        m.put("redirectUri", mobileRedirectUri);
        m.put("scopes", List.of("openid", "profile", "email", "offline_access"));
        m.put("tenantHeader", TenantResolverFilter.TENANT_HEADER);
        m.put("locales", locales);
        return m;
    }

    /** The caller's profile, tenant memberships and effective DocType permissions per tenant. */
    @GET
    @Path("/me")
    @Authenticated
    public Map<String, Object> me() {
        Memberships memberships = Memberships.of(identity);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", identity.getPrincipal().getName());
        m.put("username", jwt.getClaim("preferred_username"));
        m.put("email", jwt.getClaim("email"));
        m.put("name", jwt.getClaim("name"));
        m.put("locale", jwt.getClaim("locale"));
        m.put("platformAdmin", memberships.platformAdmin());
        m.put("tenants", memberships.byTenant().entrySet().stream().map(e -> tenantView(e.getKey(), e.getValue())).toList());
        return m;
    }

    private Map<String, Object> tenantView(String tenant, Set<String> roles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", tenant);
        m.put("roles", roles);
        m.put("permissions", permissions.effective(roles));
        return m;
    }
}
