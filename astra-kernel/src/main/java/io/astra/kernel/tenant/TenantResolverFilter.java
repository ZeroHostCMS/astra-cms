package io.astra.kernel.tenant;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves the tenant for every request in a fixed order: explicit header, then host subdomain,
 * then the configured default (single-tenant mode). Requests that resolve to no tenant in
 * multi-tenant mode are rejected before reaching any resource.
 */
@Provider
public class TenantResolverFilter implements ContainerRequestFilter {

    public static final String TENANT_HEADER = "X-Astra-Tenant";
    public static final String USER_HEADER = "X-Astra-User";

    @Inject
    TenantRequest tenantRequest;

    @ConfigProperty(name = "astra.tenancy.mode", defaultValue = "multi")
    String mode;

    @ConfigProperty(name = "astra.tenancy.default-tenant", defaultValue = "default")
    String defaultTenant;

    @ConfigProperty(name = "astra.tenancy.base-domain")
    Optional<String> baseDomain;

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (ctx.getUriInfo().getPath().startsWith("q/")) {
            return; // health, metrics and other Quarkus endpoints are not tenant-scoped
        }

        String tenant = fromHeader(ctx).or(() -> fromHost(ctx)).orElse(null);
        if (tenant == null && "single".equals(mode)) {
            tenant = defaultTenant;
        }
        if (tenant == null || !tenant.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            ctx.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "tenant could not be resolved; send " + TENANT_HEADER
                            + " or use a tenant subdomain"))
                    .build());
            return;
        }
        tenantRequest.tenantId(tenant);
        tenantRequest.userId(Optional.ofNullable(ctx.getHeaderString(USER_HEADER)).orElse("system"));
    }

    private Optional<String> fromHeader(ContainerRequestContext ctx) {
        return Optional.ofNullable(ctx.getHeaderString(TENANT_HEADER)).map(String::trim).filter(s -> !s.isEmpty());
    }

    /** {@code acme.astra.example} with base domain {@code astra.example} resolves to {@code acme}. */
    private Optional<String> fromHost(ContainerRequestContext ctx) {
        if (baseDomain.isEmpty()) return Optional.empty();
        String host = ctx.getUriInfo().getRequestUri().getHost();
        String suffix = "." + baseDomain.get();
        if (host != null && host.endsWith(suffix)) {
            String label = host.substring(0, host.length() - suffix.length());
            if (!label.contains(".")) return Optional.of(label);
        }
        return Optional.empty();
    }
}
