package io.astra.iam;

import io.astra.kernel.security.Memberships;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tenants and their members, stored in Keycloak as groups {@code /tenants/<tenant>/<role>}. Group
 * attributes hold tenant settings. Calls the Keycloak Admin API as the {@code astra-server} service
 * account, which holds only user and group management roles in the Astra realm.
 */
@ApplicationScoped
public class KeycloakDirectory {

    static final String TENANTS_ROOT = "/tenants";
    static final String ATTR_NAME = "display-name";
    static final String ATTR_PUBLIC_SIGNUP = "public-signup";
    static final String ADMIN_ROLE = "admin";

    public record Tenant(String id, String name, boolean publicSignup) { }

    public record Member(String userId, String username, String email, String firstName, String lastName,
                         boolean enabled, String role) { }

    public record NewUser(String username, String email, String firstName, String lastName, String temporaryPassword) { }

    @Inject Keycloak keycloak;

    @ConfigProperty(name = "astra.iam.realm", defaultValue = "astra")
    String realmName;

    @ConfigProperty(name = "astra.iam.tenant-roles", defaultValue = "admin,editor,author,reader")
    List<String> tenantRoles;

    @ConfigProperty(name = "astra.iam.self-join-role", defaultValue = "reader")
    String selfJoinRole;

    public List<String> tenantRoles() { return tenantRoles; }

    public String selfJoinRole() { return selfJoinRole; }

    public List<Tenant> tenants() {
        GroupRepresentation root = group(TENANTS_ROOT)
                .orElseThrow(() -> new IllegalStateException("group " + TENANTS_ROOT + " is missing from realm " + realmName));
        return realm().groups().group(root.getId()).getSubGroups(0, 10_000, false).stream()
                .map(KeycloakDirectory::toTenant)
                .sorted(Comparator.comparing(Tenant::id))
                .toList();
    }

    public Optional<Tenant> tenant(String id) {
        return group(TENANTS_ROOT + "/" + id).map(KeycloakDirectory::toTenant);
    }

    /** Creates {@code /tenants/<id>} and one subgroup per tenant role. */
    public Tenant createTenant(String id, String name, boolean publicSignup) {
        if (tenant(id).isPresent()) throw IamException.conflict("tenant " + id + " already exists");
        GroupRepresentation root = group(TENANTS_ROOT).orElseThrow();
        GroupRepresentation rep = new GroupRepresentation();
        rep.setName(id);
        rep.setAttributes(attributes(name == null || name.isBlank() ? id : name, publicSignup));
        String tenantGroupId = createdId(realm().groups().group(root.getId()).subGroup(rep));
        for (String role : tenantRoles) {
            GroupRepresentation roleGroup = new GroupRepresentation();
            roleGroup.setName(role);
            createdId(realm().groups().group(tenantGroupId).subGroup(roleGroup));
        }
        return tenant(id).orElseThrow();
    }

    public Tenant updateTenant(String id, String name, Boolean publicSignup) {
        GroupRepresentation g = group(TENANTS_ROOT + "/" + id).orElseThrow(() -> IamException.notFound("tenant " + id));
        Tenant current = toTenant(g);
        g.setAttributes(attributes(name == null || name.isBlank() ? current.name() : name,
                publicSignup == null ? current.publicSignup() : publicSignup));
        realm().groups().group(g.getId()).update(g);
        return tenant(id).orElseThrow();
    }

    public List<Member> members(String tenant) {
        requireTenant(tenant);
        List<Member> out = new ArrayList<>();
        for (String role : tenantRoles) {
            roleGroup(tenant, role).ifPresent(g -> realm().groups().group(g.getId()).members(0, 10_000, false)
                    .forEach(u -> out.add(toMember(u, role))));
        }
        out.sort(Comparator.comparing(Member::username));
        return out;
    }

    public Optional<String> findUserId(String username) {
        return realm().users().search(username, true).stream().findFirst().map(UserRepresentation::getId);
    }

    /** Creates an enabled user whose temporary password must be changed at first login. */
    public String createUser(NewUser u) {
        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(u.username());
        rep.setEmail(u.email());
        rep.setFirstName(u.firstName());
        rep.setLastName(u.lastName());
        rep.setEnabled(true);
        rep.setEmailVerified(u.email() != null && !u.email().isBlank());
        String id = createdId(realm().users().create(rep));
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(u.temporaryPassword());
        cred.setTemporary(true);
        try {
            realm().users().get(id).resetPassword(cred);
        } catch (WebApplicationException e) {
            realm().users().get(id).remove(); // do not leave a user without a usable credential
            throw IamException.badRequest("password rejected by the realm password policy");
        }
        return id;
    }

    /** The user's role in {@code tenant}, read from the directory (not from a possibly stale token). */
    public Optional<String> roleOf(String tenant, String userId) {
        String prefix = TENANTS_ROOT + "/" + tenant + "/";
        return user(userId).groups().stream()
                .map(GroupRepresentation::getPath)
                .filter(p -> p != null && p.startsWith(prefix))
                .map(p -> p.substring(prefix.length()))
                .filter(tenantRoles::contains)
                .findFirst();
    }

    /** Gives the user exactly one role in {@code tenant}, keeping at least one admin in the tenant. */
    public void assignRole(String tenant, String userId, String role) {
        requireTenant(tenant);
        requireRole(role);
        Optional<String> current = roleOf(tenant, userId);
        if (current.equals(Optional.of(role))) return;
        if (current.equals(Optional.of(ADMIN_ROLE))) guardLastAdmin(tenant, userId);
        UserResource user = user(userId);
        user.joinGroup(roleGroup(tenant, role).orElseThrow(() -> IamException.notFound("role " + role)).getId());
        current.flatMap(r -> roleGroup(tenant, r)).ifPresent(g -> user.leaveGroup(g.getId()));
    }

    public void removeMember(String tenant, String userId) {
        requireTenant(tenant);
        Optional<String> current = roleOf(tenant, userId);
        if (current.isEmpty()) throw IamException.notFound("user " + userId + " is not a member of " + tenant);
        if (current.get().equals(ADMIN_ROLE)) guardLastAdmin(tenant, userId);
        UserResource user = user(userId);
        for (String role : tenantRoles) roleGroup(tenant, role).ifPresent(g -> user.leaveGroup(g.getId()));
    }

    void guardLastAdmin(String tenant, String leavingUserId) {
        long otherAdmins = roleGroup(tenant, ADMIN_ROLE).stream()
                .flatMap(g -> realm().groups().group(g.getId()).members(0, 10_000, true).stream())
                .filter(u -> !u.getId().equals(leavingUserId))
                .count();
        if (otherAdmins == 0) throw IamException.conflict("tenant " + tenant + " must keep at least one admin");
    }

    private void requireTenant(String tenant) {
        if (tenant(tenant).isEmpty()) throw IamException.notFound("tenant " + tenant);
    }

    private void requireRole(String role) {
        if (!tenantRoles.contains(role)) throw IamException.badRequest("role must be one of " + tenantRoles);
    }

    private Optional<GroupRepresentation> roleGroup(String tenant, String role) {
        return group(Memberships.groupPath(tenant, role));
    }

    private UserResource user(String userId) {
        UserResource user = realm().users().get(userId);
        try {
            user.toRepresentation();
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) throw IamException.notFound("user " + userId);
            throw e;
        }
        return user;
    }

    Optional<GroupRepresentation> group(String path) {
        try {
            return Optional.of(realm().getGroupByPath(path));
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) return Optional.empty();
            throw e;
        }
    }

    RealmResource realm() {
        return keycloak.realm(realmName);
    }

    static String createdId(Response response) {
        try (response) {
            if (response.getStatus() == 409) throw IamException.conflict("already exists (username or email in use)");
            if (response.getStatus() >= 400) {
                throw new IllegalStateException("Keycloak admin call failed: HTTP " + response.getStatus());
            }
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static Map<String, List<String>> attributes(String name, boolean publicSignup) {
        return Map.of(ATTR_NAME, List.of(name), ATTR_PUBLIC_SIGNUP, List.of(Boolean.toString(publicSignup)));
    }

    private static Tenant toTenant(GroupRepresentation g) {
        Map<String, List<String>> a = g.getAttributes() == null ? Map.of() : g.getAttributes();
        String name = a.getOrDefault(ATTR_NAME, List.of(g.getName())).getFirst();
        boolean publicSignup = Boolean.parseBoolean(a.getOrDefault(ATTR_PUBLIC_SIGNUP, List.of("false")).getFirst());
        return new Tenant(g.getName(), name, publicSignup);
    }

    private static Member toMember(UserRepresentation u, String role) {
        return new Member(u.getId(), u.getUsername(), u.getEmail(), u.getFirstName(), u.getLastName(),
                Boolean.TRUE.equals(u.isEnabled()), role);
    }
}
