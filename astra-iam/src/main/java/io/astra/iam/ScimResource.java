package io.astra.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.astra.kernel.tenant.TenantResolverFilter;
import io.astra.kernel.tenant.WithoutTenant;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SCIM 2.0 (RFC 7643/7644) service provider for one tenant per token: Users and Groups backed by Keycloak
 * through {@link ScimDirectory}, plus the discovery endpoints.
 *
 * <p>Callers authenticate with a client-credentials access token of the tenant's {@code scim-<tenant>}
 * Keycloak client. The token must carry the realm role {@code scim} and the {@code scim_tenant} claim,
 * which alone selects the tenant; a request body or path never does. A conflicting
 * {@code X-Astra-Tenant} header is refused, and resources of other tenants answer 404. Every response,
 * errors included, is {@code application/scim+json}.
 */
@Path("/scim/v2")
@Produces({ScimResource.SCIM_JSON, "application/json"})
@WithoutTenant
@RunOnVirtualThread
public class ScimResource {

    public static final String SCIM_JSON = "application/scim+json";
    public static final String SCIM_ROLE = "scim";
    public static final String TENANT_CLAIM = "scim_tenant";
    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    static final String LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    static final String SP_CONFIG = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";
    static final String RESOURCE_TYPE = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";
    static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema";
    static final int MAX_RESULTS = 200;
    private static final String TENANT_ID = "[a-z0-9][a-z0-9-]{0,62}";
    private static final Logger LOG = Logger.getLogger(ScimResource.class);

    @Inject ScimDirectory scim;
    @Inject SecurityIdentity identity;
    @Inject JsonWebToken jwt;
    @Inject ObjectMapper mapper;
    @Context UriInfo uriInfo;
    @Context HttpHeaders headers;

    // ---- discovery (no tenant data, readable without a token) ----

    @GET
    @Path("/ServiceProviderConfig")
    public Response serviceProviderConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(SP_CONFIG));
        m.put("patch", Map.of("supported", true));
        m.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        m.put("filter", Map.of("supported", true, "maxResults", MAX_RESULTS));
        m.put("changePassword", Map.of("supported", true));
        m.put("sort", Map.of("supported", false));
        m.put("etag", Map.of("supported", false));
        m.put("authenticationSchemes", List.of(Map.of(
                "type", "oauthbearertoken",
                "name", "OAuth Bearer Token",
                "description", "Client-credentials access token of the tenant's scim-<tenant> Keycloak client",
                "primary", true)));
        m.put("meta", meta("ServiceProviderConfig", location("ServiceProviderConfig")));
        return ok(m);
    }

    @GET
    @Path("/ResourceTypes")
    public Response resourceTypes() {
        return ok(list(resourceTypeList(), resourceTypeList().size(), 1));
    }

    @GET
    @Path("/ResourceTypes/{id}")
    public Response resourceType(@PathParam("id") String id) {
        return ok(resourceTypeList().stream().filter(r -> r.get("id").equals(id)).findFirst()
                .orElseThrow(() -> ScimException.notFound("ResourceType " + id + " not found")));
    }

    @GET
    @Path("/Schemas")
    public Response schemas() {
        List<Map<String, Object>> all = schemaList();
        return ok(list(all, all.size(), 1));
    }

    @GET
    @Path("/Schemas/{id}")
    public Response schema(@PathParam("id") String id) {
        return ok(schemaList().stream().filter(s -> s.get("id").equals(id)).findFirst()
                .orElseThrow(() -> ScimException.notFound("Schema " + id + " not found")));
    }

    // ---- Users ----

    @GET
    @Path("/Users")
    public Response listUsers(@QueryParam("filter") String filter, @QueryParam("startIndex") String startIndex,
                              @QueryParam("count") String count) {
        String tenant = tenant();
        Optional<ScimFilter> f = ScimFilter.parse(filter, USER_SCHEMA, "userName");
        int start = startIndex(startIndex);
        ScimDirectory.Page<ScimDirectory.User> page = scim.users(tenant, f, start, count(count));
        return ok(list(page.items().stream().map(this::userJson).toList(), page.total(), start));
    }

    @GET
    @Path("/Users/{id}")
    public Response getUser(@PathParam("id") String id) {
        return ok(userJson(scim.user(tenant(), id)));
    }

    @POST
    @Path("/Users")
    public Response createUser(String body) {
        String tenant = tenant();
        ScimDirectory.User u = scim.create(tenant, userInput(resource(body, USER_SCHEMA)));
        URI location = location("Users/" + u.id());
        return Response.status(201).type(SCIM_JSON).location(location).entity(json(userJson(u))).build();
    }

    @PUT
    @Path("/Users/{id}")
    public Response replaceUser(@PathParam("id") String id, String body) {
        String tenant = tenant();
        return ok(userJson(scim.replace(tenant, id, userInput(resource(body, USER_SCHEMA)))));
    }

    @PATCH
    @Path("/Users/{id}")
    public Response patchUser(@PathParam("id") String id, String body) {
        String tenant = tenant();
        List<ScimPatch.Op> ops = ScimPatch.parse(parse(body));
        ScimDirectory.UserInput merged = ScimPatch.applyToUser(scim.user(tenant, id).toInput(), ops);
        return ok(userJson(scim.replace(tenant, id, merged)));
    }

    @DELETE
    @Path("/Users/{id}")
    public Response deleteUser(@PathParam("id") String id) {
        scim.delete(tenant(), id);
        return Response.noContent().build();
    }

    // ---- Groups: the tenant's roles ----

    @GET
    @Path("/Groups")
    public Response listGroups(@QueryParam("filter") String filter, @QueryParam("startIndex") String startIndex,
                               @QueryParam("count") String count, @QueryParam("excludedAttributes") String excluded) {
        String tenant = tenant();
        Optional<ScimFilter> f = ScimFilter.parse(filter, GROUP_SCHEMA, "displayName");
        int start = startIndex(startIndex);
        List<ScimDirectory.Group> all = scim.groups(tenant, !excludesMembers(excluded)).stream()
                .filter(g -> f.isEmpty() || f.get().matches(g.role())).toList();
        List<Map<String, Object>> page = all.stream().skip(start - 1L).limit(count(count)).map(this::groupJson).toList();
        return ok(list(page, all.size(), start));
    }

    @GET
    @Path("/Groups/{id}")
    public Response getGroup(@PathParam("id") String id, @QueryParam("excludedAttributes") String excluded) {
        return ok(groupJson(scim.group(tenant(), id, !excludesMembers(excluded))));
    }

    @PATCH
    @Path("/Groups/{id}")
    public Response patchGroup(@PathParam("id") String id, String body) {
        String tenant = tenant();
        List<ScimPatch.Op> ops = ScimPatch.parse(parse(body));
        Set<String> current = memberIds(scim.group(tenant, id, true));
        return ok(groupJson(scim.setMembers(tenant, id, ScimPatch.applyToMembers(current, ops))));
    }

    @PUT
    @Path("/Groups/{id}")
    public Response replaceGroup(@PathParam("id") String id, String body) {
        String tenant = tenant();
        JsonNode b = resource(body, GROUP_SCHEMA);
        ScimDirectory.Group g = scim.group(tenant, id, false);
        String name = ScimPatch.text(b.get("displayName"));
        if (name != null && !name.equalsIgnoreCase(g.role())) {
            throw ScimException.mutability("group names are the tenant's roles and cannot be changed");
        }
        JsonNode members = b.get("members");
        Set<String> desired = new LinkedHashSet<>();
        if (members != null && !members.isNull()) {
            if (!members.isArray()) throw ScimException.invalidValue("members must be an array");
            for (JsonNode m : members) {
                String v = ScimPatch.text(m.get("value"));
                if (v == null || v.isBlank()) throw ScimException.invalidValue("every member needs a value (the user id)");
                desired.add(v);
            }
        }
        return ok(groupJson(scim.setMembers(tenant, id, desired)));
    }

    @POST
    @Path("/Groups")
    public Response createGroup() {
        tenant();
        throw ScimException.notImplemented("groups are the tenant's roles; roles are defined by Astra, not by SCIM");
    }

    @DELETE
    @Path("/Groups/{id}")
    public Response deleteGroup(@PathParam("id") String id) {
        tenant();
        throw ScimException.notImplemented("groups are the tenant's roles; roles are defined by Astra, not by SCIM");
    }

    // ---- errors (RFC 7644 section 3.12), scoped to this resource ----

    @ServerExceptionMapper
    public Response mapScim(ScimException e) {
        return error(e.status(), e.scimType(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response mapIam(IamException e) {
        return error(e.status(), null, e.getMessage());
    }

    @ServerExceptionMapper
    public Response mapUpstream(WebApplicationException e) {
        LOG.warnf("SCIM: Keycloak admin call failed with HTTP %d", e.getResponse().getStatus());
        return error(500, null, "identity provider call failed (HTTP " + e.getResponse().getStatus() + ")");
    }

    @ServerExceptionMapper
    public Response mapUnexpected(RuntimeException e) {
        LOG.error("SCIM request failed", e);
        return error(500, null, "internal error");
    }

    // ---- helpers ----

    /** The tenant this request acts for, taken only from the verified token. */
    private String tenant() {
        if (identity.isAnonymous()) {
            throw ScimException.unauthorized("a bearer token of the tenant's SCIM client is required");
        }
        if (!identity.hasRole(SCIM_ROLE)) throw ScimException.forbidden("the token lacks the " + SCIM_ROLE + " role");
        Object claim = jwt.getClaim(TENANT_CLAIM);
        String tenant = claim instanceof JsonString s ? s.getString() : claim == null ? null : claim.toString();
        if (tenant == null || !tenant.matches(TENANT_ID)) {
            throw ScimException.forbidden("the token is not bound to a tenant (claim " + TENANT_CLAIM + ")");
        }
        String requested = headers.getHeaderString(TenantResolverFilter.TENANT_HEADER);
        if (requested != null && !requested.isBlank() && !requested.trim().equals(tenant)) {
            throw ScimException.forbidden("this SCIM token is bound to tenant " + tenant + " and cannot act on " + requested.trim());
        }
        if (!scim.tenantExists(tenant)) throw ScimException.forbidden("tenant " + tenant + " does not exist");
        return tenant;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) throw ScimException.invalidSyntax("a JSON body is required");
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw ScimException.invalidSyntax("body is not valid JSON");
        }
    }

    private JsonNode resource(String body, String schema) {
        JsonNode b = parse(body);
        if (!b.isObject()) throw ScimException.invalidSyntax("a JSON object is required");
        if (!ScimPatch.hasSchema(b, schema)) throw ScimException.invalidSyntax("schemas must contain " + schema);
        return b;
    }

    private static ScimDirectory.UserInput userInput(JsonNode b) {
        JsonNode name = b.get("name");
        if (name != null && !name.isNull() && !name.isObject()) throw ScimException.invalidValue("name must be an object");
        JsonNode n = name == null || name.isNull() ? null : name;
        return new ScimDirectory.UserInput(
                ScimPatch.text(b.get("userName")),
                n == null ? null : ScimPatch.text(n.get("givenName")),
                n == null ? null : ScimPatch.text(n.get("familyName")),
                ScimPatch.primaryEmail(b.get("emails")),
                ScimPatch.bool(b.get("active"), "active"),
                ScimPatch.text(b.get("password")));
    }

    private Map<String, Object> userJson(ScimDirectory.User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(USER_SCHEMA));
        m.put("id", u.id());
        m.put("userName", u.userName());
        String formatted = Stream.of(u.givenName(), u.familyName()).filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
        Map<String, Object> name = new LinkedHashMap<>();
        if (!formatted.isEmpty()) name.put("formatted", formatted);
        if (u.givenName() != null) name.put("givenName", u.givenName());
        if (u.familyName() != null) name.put("familyName", u.familyName());
        if (!name.isEmpty()) m.put("name", name);
        if (!formatted.isEmpty()) m.put("displayName", formatted);
        if (u.email() != null) m.put("emails", List.of(Map.of("value", u.email(), "type", "work", "primary", true)));
        m.put("active", u.active());
        m.put("groups", u.roles().stream().map(r -> Map.of(
                "value", r.id(), "display", r.role(), "$ref", location("Groups/" + r.id()).toString(), "type", "direct")).toList());
        Map<String, Object> meta = meta("User", location("Users/" + u.id()));
        if (u.created() != null) meta.put("created", Instant.ofEpochMilli(u.created()).toString());
        m.put("meta", meta);
        return m;
    }

    private Map<String, Object> groupJson(ScimDirectory.Group g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(GROUP_SCHEMA));
        m.put("id", g.id());
        m.put("displayName", g.role());
        if (g.members() != null) {
            m.put("members", g.members().stream().map(u -> Map.of(
                    "value", u.id(), "display", u.userName(), "$ref", location("Users/" + u.id()).toString(), "type", "User")).toList());
        }
        m.put("meta", meta("Group", location("Groups/" + g.id())));
        return m;
    }

    private static Set<String> memberIds(ScimDirectory.Group g) {
        return g.members().stream().map(ScimDirectory.MemberRef::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Map<String, Object>> resourceTypeList() {
        return List.of(resourceType("User", "/Users", USER_SCHEMA, "User account"),
                resourceType("Group", "/Groups", GROUP_SCHEMA, "Tenant role"));
    }

    private Map<String, Object> resourceType(String id, String endpoint, String schema, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(RESOURCE_TYPE));
        m.put("id", id);
        m.put("name", id);
        m.put("endpoint", endpoint);
        m.put("description", description);
        m.put("schema", schema);
        m.put("meta", meta("ResourceType", location("ResourceTypes/" + id)));
        return m;
    }

    private List<Map<String, Object>> schemaList() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("scim/schemas.json")) {
            if (in == null) throw new IllegalStateException("scim/schemas.json is missing from the classpath");
            List<Map<String, Object>> schemas = mapper.readValue(in, new TypeReference<>() { });
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> s : schemas) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("schemas", List.of(SCHEMA));
                m.putAll(s);
                m.put("meta", meta("Schema", location("Schemas/" + s.get("id"))));
                out.add(m);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> list(List<? extends Map<String, Object>> resources, int total, int start) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(LIST_RESPONSE));
        m.put("totalResults", total);
        m.put("startIndex", start);
        m.put("itemsPerPage", resources.size());
        m.put("Resources", resources);
        return m;
    }

    private static Map<String, Object> meta(String resourceType, URI location) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resourceType", resourceType);
        m.put("location", location.toString());
        return m;
    }

    private URI location(String relative) {
        return uriInfo.getBaseUriBuilder().path("scim/v2").path(relative).build();
    }

    /** RFC 7644 section 3.4.2.4: 1-based; values below 1 mean 1. */
    private static int startIndex(String raw) {
        return Math.max(1, intParam(raw, "startIndex", 1));
    }

    /** RFC 7644 section 3.4.2.4: negative means 0; capped at the advertised maxResults. */
    private static int count(String raw) {
        return Math.min(MAX_RESULTS, Math.max(0, intParam(raw, "count", 100)));
    }

    private static int intParam(String raw, String name, int dflt) {
        if (raw == null || raw.isBlank()) return dflt;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ScimException.invalidValue(name + " must be an integer");
        }
    }

    private static boolean excludesMembers(String excluded) {
        if (excluded == null) return false;
        for (String a : excluded.split(",")) {
            String s = a.trim().toLowerCase(Locale.ROOT);
            if (s.equals("members") || s.equals(GROUP_SCHEMA.toLowerCase(Locale.ROOT) + ":members")) return true;
        }
        return false;
    }

    private Response ok(Object body) {
        return Response.ok(json(body)).type(SCIM_JSON).build();
    }

    private Response error(int status, String scimType, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(ERROR));
        m.put("status", Integer.toString(status));
        if (scimType != null) m.put("scimType", scimType);
        m.put("detail", detail);
        Response.ResponseBuilder r = Response.status(status).type(SCIM_JSON).entity(json(m));
        if (status == 401) r.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"scim\"");
        return r.build();
    }

    private String json(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise SCIM response", e);
        }
    }
}
