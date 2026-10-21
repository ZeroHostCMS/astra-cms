package io.astra.kernel.tenant;

import jakarta.enterprise.context.RequestScoped;

import java.util.Set;

/**
 * Per-request holder populated by {@link TenantResolverFilter}. Resources bind this into the
 * {@link io.astra.spi.tenant.TenantContext} scoped value around their service calls.
 */
@RequestScoped
public class TenantRequest {

    private String tenantId;
    private String userId;
    private Set<String> roles = Set.of();

    public String tenantId() { return tenantId; }
    /** Stable subject identifier of the authenticated user (the token's {@code sub}). */
    public String userId() { return userId; }
    /** The user's roles in {@link #tenantId()}. */
    public Set<String> roles() { return roles; }

    void tenantId(String tenantId) { this.tenantId = tenantId; }
    void userId(String userId) { this.userId = userId; }
    void roles(Set<String> roles) { this.roles = Set.copyOf(roles); }
}
