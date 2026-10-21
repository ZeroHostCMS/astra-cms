package io.astra.kernel.security;

import io.quarkus.security.identity.SecurityIdentity;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Tenant memberships carried by an authenticated identity. The identity provider issues group paths
 * of the form {@code /tenants/<tenant>/<role>} (Keycloak groups with full paths in the {@code groups}
 * claim), and the OIDC layer maps them to identity roles; this class turns them back into
 * {@code tenant -> roles}. Roles are therefore always evaluated per tenant.
 */
public record Memberships(Map<String, Set<String>> byTenant, boolean platformAdmin) {

    public static final String GROUP_PREFIX = "/tenants/";
    public static final String PLATFORM_ADMIN = "platform-admin";

    public static Memberships of(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) return new Memberships(Map.of(), false);
        Map<String, Set<String>> out = new TreeMap<>();
        for (String role : identity.getRoles()) {
            if (!role.startsWith(GROUP_PREFIX)) continue;
            String[] parts = role.substring(GROUP_PREFIX.length()).split("/");
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                out.computeIfAbsent(parts[0], t -> new TreeSet<>()).add(parts[1]);
            }
        }
        out.replaceAll((t, roles) -> Collections.unmodifiableSet(roles));
        return new Memberships(Collections.unmodifiableMap(out), identity.hasRole(PLATFORM_ADMIN));
    }

    /** Roles in {@code tenant}; empty when the identity is not a member. */
    public Set<String> roles(String tenant) {
        return byTenant.getOrDefault(tenant, Set.of());
    }

    public boolean isMember(String tenant) {
        return byTenant.containsKey(tenant);
    }

    public static String groupPath(String tenant, String role) {
        return GROUP_PREFIX + tenant + "/" + role;
    }
}
