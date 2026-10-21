package io.astra.iam;

import io.astra.kernel.security.Memberships;
import io.astra.kernel.tenant.WithoutTenant;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant and membership management backed by {@link KeycloakDirectory}. Platform admins manage
 * tenants; a tenant's admins manage its members; any signed-in user may join a tenant that allows
 * public sign-up. Membership changes reach the caller's token at its next refresh.
 */
@Path("/api/iam/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@WithoutTenant
@Authenticated
@RunOnVirtualThread
public class TenantResource {

    private static final String TENANT_ID = "[a-z0-9][a-z0-9-]{0,62}";

    public record CreateTenant(String id, String name, Boolean publicSignup) { }
    public record UpdateTenant(String name, Boolean publicSignup) { }
    public record AddMember(String username, String email, String firstName, String lastName,
                            String role, String temporaryPassword) { }
    public record ChangeRole(String role) { }

    @Inject KeycloakDirectory directory;
    @Inject SecurityIdentity identity;

    /** Tenants the caller belongs to; every tenant for a platform admin. */
    @GET
    public List<KeycloakDirectory.Tenant> list() {
        Memberships m = Memberships.of(identity);
        return directory.tenants().stream().filter(t -> m.platformAdmin() || m.isMember(t.id())).toList();
    }

    /** Tenants any signed-in user may join, for a tenant picker after self-registration. */
    @GET
    @Path("/public")
    public List<KeycloakDirectory.Tenant> joinable() {
        return directory.tenants().stream().filter(KeycloakDirectory.Tenant::publicSignup).toList();
    }

    @POST
    @RolesAllowed(Memberships.PLATFORM_ADMIN)
    public Response create(CreateTenant body) {
        if (body == null || body.id() == null || !body.id().matches(TENANT_ID)) {
            throw IamException.badRequest("id must match " + TENANT_ID);
        }
        KeycloakDirectory.Tenant t = directory.createTenant(body.id(), body.name(), Boolean.TRUE.equals(body.publicSignup()));
        return Response.status(201).entity(t).build();
    }

    @PUT
    @Path("/{tenant}")
    @RolesAllowed(Memberships.PLATFORM_ADMIN)
    public KeycloakDirectory.Tenant update(@PathParam("tenant") String tenant, UpdateTenant body) {
        if (body == null) throw IamException.badRequest("body required");
        return directory.updateTenant(tenant, body.name(), body.publicSignup());
    }

    /** Self-service: joins a public tenant with the self-join role. Idempotent for existing members. */
    @POST
    @Path("/{tenant}/join")
    public Map<String, Object> join(@PathParam("tenant") String tenant) {
        KeycloakDirectory.Tenant t = directory.tenant(tenant).orElseThrow(() -> IamException.notFound("tenant " + tenant));
        String userId = identity.getPrincipal().getName();
        String role = directory.roleOf(tenant, userId).orElse(null);
        if (role == null) {
            if (!t.publicSignup()) throw IamException.forbidden("tenant " + tenant + " does not allow public sign-up");
            role = directory.selfJoinRole();
            directory.assignRole(tenant, userId, role);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", tenant);
        m.put("role", role);
        m.put("refreshToken", !Memberships.of(identity).isMember(tenant)); // the current token predates the join
        return m;
    }

    @GET
    @Path("/{tenant}/members")
    public List<KeycloakDirectory.Member> members(@PathParam("tenant") String tenant) {
        requireTenantAdmin(tenant);
        return directory.members(tenant);
    }

    /**
     * Adds a user to the tenant with a role. An existing username is reused as is (its password is
     * never touched); a new user is created with a temporary password they must change at first login.
     */
    @POST
    @Path("/{tenant}/members")
    public Response addMember(@PathParam("tenant") String tenant, AddMember body) {
        requireTenantAdmin(tenant);
        if (body == null || body.username() == null || body.username().isBlank() || body.role() == null) {
            throw IamException.badRequest("username and role are required");
        }
        String userId = directory.findUserId(body.username()).orElse(null);
        boolean created = userId == null;
        if (created) {
            if (body.temporaryPassword() == null || body.temporaryPassword().isBlank()) {
                throw IamException.badRequest("temporaryPassword is required to create a new user");
            }
            userId = directory.createUser(new KeycloakDirectory.NewUser(body.username(), body.email(),
                    body.firstName(), body.lastName(), body.temporaryPassword()));
        }
        directory.assignRole(tenant, userId, body.role());
        return Response.status(created ? 201 : 200).entity(member(tenant, userId)).build();
    }

    @PUT
    @Path("/{tenant}/members/{userId}")
    public KeycloakDirectory.Member changeRole(@PathParam("tenant") String tenant, @PathParam("userId") String userId,
                                               ChangeRole body) {
        requireTenantAdmin(tenant);
        if (body == null || body.role() == null) throw IamException.badRequest("role is required");
        if (directory.roleOf(tenant, userId).isEmpty()) {
            throw IamException.notFound("user " + userId + " is not a member of " + tenant);
        }
        directory.assignRole(tenant, userId, body.role());
        return member(tenant, userId);
    }

    @DELETE
    @Path("/{tenant}/members/{userId}")
    public Response removeMember(@PathParam("tenant") String tenant, @PathParam("userId") String userId) {
        requireTenantAdmin(tenant);
        directory.removeMember(tenant, userId);
        return Response.noContent().build();
    }

    private KeycloakDirectory.Member member(String tenant, String userId) {
        return directory.members(tenant).stream().filter(x -> x.userId().equals(userId)).findFirst()
                .orElseThrow(() -> IamException.notFound("user " + userId));
    }

    private void requireTenantAdmin(String tenant) {
        Memberships m = Memberships.of(identity);
        if (!m.platformAdmin() && !m.roles(tenant).contains(KeycloakDirectory.ADMIN_ROLE)) {
            throw IamException.forbidden("tenant admin role required for " + tenant);
        }
    }
}
