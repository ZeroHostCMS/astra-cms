package io.astra.iam;

import io.astra.kernel.security.Memberships;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SCIM users and groups of one tenant, stored in Keycloak through {@link KeycloakDirectory}'s admin client.
 *
 * <ul>
 *   <li>A tenant's SCIM users are the identities its provisioning client created: direct members of the
 *       tenant group {@code /tenants/<tenant>}. That membership grants no role; it marks ownership, so a
 *       tenant's SCIM client can neither see nor change local members or another tenant's users.</li>
 *   <li>A tenant's SCIM groups are its role groups {@code /tenants/<tenant>/<role>}. Group membership is
 *       the tenant role that reaches the user's token (claim {@code groups}) at the next sign-in.</li>
 * </ul>
 */
@ApplicationScoped
public class ScimDirectory {

    private static final int MAX = 10_000;

    /** Attributes a client may write. {@code null} fields mean "not set"; {@code active} null means true. */
    public record UserInput(String userName, String givenName, String familyName, String email, Boolean active, String password) {
        UserInput withUserName(String v) { return new UserInput(v, givenName, familyName, email, active, password); }
        UserInput withGivenName(String v) { return new UserInput(userName, v, familyName, email, active, password); }
        UserInput withFamilyName(String v) { return new UserInput(userName, givenName, v, email, active, password); }
        UserInput withEmail(String v) { return new UserInput(userName, givenName, familyName, v, active, password); }
        UserInput withActive(Boolean v) { return new UserInput(userName, givenName, familyName, email, v, password); }
        UserInput withPassword(String v) { return new UserInput(userName, givenName, familyName, email, active, v); }
    }

    public record RoleRef(String id, String role) { }

    public record User(String id, String userName, String givenName, String familyName, String email,
                       boolean active, Long created, List<RoleRef> roles) {
        UserInput toInput() { return new UserInput(userName, givenName, familyName, email, active, null); }
    }

    public record MemberRef(String id, String userName) { }

    public record Group(String id, String role, List<MemberRef> members) { }

    public record Page<T>(int total, List<T> items) { }

    @Inject KeycloakDirectory directory;

    public boolean tenantExists(String tenant) {
        return directory.group(tenantPath(tenant)).isPresent();
    }

    public Page<User> users(String tenant, Optional<ScimFilter> userName, int startIndex, int count) {
        List<UserRepresentation> owned;
        if (userName.isPresent()) {
            owned = directory.realm().users().search(userName.get().value().toLowerCase(Locale.ROOT), true).stream()
                    .filter(u -> userName.get().matches(u.getUsername()))
                    .filter(u -> isOwned(tenant, groupsOf(u.getId())))
                    .toList();
        } else {
            owned = tenantGroup(tenant).members(0, MAX, false).stream()
                    .sorted(Comparator.comparing(UserRepresentation::getUsername)).toList();
        }
        List<User> page = owned.stream().skip(startIndex - 1L).limit(count)
                .map(u -> toUser(tenant, u, groupsOf(u.getId()))).toList();
        return new Page<>(owned.size(), page);
    }

    public User user(String tenant, String id) {
        return load(tenant, id).user();
    }

    /** Creates an enabled (unless {@code active} is false) user owned by {@code tenant}. */
    public User create(String tenant, UserInput in) {
        requireUserName(in.userName());
        if (!directory.realm().users().search(in.userName().toLowerCase(Locale.ROOT), true).isEmpty()) {
            throw ScimException.uniqueness("userName " + in.userName() + " is already taken");
        }
        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(in.userName());
        apply(rep, in);
        Response response = directory.realm().users().create(rep);
        if (response.getStatus() == 409 || response.getStatus() == 400) {
            try (response) {
                if (response.getStatus() == 409) throw ScimException.uniqueness("userName or email is already in use");
                throw ScimException.invalidValue("rejected by the identity provider: " + response.readEntity(String.class));
            }
        }
        String id = KeycloakDirectory.createdId(response);
        try {
            UserResource user = directory.realm().users().get(id);
            user.joinGroup(tenantGroupRep(tenant).getId());
            if (in.password() != null) setPassword(user, in.password());
        } catch (RuntimeException e) {
            directory.realm().users().get(id).remove();   // never leave a half-provisioned identity behind
            throw e;
        }
        return user(tenant, id);
    }

    /** Overwrites the writable attributes; deactivation also ends the user's Keycloak sessions. */
    public User replace(String tenant, String id, UserInput in) {
        Loaded current = load(tenant, id);
        requireUserName(in.userName());
        if (!in.userName().equalsIgnoreCase(current.rep().getUsername())) {
            throw ScimException.mutability("userName cannot be changed (the realm does not allow editing usernames)");
        }
        UserRepresentation rep = current.rep();
        boolean wasActive = Boolean.TRUE.equals(rep.isEnabled());
        apply(rep, in);
        UserResource user = directory.realm().users().get(id);
        try {
            user.update(rep);
        } catch (WebApplicationException e) {
            throw fromKeycloak(e);
        }
        if (in.password() != null) setPassword(user, in.password());
        if (wasActive && !Boolean.TRUE.equals(rep.isEnabled())) user.logout();
        return user(tenant, id);
    }

    public void delete(String tenant, String id) {
        Loaded current = load(tenant, id);
        if (current.user().roles().stream().anyMatch(r -> r.role().equals(KeycloakDirectory.ADMIN_ROLE))) {
            directory.guardLastAdmin(tenant, id);
        }
        directory.realm().users().get(id).remove();
    }

    public List<Group> groups(String tenant, boolean withMembers) {
        Set<String> owned = withMembers ? ownedIds(tenant) : Set.of();
        return roleGroups(tenant).stream().map(g -> toGroup(g, withMembers, owned)).toList();
    }

    public Group group(String tenant, String groupId, boolean withMembers) {
        GroupRepresentation g = roleGroup(tenant, groupId);
        return toGroup(g, withMembers, withMembers ? ownedIds(tenant) : Set.of());
    }

    /**
     * Makes the tenant-owned members of the role group exactly {@code desired}. Every id must be a user
     * this tenant provisioned; the tenant keeps at least one admin.
     */
    public Group setMembers(String tenant, String groupId, Set<String> desired) {
        GroupRepresentation g = roleGroup(tenant, groupId);
        Set<String> owned = ownedIds(tenant);
        for (String id : desired) {
            if (!owned.contains(id)) throw ScimException.invalidValue("member " + id + " is not a user of this tenant");
        }
        GroupResource group = directory.realm().groups().group(g.getId());
        Set<String> all = group.members(0, MAX, true).stream().map(UserRepresentation::getId).collect(Collectors.toSet());
        Set<String> current = all.stream().filter(owned::contains).collect(Collectors.toSet());
        Set<String> add = new LinkedHashSet<>(desired);
        add.removeAll(current);
        Set<String> remove = new HashSet<>(current);
        remove.removeAll(desired);
        if (g.getName().equals(KeycloakDirectory.ADMIN_ROLE) && !remove.isEmpty()) {
            Set<String> remaining = new HashSet<>(all);
            remaining.removeAll(remove);
            remaining.addAll(add);
            if (remaining.isEmpty()) throw IamException.conflict("tenant " + tenant + " must keep at least one admin");
        }
        for (String id : add) directory.realm().users().get(id).joinGroup(g.getId());
        for (String id : remove) directory.realm().users().get(id).leaveGroup(g.getId());
        return toGroup(g, true, owned);
    }

    private record Loaded(UserRepresentation rep, User user) { }

    /** The user when {@code tenant} owns it; otherwise 404, so other tenants' users stay invisible. */
    private Loaded load(String tenant, String id) {
        UserRepresentation rep;
        try {
            rep = directory.realm().users().get(id).toRepresentation();
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) throw ScimException.notFound("User " + id + " not found");
            throw e;
        }
        List<GroupRepresentation> groups = groupsOf(id);
        if (!isOwned(tenant, groups)) throw ScimException.notFound("User " + id + " not found");
        return new Loaded(rep, toUser(tenant, rep, groups));
    }

    private List<GroupRepresentation> groupsOf(String userId) {
        return directory.realm().users().get(userId).groups(0, MAX, true);
    }

    private static boolean isOwned(String tenant, List<GroupRepresentation> groups) {
        String path = tenantPath(tenant);
        return groups.stream().anyMatch(g -> path.equals(g.getPath()));
    }

    private Set<String> ownedIds(String tenant) {
        return tenantGroup(tenant).members(0, MAX, true).stream().map(UserRepresentation::getId).collect(Collectors.toSet());
    }

    private List<GroupRepresentation> roleGroups(String tenant) {
        return tenantGroup(tenant).getSubGroups(0, MAX, true).stream()
                .filter(g -> directory.tenantRoles().contains(g.getName()))
                .sorted(Comparator.comparing(g -> directory.tenantRoles().indexOf(g.getName())))
                .toList();
    }

    private GroupRepresentation roleGroup(String tenant, String groupId) {
        return roleGroups(tenant).stream().filter(g -> g.getId().equals(groupId)).findFirst()
                .orElseThrow(() -> ScimException.notFound("Group " + groupId + " not found"));
    }

    private Group toGroup(GroupRepresentation g, boolean withMembers, Set<String> owned) {
        if (!withMembers) return new Group(g.getId(), g.getName(), null);
        List<MemberRef> members = directory.realm().groups().group(g.getId()).members(0, MAX, true).stream()
                .filter(u -> owned.contains(u.getId()))
                .sorted(Comparator.comparing(UserRepresentation::getUsername))
                .map(u -> new MemberRef(u.getId(), u.getUsername()))
                .toList();
        return new Group(g.getId(), g.getName(), members);
    }

    private User toUser(String tenant, UserRepresentation u, List<GroupRepresentation> groups) {
        String prefix = Memberships.GROUP_PREFIX + tenant + "/";
        List<RoleRef> roles = new ArrayList<>();
        for (GroupRepresentation g : groups) {
            if (g.getPath() != null && g.getPath().startsWith(prefix) && directory.tenantRoles().contains(g.getName())) {
                roles.add(new RoleRef(g.getId(), g.getName()));
            }
        }
        return new User(u.getId(), u.getUsername(), u.getFirstName(), u.getLastName(), u.getEmail(),
                Boolean.TRUE.equals(u.isEnabled()), u.getCreatedTimestamp(), roles);
    }

    private GroupResource tenantGroup(String tenant) {
        return directory.realm().groups().group(tenantGroupRep(tenant).getId());
    }

    private GroupRepresentation tenantGroupRep(String tenant) {
        return directory.group(tenantPath(tenant)).orElseThrow(() -> ScimException.forbidden("tenant " + tenant + " does not exist"));
    }

    private static String tenantPath(String tenant) {
        return KeycloakDirectory.TENANTS_ROOT + "/" + tenant;
    }

    private static void apply(UserRepresentation rep, UserInput in) {
        rep.setFirstName(in.givenName());
        rep.setLastName(in.familyName());
        rep.setEmail(in.email());
        rep.setEmailVerified(in.email() != null && !in.email().isBlank());
        rep.setEnabled(in.active() == null || in.active());
    }

    private static void requireUserName(String userName) {
        if (userName == null || userName.isBlank()) throw ScimException.invalidValue("userName is required");
    }

    private static void setPassword(UserResource user, String password) {
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(false);
        try {
            user.resetPassword(cred);
        } catch (WebApplicationException e) {
            throw ScimException.invalidValue("password rejected by the realm password policy");
        }
    }

    private static ScimException fromKeycloak(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        if (status == 409) return ScimException.uniqueness("userName or email is already in use");
        if (status == 400) return ScimException.invalidValue("rejected by the identity provider: " + keycloakError(e));
        if (status == 404) return ScimException.notFound("resource no longer exists");
        throw e;
    }

    private static String keycloakError(WebApplicationException e) {
        try {
            String body = e.getResponse().readEntity(String.class);
            return body == null || body.isBlank() ? "HTTP 400" : body;
        } catch (RuntimeException ignored) {
            return "HTTP 400";
        }
    }
}
