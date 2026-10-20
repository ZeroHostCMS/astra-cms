package io.astra.kernel.tenant;

import jakarta.enterprise.context.RequestScoped;

/**
 * Per-request holder populated by {@link TenantResolverFilter}. Resources bind this into the
 * {@link io.astra.spi.tenant.TenantContext} scoped value around their service calls.
 */
@RequestScoped
public class TenantRequest {

    private String tenantId;
    private String userId;

    public String tenantId() { return tenantId; }
    public String userId() { return userId; }

    void tenantId(String tenantId) { this.tenantId = tenantId; }
    void userId(String userId) { this.userId = userId; }
}
